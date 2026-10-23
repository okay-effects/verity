package verity.web

import com.raquo.laminar.api.L._
import okay.effects.{Sum, Void}
import okay.effects.machine.Machine
import okay.http.{Request, Response}
import okay.store.Stores
import org.scalajs.dom
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import verity._

/** THE DIALOGUE IN THE BROWSER under jsdom, its server in the same page: a passport added through the forms, the
 * store on the "server" changed, the list showing the number masked */
class LocalSuite extends munit.FunSuite {
  import Machine._
  import Local.app.domain._
  type S[X] = Sum[Void, Store, X]
  type F[X] = Sum[Void, Files, X]
  var vault: Vault = Vault.empty
  var files: Map[String, List[String]] = Map.empty
  var n = 0
  val storeServed = Local.rest.serve[Store, S](Stores.describe, "/store")
  val filesServed = Local.rest.serve[Files, F](Describes.files, "/files")
  def server(req: Request): Response =
    if (req.path.startsWith("/store")) { val (v, r) = Machine.run(store(vault, () => { n += 1; s"id$n" }, () => 1000L + n)(storeServed.service(req))); vault = v; r }
    else if (req.path.startsWith("/files")) { val (fs, r) = Machine.run(filesInMemory(files)(filesServed.service(req))); files = fs; r }
    else Response.notFound(req.path)
  val send: Request => Future[Response] = req => Future.successful(server(req))

  def mounted(el: HtmlElement): dom.html.Element = {
    dom.document.body.innerHTML = ""
    val c = dom.document.createElement("div").asInstanceOf[dom.html.Element]
    dom.document.body.appendChild(c); render(c, el); c
  }
  def q(root: dom.Element, sel: String): dom.html.Element = { val e = root.querySelector(sel); assert(e != null, s"no $sel in: ${root.innerHTML}"); e.asInstanceOf[dom.html.Element] }
  def typeInto(root: dom.Element, id: String, v: String): Unit = q(root, s"#$id").asInstanceOf[dom.html.Input].value = v
  def submit(root: dom.Element, action: String): Unit = q(root, s"form[data-action=$action]").dispatchEvent(new dom.Event("submit", new dom.EventInit { bubbles = true; cancelable = true }))
  def settled: Future[Unit] = (1 to 12).foldLeft(Future.unit)((f, _) => f.flatMap(_ => Future(())))

  test("the main screen from the server's store; a passport added; the list with the number masked; " +
    "saved to the server's file at once") {
    val root = mounted(Local.element(send, "v.jsonl"))
    settled.flatMap { _ =>
      assertEquals(q(root, "h1").textContent, "Verity")
      assert(root.innerHTML.contains("Nothing kept yet"), root.innerHTML)
      q(root, "button[data-action=add]").click(); settled
    }.flatMap { _ =>
      q(root, "button[data-action=passport]").click(); settled
    }.flatMap { _ =>
      typeInto(root, "f-country", "UA"); typeInto(root, "f-number", "AB123456"); typeInto(root, "f-issued", "2020-01-02")
      typeInto(root, "f-expires", "2030-01-01"); typeInto(root, "f-holder", "S.")
      submit(root, "submit"); settled
    }.flatMap { _ =>
      typeInto(root, "f-country", "UA"); typeInto(root, "f-tags", "travel")
      submit(root, "submit"); settled
    }.map { _ =>
      assert(root.innerHTML.contains("•••• 3456") && !root.innerHTML.contains("AB123456"), root.innerHTML)
      assertEquals(vault.records.size, 1); assertEquals(vault.unsaved, 0)
      assert(files.get("v.jsonl").exists(_.size == 2), files.toString)   // no Save pressed: the header and the add
      List("save", "quit").foreach(a => assert(root.querySelector(s"button[data-action=$a]") == null, root.innerHTML))
    }
  }
}
