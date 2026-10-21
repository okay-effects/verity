package verity

import okay.effects.{Sum, Void}
import okay.effects.json.Json
import okay.effects.json.Json.{Str, num, obj}
import okay.effects.machine.Machine
import okay.faces.{Face, Faces, Pages}
import okay.mcp.{Link, Rpc}

/** THE AGENT'S FACE of verity over a scripted link: the screens as tools, an add, a copy that asks the owner */
class AgentSuite extends munit.FunSuite {
  val faces: Faces[Machine.type] = new Faces[Machine.type]()(Machine)
  val agents = new Agents[Machine.type](faces)
  val app = new Verity[Machine.type](faces)
  import Machine._
  import app.domain._

  type L[X] = Sum[Void, Link, X]
  type LF[X] = Sum[L, Face, X]
  type N[X] = Sum[LF, app.Nav, X]
  type NF[X] = Sum[N, Files, X]
  type NFC[X] = Sum[NF, Clipboard, X]
  type NFCR[X] = Sum[NFC, Reveal, X]
  type NFCRC[X] = Sum[NFCR, Consent, X]
  type All[X] = Sum[NFCRC, Store, X]
  var n = 0
  def ids(): String = { n += 1; s"id$n" }

  def wired: (Vault, (List[String], (Map[String, List[String]], Unit))) ! LF = {
    implicit val needs: app.Needs[All] = app.Needs[All]
    val pages = new Pages[app.Place](app.Main)
    store(Vault.empty, ids, () => 1000L)(agents.consentByElicitation(policy(Audience.Agent("claude"))(clipboardRecording(filesInMemory(Map.empty)(app.navs.memory(pages)(app.run[All]("v.jsonl")))))))
  }
  def line(m: Rpc): String = Rpc.encode(m)
  def rpc(l: String): Rpc = Rpc.decode(l).fold(f => f, m => m)
  val init = line(Rpc.Request(num(1), "initialize", obj("protocolVersion" -> Str("2025-06-18"), "capabilities" -> obj("elicitation" -> Json.Obj.empty), "clientInfo" -> obj("name" -> Str("claude")))))
  val passport = obj("country" -> Str("UA"), "number" -> Str("AB123456"), "issued" -> Str("2020-01-02"), "expires" -> Str("2030-01-01"), "holder" -> Str("S."))

  test("the agent adds a passport, opens it, asks for the number: the owner is asked, consents once, declines once") {
    val lines = List(init,
      line(Rpc.Request(num(2), "tools/list", Json.Obj.empty)),
      line(Rpc.Request(num(3), "tools/call", obj("name" -> Str("add")))),
      line(Rpc.Request(num(4), "tools/call", obj("name" -> Str("passport")))),
      line(Rpc.Request(num(5), "tools/call", obj("name" -> Str("submit"), "arguments" -> passport))),
      line(Rpc.Request(num(6), "tools/call", obj("name" -> Str("submit"), "arguments" -> obj("country" -> Str("UA"))))),
      line(Rpc.Request(num(7), "tools/call", obj("name" -> Str("open"), "arguments" -> obj("n" -> Str("1"))))),
      line(Rpc.Request(num(8), "tools/call", obj("name" -> Str("copy"), "arguments" -> obj("field" -> Str("number"))))),
      """{"jsonrpc":"2.0","id":"elicit-1","result":{"action":"accept","content":{"allow":true}}}""",
      line(Rpc.Request(num(9), "tools/call", obj("name" -> Str("copy"), "arguments" -> obj("field" -> Str("number"))))),
      """{"jsonrpc":"2.0","id":"elicit-2","result":{"action":"decline"}}""")
    val server = agents.server[(Vault, (List[String], (Map[String, List[String]], Unit))), L](faces.steps[(Vault, (List[String], (Map[String, List[String]], Unit))), LF, L](wired))
    val (sent, _) = Machine.run(agents.face.servers.links.scripted(lines)(agents.face.servers.serve[L](server)))
    val replies = sent.map(rpc)
    val texts = replies.collect { case Rpc.Answer(_, r) => Json.print(r) }
    assert(texts.exists(_.contains("\"name\":\"add\"")), texts.mkString("\n"))                       // the main screen's actions as tools
    assert(texts.exists(_.contains("3456")), texts.mkString("\n"))                                    // the list with the number masked
    val asked = replies.collect { case Rpc.Request(_, "elicitation/create", p: Json.Obj) => Json.print(p) }
    assertEquals(asked.size, 2)
    assert(asked.head.contains("An agent asks for a secret") && asked.head.contains("claude"), asked.head)
    assert(texts.exists(_.contains("number copied")), texts.mkString("\n"))
    // the marks: told in the instructions, served as a resource; the card's Markdown names the kind and the secret
    assert(texts.head.contains("Marks on views, and what they mean") && texts.head.contains("revealed: a secret shown in the clear"), texts.head)
    assert(texts.exists(t => t.contains("**kind:** Passport") && t.contains("secret: •••• 3456")), texts.mkString("\n"))
    assert(texts.exists(_.contains("number not shown")), texts.mkString("\n"))
  }
}
