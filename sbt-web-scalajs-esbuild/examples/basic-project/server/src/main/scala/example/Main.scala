package example

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._

import scala.util.Failure
import scala.util.Success

object Main extends App {
  implicit val system = ActorSystem(Behaviors.empty, "main-system")
  implicit val executionContext = system.executionContext

  val route = concat(
    pathSingleSlash {
      get {
        encodeResponse {
          getFromResource(s"index.html")
        }
      }
    },
    path("hello") {
      get {
        complete(
          HttpEntity(
            ContentTypes.`text/plain(UTF-8)`,
            "basic-project-sbt-web works!"
          )
        )
      }
    },
    path("favicon.ico") {
      complete("")
    },
    path(Remaining) { file =>
      encodeResponse {
        getFromResource(file)
      }
    }
  )

  val binding = Http().newServerAt("0.0.0.0", 8080).bind(route)

  binding.onComplete {
    case Success(binding) =>
      println(
        s"HTTP server bound to: http://localhost:${binding.localAddress.getPort}"
      )
    case Failure(ex) =>
      println(s"HTTP server binding failed: ${ex.getMessage}")
      system.terminate()
  }
}
