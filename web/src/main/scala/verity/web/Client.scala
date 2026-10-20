package verity.web

import com.raquo.laminar.api.L._
import okay.face.laminar.RemoteFace
import okay.http.Browser
import org.scalajs.dom
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** THE PAGE of `verity app`: okay-faces' remote face over the server's REST face. It knows nothing of verity — the views,
 * the forms, the actions all come from the server; the look comes as /okay.css from the same server */
object Client {
  def main(args: Array[String]): Unit =
    renderOnDomContentLoaded(dom.document.getElementById("app"), RemoteFace(Browser.fetch()).element("/sessions"))
}
