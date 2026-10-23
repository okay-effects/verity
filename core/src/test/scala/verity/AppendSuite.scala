package verity

import okay.effects.{Runner, Sum, Void}
import okay.effects.json.Json.{Str, obj}
import okay.effects.machine.Machine
import okay.effects.free.Free
import okay.faces.{Chosen, Face, Faces, Pages, Step, View}

/** THE JOURNAL IS APPEND-ONLY: two sessions over one file, back after a save, a repeated save, an old file — each
 * session the whole application under the test face, the file one map every session's handler reads and appends to */
abstract class AppendChecks(val fx0: Runner, name: String) extends munit.FunSuite {
  val faces: Faces[fx0.type] = new Faces[fx0.type]()(fx0)
  val app = new Verity[fx0.type](faces, () => Some(okay.effects.shape.Day(2026, 10, 21)))
  import fx0._
  import app.domain._

  type F[X] = Sum[Void, Face, X]
  type FN[X] = Sum[F, app.Nav, X]
  type FNF[X] = Sum[FN, Files, X]
  type FNFC[X] = Sum[FNF, Clipboard, X]
  type FNFCR[X] = Sum[FNFC, Reveal, X]
  type FNFCRC[X] = Sum[FNFCR, Consent, X]
  type All[X] = Sum[FNFCRC, Store, X]
  type A = (Vault, (List[String], Unit))
  type M[X] = X ! Pure
  type At = Step.Waiting[A, M]

  /** the disk: one map of files, shared by every session's handler */
  final class Disk(var files: Map[String, List[String]] = Map.empty) {
    def lines: List[String] = files.getOrElse("v.jsonl", Nil)
    def handler: FilesOn = filesOn(p => Right(files.getOrElse(p, Nil)),
      (p, ls) => { files = files.updated(p, ls); Right(()) },
      (p, ls) => { files = files.updated(p, files.getOrElse(p, Nil) ++ ls); Right(()) })
  }
  var t = 0L
  def counting(prefix: String): () => String = { var n = 0; () => { n += 1; s"$prefix$n" } }
  /** a session opened on the disk: its first step (the main screen), and its vault as last told */
  final class Session(disk: Disk, ids: () => String) {
    var vault: Vault = Vault.empty
    private val pages = new Pages[app.Place](app.Main)
    private val wired = {
      implicit val needs: app.Needs[All] = app.Needs[All]
      store(Vault.empty, ids, () => { t += 1; 1000L + t }, v => vault = v)(consenting(Nil)(policy(Audience.Owner)(
        clipboardRecording(disk.handler(app.navs.memory(pages)(app.run[All]("v.jsonl")))))))
    }
    val first: At = settle(run(faces.steps[A, F, Void](wired)))
    /** from a step — the latest, or an earlier one, as the face's back resumes it — the choices taken, to the main
     * screen */
    def from(at: At, choices: Chosen*): At =
      choices.foldLeft(at)((w, c) => w.resume(c).fold(r => fail(s"rejected: $r"), next => settle(run(next))))
  }
  /** what is told run through, to the step that waits: the test face's way */
  def settle(step: Step[A, M]): At = step match {
    case Step.Told(_, next) => settle(run(next))
    case w: Step.Waiting[A, M] @unchecked => w
    case Step.Done(_) => fail("the dialogue ended by itself; it has no quit")
  }
  val passport = obj("country" -> Str("UA"), "number" -> Str("AB123456"), "issued" -> Str("2020-01-02"),
    "expires" -> Str("2030-01-01"), "holder" -> Str("S."))
  val adding = List(Chosen.plain("add"), Chosen.plain("passport"), Chosen("submit", passport),
    Chosen("submit", obj("country" -> Str("UA"))))
  val emailing = List(Chosen.plain("add"), Chosen.plain("email"),
    Chosen("submit", obj("address" -> Str("s@example.org"))), Chosen("submit", obj()))
  def added(lines: List[String]): List[Record] = Journal.read(lines)._2.collect { case Event.RecordAdded(r, _) => r }
  def rows(v: View): Int = v match {
    case View.Group(_, cs) => cs.collect { case View.Table(_, rs) => rs.size }.sum
    case _ => 0
  }

  test(s"$name: two sessions over one file, interleaved, each adds a record: the file has both, a reload shows both") {
    val disk = new Disk
    val a = new Session(disk, uniqueIds); val b = new Session(disk, uniqueIds)   // both open the file while it is empty
    a.from(a.first, adding: _*)
    val atB = b.from(b.first, emailing: _*)
    val records = added(disk.lines)
    assertEquals(records.map(_.kind.value).toSet, Set("passport", "email"))
    assertEquals(records.map(_.id).distinct.size, 2); assertEquals(records.map(_.version).distinct.size, 2)
    assertEquals(b.vault.records.size, 2)                // B took in what A appended, at its save
    assertEquals(rows(atB.view), 2)
    val c = new Session(disk, uniqueIds)                 // a reload: the whole file replayed
    assertEquals(rows(c.first.view), 2); assertEquals(c.vault.records.size, 2)
  }
  test(s"$name: a change, back past it, another change: the file keeps the first; the vault has it again") {
    val disk = new Disk
    val s = new Session(disk, counting("s"))
    s.from(s.first, adding: _*)
    val first = disk.lines
    assertEquals(added(first).size, 1)
    s.from(s.first, emailing: _*)                         // back to the main screen before the passport, and on
    assert(disk.lines.startsWith(first), disk.lines.mkString("\n"))
    assertEquals(added(disk.lines).map(_.kind.value), List("passport", "email"))
    assertEquals(s.vault.records.map(_.kind.value).toSet, Set("passport", "email"))
  }
  test(s"$name: a repeated save appends nothing: back to a step after the save, a record opened and closed") {
    val disk = new Disk
    val s = new Session(disk, counting("s"))
    val after = s.from(s.first, adding: _*)
    val lines = disk.lines
    s.from(after, Chosen("open", obj("n" -> Str("1"))), Chosen.plain("done"))
    s.from(after, Chosen("open", obj("n" -> Str("1"))), Chosen.plain("done"))
    assertEquals(disk.lines, lines)
  }
  test(s"$name: ids from two sessions differ") {
    val (one, two) = (uniqueIds, uniqueIds)
    val minted = List.fill(50)(one()) ++ List.fill(50)(two())
    assertEquals(minted.distinct.size, 100)
  }
  test(s"$name: an old journal, written before events had ids, reads, and is appended to, not rewritten") {
    val old = List("""{"format":"verity","version":1,"created":1000}""",
      """{"type":"record-added","record":{"id":"r1","version":"v1","kind":"email",""" +
        """"fields":{"address":"old@example.org"},"at":1000}}""")
    val disk = new Disk(Map("v.jsonl" -> old))
    val s = new Session(disk, counting("s"))
    assertEquals(rows(s.first.view), 1); assertEquals(s.vault.records.map(_.id.value), List("r1"))
    s.from(s.first, adding: _*)
    assertEquals(disk.lines.take(2), old)
    assertEquals(added(disk.lines).map(_.id.value), List("r1", "s1"))
    assertEquals(Vault.of(Journal.read(disk.lines)._2).records.size, 2)
  }
}
class DiskSuite extends munit.FunSuite {
  test("the disk appends: what is there untouched, the batch after it, a missing file made") {
    val f = java.io.File.createTempFile("verity", ".jsonl"); f.delete()
    assertEquals(Disk.append(f.getPath, List("a", "b")), Right(()))
    assertEquals(Disk.append(f.getPath, List("c")), Right(()))
    assertEquals(Disk.read(f.getPath), Right(List("a", "b", "c")))
    f.delete()
  }
}
class AppendOnMachine extends AppendChecks(Machine, "machine")
class AppendOnFree extends AppendChecks(Free, "free")
