package verity.web

import com.raquo.laminar.api.L._
import okay.effects.{Sum, Void}
import okay.effects.async.{Async, Asynchrony}
import okay.effects.machine.Machine
import okay.faces.{Face, Faces, Pages}
import okay.faces.laminar.{LaminarFace, RemoteFace}
import okay.faces.rest.RestDomains
import okay.http.{Browser, Fetch, Fetches, Request, Response}
import okay.store.Stores
import org.scalajs.dom
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import verity._

/** THE PAGE: by the root's `data-mode` — `remote`, okay-faces' remote face over the server's REST face, the dialogue
 * there (`verity app`); `local`, the dialogue HERE, verity's own program under Laminar, its store and its file on the
 * server over REST (`verity browser`). One script, one application, two cuts; the look is /okay.css either way */
object Client {
  def main(args: Array[String]): Unit = {
    val root = dom.document.getElementById("app")
    val element = Option(root.getAttribute("data-mode")) match {
      case Some("local") => Local.element(Browser.fetch(), Option(root.getAttribute("data-file")).getOrElse("verity.jsonl"))
      case _ => RemoteFace(Browser.fetch()).element("/sessions")
    }
    renderOnDomContentLoaded(root, element)
  }
}

/** THE DIALOGUE IN THE BROWSER: the same `Verity` scenario as at the terminal, wired with the browser's handlers — the
 * owner asked at this face, the policy here, the clipboard the browser's — and with `Store` and `Files` performed
 * from afar: each operation a call to the server's `/store` and `/files`, awaited through `Async` */
object Local {
  val faces: Faces[Machine.type] = new Faces[Machine.type]()(Machine)
  val app = new Verity[Machine.type](faces, () => { val d = new scala.scalajs.js.Date(); Some(okay.effects.shape.Day(d.getFullYear().toInt, d.getMonth().toInt + 1, d.getDate().toInt)) })
  val asynchrony: Asynchrony[Machine.type] = new Asynchrony[Machine.type]()(Machine, queue)
  val rest: RestDomains[Machine.type] = new RestDomains[Machine.type]()(Machine)
  val face = new LaminarFace[Machine.type]()(faces)
  import app.domain._
  import Machine._

  type LFe[X] = Sum[Async, Fetch, X]
  type LFeF[X] = Sum[LFe, Face, X]
  type N[X] = Sum[LFeF, app.Nav, X]
  type NF[X] = Sum[N, Files, X]
  type NFC[X] = Sum[NF, Clipboard, X]
  type NFCR[X] = Sum[NFC, Reveal, X]
  type NFCRC[X] = Sum[NFCR, Consent, X]
  type All[X] = Sum[NFCRC, Store, X]
  type LF[X] = Sum[Async, Face, X]

  def put(text: String): Unit = try dom.window.navigator.clipboard.writeText(text) catch { case _: Throwable => () }

  /** the element: `send` carries a request to the server — the browser's fetch, or a server in the same page in a test */
  def element(send: Request => Future[Response], file: String, done: () => Unit = () => ()): HtmlElement = {
    implicit val needs: app.Needs[All] = app.Needs[All]
    val pages = new Pages[app.Place](app.Main)
    val program = rest.remote[Store](Stores.describe, "/store")(askingTheOwner(faces)(policy(Audience.Owner)(
      clipboardTo(put)(rest.remote[Files](Describes.files, "/files")(app.navs.memory(pages)(app.run[All](file)))))))
    val fetches = new Fetches[Machine.type](asynchrony, send)
    val here: Unit ! LF = fetches(program)
    face.elementAsync[Unit](faces.steps[Unit, LF, Async](here), (_: Unit) => done())
  }
}
