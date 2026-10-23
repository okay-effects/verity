package verity

import okay.effects.{Runner, Sum, Void}
import okay.effects.json.Json
import okay.effects.json.Json.{Str, obj}
import okay.effects.machine.Machine
import okay.effects.free.Free
import okay.faces.{Chosen, Face, Faces, Navigation, Pages, View}
import okay.faces.test.Plays

/** THE WHOLE APPLICATION under the test face: a script of choices, every handler in memory, on an implementation */
abstract class ScenarioChecks(val fx0: Runner, name: String) extends munit.FunSuite {
  val faces: Faces[fx0.type] = new Faces[fx0.type]()(fx0)
  val plays: Plays[fx0.type] = new Plays[fx0.type]()(fx0)
  val app = new Verity[fx0.type](faces, () => Some(okay.effects.shape.Day(2026, 10, 21)))
  import fx0._
  import app.domain._
  import plays._

  type F[X] = Sum[Void, Face, X]
  type FN[X] = Sum[F, app.Nav, X]
  type FNF[X] = Sum[FN, Files, X]
  type FNFC[X] = Sum[FNF, Clipboard, X]
  type FNFCR[X] = Sum[FNFC, Reveal, X]
  type FNFCRC[X] = Sum[FNFCR, Consent, X]
  type All[X] = Sum[FNFCRC, Store, X]

  var n = 0
  def ids(): String = { n += 1; s"id$n" }
  def clock(): Long = 1000L + n
  final case class Outcome(views: List[View], vault: Vault, clipboard: List[String], files: Map[String, List[String]],
    pages: List[app.Place], actions: List[String])

  /** the application wired with handlers in memory, driven by the script. There is no quit: the dialogue never ends
   * by itself, so a script ends at the main screen, and what the handlers keep is read from what they tell */
  def play(script: Script, files: Map[String, List[String]] = Map.empty, audience: Audience = Audience.Owner, consents: List[Boolean] = Nil): Outcome = {
    val pages = new Pages[app.Place](app.Main)
    var vault = Vault.empty
    var clip: List[String] = Nil
    var fs = files
    implicit val needs: app.Needs[All] = app.Needs[All]
    val program = app.run[All]("v.jsonl")
    val wired = store(Vault.empty, () => ids(), () => clock(), v => vault = v)(consenting(consents)(policy(audience)(
      clipboardRecording.telling(c => clip = c)(filesInMemory(files, f => fs = f)(app.navs.memory(pages)(program))))))
    val played = plays.play(faces.steps[(Vault, (List[String], (Map[String, List[String]], Unit))), F, Void](wired), script)
    played match {
      case Played.Stuck(views, at, None) if pages.current == app.Main =>
        Outcome(views, vault, clip, fs, pages.history, at.actions.map(_.name).toList)
      case Played.Stuck(views, at, rejected) => fail(s"stuck at ${at.view} with $rejected after ${views.size} views: ${views.lastOption}")
      case Played.Finished(views, _) => fail(s"the dialogue ended by itself after ${views.size} views; it has no quit")
    }
  }
  val passport = obj("country" -> Str("UA"), "number" -> Str("AB123456"), "issued" -> Str("2020-01-02"), "expires" -> Str("2030-01-01"), "holder" -> Str("S."))
  val metaUA = obj("country" -> Str("UA"), "tags" -> Str("travel id"))

  test(s"$name: add a passport, open it, copy the number (audited), reveal, revise, history; saved at once, no Save") {
    val out = play(Script.of(
      Chosen.plain("add"), Chosen.plain("passport"), Chosen("submit", passport), Chosen("submit", metaUA),
      Chosen("open", obj("n" -> Str("1"))),
      Chosen("copy", obj("field" -> Str("number"))),
      Chosen("reveal", obj("field" -> Str("holder"))),
      Chosen.plain("revise"), Chosen("submit", passport), Chosen("submit", obj("tags" -> Str("travel renewed"), "note" -> Str("renewed"))),
      Chosen.plain("history"), Chosen.plain("done"),
      Chosen.plain("done")))
    assertEquals(out.clipboard, List("AB123456"))
    assertEquals(out.vault.records.size, 1)
    assertEquals(out.vault.records.head.tags, Set("travel", "renewed"))
    assertEquals(out.vault.history(out.vault.records.head.id).size, 2)
    assertEquals(out.vault.events.collect { case e: Event.Accessed => (e.field, e.by) }, List(("number", "owner"), ("holder", "owner")))
    assertEquals(out.vault.unsaved, 0)   // no Save pressed: there is none, every change was written at once
    assertEquals(out.actions, List("open", "add", "find", "define"))   // and no Quit
    assertEquals(out.files.keySet, Set("v.jsonl"))
    val (_, events, damaged) = Journal.read(out.files("v.jsonl"))
    assertEquals(damaged, Nil); assertEquals(events.size, out.vault.events.size)
    // what was shown: the list masks the number, the card masks it, the reveal shows the holder
    assert(out.views.exists { case View.Group(_, cs) => cs.exists { case View.Table(_, rows) => rows.exists(_.exists { case View.Text(t) => t.contains("•••• 3456"); case _ => false }); case _ => false }; case _ => false }, out.views.mkString("\n"))
    assert(out.views.contains(View.fields("holder" -> View.text("S."))))   // holder is no secret: shown unmarked
    assertEquals(out.pages.head, app.Main)
    // the marks, read back as the type: the card's heading is the kind, the number a secret, the validity holds (no end)
    val card = out.views.collectFirst { case View.Group(_, cs) if cs.headOption.flatMap(app.marks.on).contains(Mark.Kind) => cs }.getOrElse(fail("no card"))
    assert(card.exists { case View.Fields(rows) => rows.exists { case (_, v) => app.marks.on(v).contains(Mark.Secret) }; case _ => false }, card.toString)
    assert(card.exists { case View.Fields(rows) => rows.exists { case ("valid", v) => app.marks.on(v).contains(Mark.Valid); case _ => false }; case _ => false }, card.toString)
  }
  test(s"$name: a validity over is marked expired, one ending within thirty days expiring; a revealed secret is marked") {
    val out = play(Script.of(
      Chosen.plain("add"), Chosen.plain("passport"), Chosen("submit", passport), Chosen("submit", obj("country" -> Str("UA"), "to" -> Str("2026-11-15"))),
      Chosen.plain("add"), Chosen.plain("passport"), Chosen("submit", passport), Chosen("submit", obj("country" -> Str("UA"), "to" -> Str("2025-01-01"))),
      Chosen("open", obj("n" -> Str("1"))), Chosen("reveal", obj("field" -> Str("number"))), Chosen.plain("done")))
    val cells = out.views.collect { case View.Group(_, cs) => cs.collect { case View.Table(_, rows) => rows.flatMap(_.flatMap(app.marks.on)) }.flatten }.flatten
    assert(cells.contains(Mark.Expiring) && cells.contains(Mark.Expired), cells.toString)
    assert(out.views.contains(View.fields("number" -> app.marks.mark(Mark.Revealed, View.text("AB123456")))), out.views.mkString("\n"))
  }
  test(s"$name: a kind of one's own, a record of it, a find by tag, a retire; each change saved at once, in the file") {
    val spec = obj("name" -> Str("UK NI number"), "fields" -> Json.arr(obj("name" -> Str("value"), "label" -> Str("Number"), "kind" -> Str("text"), "secret" -> Json.Bool(true))), "contextual" -> Json.Bool(false))
    val out = play(Script.of(
      Chosen.plain("define"), Chosen("submit", spec),
      Chosen.plain("add"), Chosen.plain("uk-ni-number"), Chosen("submit", obj("value" -> Str("QQ123456C"))), Chosen("submit", obj("tags" -> Str("uk"))),
      Chosen("find", obj("text" -> Str(""), "tag" -> Str("uk"))),
      Chosen("open", obj("n" -> Str("1"))), Chosen("retire", obj()), Chosen.plain("yes")))
    assert(out.vault.kinds.contains(KindId("uk-ni-number")))
    assertEquals(out.vault.records, Nil)
    assertEquals(out.vault.versions.values.map(_.size).toList, List(1))   // retired, its history stays
    assertEquals(out.vault.unsaved, 0); assertEquals(out.files.keySet, Set("v.jsonl"))
    val (_, events, _) = Journal.read(out.files("v.jsonl"))
    assertEquals(events, out.vault.events)   // the kind defined, the record added, retired: each written as it happened
    val kept = Vault.of(events)
    assert(kept.kinds.contains(KindId("uk-ni-number"))); assertEquals(kept.records, Nil)
    assertEquals(kept.retired.size, 1)
    assert(out.views.exists { case View.Group(_, cs) => cs.exists { case View.Table(_, rows) => rows.exists(_.exists { case View.Text(t) => t == "••••" || t.startsWith("•••• "); case _ => false }); case _ => false }; case _ => false })
  }
  test(s"$name: a damaged journal is read with a warning; an agent's copy needs consent") {
    val lines = Journal.write(Journal.Header("verity", 1, 1L), Nil) ++ List("garbage")
    val out = play(Script.of(), files = Map("v.jsonl" -> lines))
    assert(out.views.exists { case View.Notice(View.Notice.Warning, s) => s.contains("1 damaged"); case _ => false }, out.views.mkString("\n"))
    val agent = play(Script.of(
      Chosen.plain("add"), Chosen.plain("passport"), Chosen("submit", passport), Chosen("submit", metaUA),
      Chosen("open", obj("n" -> Str("1"))), Chosen("copy", obj("field" -> Str("number"))),
      Chosen("copy", obj("field" -> Str("number"))), Chosen.plain("done")),
      audience = Audience.Agent("claude"), consents = List(false, true))
    assertEquals(agent.clipboard, List("AB123456"))
    assertEquals(agent.vault.events.collect { case e: Event.Accessed => e.by }, List("agent:claude"))
    assert(agent.views.contains(View.problem("number not shown")))
  }
}
class ScenarioOnMachine extends ScenarioChecks(Machine, "machine")
class ScenarioOnFree extends ScenarioChecks(Free, "free")
