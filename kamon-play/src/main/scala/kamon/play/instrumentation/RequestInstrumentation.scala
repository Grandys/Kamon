/* =========================================================================================
 * Copyright © 2013-2014 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.play.instrumentation

import kamon.Kamon
import kamon.play.{ Play, PlayExtension }
import kamon.trace.TraceLocal.{ HttpContextKey, HttpContext }
import kamon.trace._
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation._
import play.api.mvc._
import play.api.Routes
import play.libs.Akka

@Aspect
class RequestInstrumentation {

  import RequestInstrumentation._

  @DeclareMixin("play.api.mvc.RequestHeader+")
  def mixinContextAwareNewRequest: TraceContextAware = TraceContextAware.default

  @Before("call(* play.api.GlobalSettings.onRouteRequest(..)) && args(requestHeader)")
  def beforeRouteRequest(requestHeader: RequestHeader): Unit = {
    import Kamon.tracer
    val playExtension = Kamon(Play)

    val defaultTraceName = playExtension.generateTraceName(requestHeader)
    val token = if (playExtension.includeTraceToken) {
      requestHeader.headers.toSimpleMap.find(_._1 == playExtension.traceTokenHeaderName).map(_._2)
    } else None

    val newContext = token.map(t ⇒ tracer.newContext(defaultTraceName, t)).getOrElse(tracer.newContext(defaultTraceName))
    TraceContext.setCurrentContext(newContext)
  }

  @Around("call(* play.api.GlobalSettings.doFilter(*)) && args(next)")
  def aroundDoFilter(pjp: ProceedingJoinPoint, next: EssentialAction): Any = {
    val essentialAction = (requestHeader: RequestHeader) ⇒ {

      val playExtension = Kamon(Play)
      val executor = playExtension.defaultDispatcher

      def onResult(result: SimpleResult): SimpleResult = {
        TraceContext.map { ctx ⇒
          ctx.finish()

          recordHttpServerMetrics(result.header.status.toString, ctx.name)

          if (playExtension.includeTraceToken) result.withHeaders(playExtension.traceTokenHeaderName -> ctx.token)
          else result
        } getOrElse result
      }

      //store in TraceLocal useful data to diagnose errors
      storeDiagnosticData(requestHeader)

      //override the current trace name
      normaliseTraceName(requestHeader).map(TraceContext.currentContext.rename)

      // Invoke the action
      next(requestHeader).map(onResult)(executor)
    }
    pjp.proceed(Array(EssentialAction(essentialAction)))
  }

  @Before("call(* play.api.GlobalSettings.onError(..)) && args(request, ex)")
  def beforeOnError(request: TraceContextAware, ex: Throwable): Unit = {
    TraceContext.map { ctx ⇒
      recordHttpServerMetrics("500", ctx.name)
    }
  }

  def recordHttpServerMetrics(status: String, traceName: String): Unit =
    Kamon(Play).httpServerMetrics.recordResponse(traceName, status)

  def storeDiagnosticData(request: RequestHeader): Unit = {
    val agent = request.headers.get(UserAgent).getOrElse(Unknown)
    val forwarded = request.headers.get(XForwardedFor).getOrElse(Unknown)

    TraceLocal.store(HttpContextKey)(HttpContext(agent, request.uri, forwarded))
  }
}

object RequestInstrumentation {
  import kamon.util.TriemapAtomicGetOrElseUpdate.Syntax
  import java.util.Locale
  import scala.collection.concurrent.TrieMap

  val UserAgent = "User-Agent"
  val XForwardedFor = "X-Forwarded-For"
  val Unknown = "unknown"

  private val cache = TrieMap.empty[String, String]

  def normaliseTraceName(requestHeader: RequestHeader): Option[String] = requestHeader.tags.get(Routes.ROUTE_VERB).map({ verb ⇒
    val path = requestHeader.tags(Routes.ROUTE_PATTERN)
    cache.atomicGetOrElseUpdate(s"$verb$path", {
      val traceName = {
        // Convert paths of form GET /foo/bar/$paramname<regexp>/blah to foo.bar.paramname.blah.get
        val p = path.replaceAll("""\$([^<]+)<[^>]+>""", "$1").replace('/', '.').dropWhile(_ == '.')
        val normalisedPath = {
          if (p.lastOption.filter(_ != '.').isDefined) s"$p."
          else p
        }
        s"$normalisedPath${verb.toLowerCase(Locale.ENGLISH)}"
      }
      traceName
    })
  })
}
