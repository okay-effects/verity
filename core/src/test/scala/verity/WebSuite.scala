package verity

import okay.effects.{Sum, Void}
import okay.effects.json.Json
import okay.effects.json.Json.{Str, num, obj}
import okay.effects.machine.Machine
import okay.faces.{Face, Faces, Pages}
import okay.http.{Http, Https, Query, Request}

/** VERITY IN A BROWSER AND AS AN API, under a scripted HTTP: the pages and the resources of the same dialogue */
class WebSuite extends munit.FunSuite {
  val faces: Faces[Machine.type] = new Faces[Machine.type]()(Machine)
  val app = new Verity[Machine.type](faces)
  val https = new Https[Machine.type]()(Machine)
  import Machine._
  import app.domain._
  type L[X] = Sum[Void, Http, X]
  type HF[X] = Sum[L, Face, X]
  type HN[X] = Sum[HF, app.Nav, X]
  type HNF[X] = Sum[HN, Files, X]
  type HNFC[X] = Sum[HNF, Clipboard, X]
  type HNFCR[X] = Sum[HNFC, Reveal, X]
  type HNFCRC[X] = Sum[HNFCR, Consent, X]
  type HAll[X] = Sum[HNFCRC, Store, X]
  var n = 0
  def session(): faces.Dialogue[(Vault, (List[String], (Map[String, List[String]], Unit))), L] = {
    implicit val needs: app.Needs[HAll] = app.Needs[HAll]
    val pages = new Pages[app.Place](app.Main)
    val program = store(Vault.empty, () => { n += 1; s"id$n" }, () => 1000L)(askingTheOwner(faces)(policy(Audience.Owner)(clipboardRecording(filesInMemory(Map.empty)(app.navs.memory(pages)(app.run[HAll]("v.jsonl")))))))
    faces.dialogue[(Vault, (List[String], (Map[String, List[String]], Unit))), L](faces.steps[(Vault, (List[String], (Map[String, List[String]], Unit))), HF, L](program))
  }
  def form(path: String, kv: (String, String)*): Request = Request.post(path, Query.print(kv.toList.groupBy(_._1).map { case (k, vs) => k -> vs.map(_._2) }), "application/x-www-form-urlencoded")

  test("in a browser: the list, add a passport through the pages, the list with the number masked, the card") {
    val html = new okay.faces.html.HtmlFace[Machine.type]()(Machine)
    val pages = html.pages[(Vault, (List[String], (Map[String, List[String]], Unit))), L](() => session(), "Verity", ids = okay.faces.Sessions.counting("s"))
    val rs = Machine.run(https.scripted(List(Request.get("/"), Request.get("/s/s1"),
      form("/s/s1/act/add"), form("/s/s1/act/passport"),
      form("/s/s1/act/submit", "country" -> "UA", "number" -> "AB123456", "issued" -> "2020-01-02", "expires" -> "2030-01-01", "holder" -> "S."),
      form("/s/s1/act/submit", "country" -> "UA", "tags" -> "travel"), Request.get("/s/s1"),
      form("/s/s1/act/open", "n" -> "1"), Request.get("/s/s1")))(https.serve[L](pages.service)))._1
    assertEquals(rs.map(_.status), List(303, 200, 303, 303, 303, 303, 200, 303, 200))
    assert(rs(1).body.contains("Nothing kept yet") && rs(1).body.contains("""value="add""""), rs(1).body)
    assert(rs(6).body.contains("•••• 3456") && !rs(6).body.contains("AB123456"), rs(6).body)
    assert(rs(8).body.contains("""<dt class="okay-label">Number</dt>""") && rs(8).body.contains("•••• 3456") && rs(8).body.contains("/s/s1/act/copy"), rs(8).body)
  }
  test("as an API: a session's resource with the actions as links; the add through links") {
    val rest = new okay.faces.rest.RestFace[Machine.type]()(Machine)
    val api = rest.sessions[(Vault, (List[String], (Map[String, List[String]], Unit))), L](() => session(), ids = okay.faces.Sessions.counting("s"))
    val rs = Machine.run(https.scripted(List(Request("POST", "/sessions"), Request("POST", "/sessions/s1/act/add"), Request("POST", "/sessions/s1/act/passport"),
      Request.json("POST", "/sessions/s1/act/submit", obj("country" -> Str("UA"), "number" -> Str("AB123456"), "issued" -> Str("2020-01-02"), "expires" -> Str("2030-01-01"), "holder" -> Str("S."))),
      Request.json("POST", "/sessions/s1/act/submit", obj("tags" -> Str("travel"))), Request.get("/sessions/s1")))(https.serve[L](api.service)))._1
    assertEquals(rs.map(_.status), List(201, 200, 200, 200, 200, 200))
    assert(rs.head.body.contains("\"rel\":\"add\"") && rs.head.body.contains("\"rel\":\"find\""), rs.head.body)
    assert(rs(2).body.contains("\"rel\":\"submit\"") && rs(2).body.contains("\"x-secret\":true"), rs(2).body)
    assert(rs.last.body.contains("•••• 3456") && !rs.last.body.contains("AB123456"), rs.last.body)
  }
}
