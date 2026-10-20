package verity

import okay.effects.Effects
import okay.effects.json.Json
import okay.face.View

/** THE DOMAIN'S EFFECTS. The scenario is a program in `Face + Store + Reveal + Consent + Clipboard + Files`; each a
 * port, its handlers the adapters: in memory and pure for a test, the disk and the clipboard for the real thing */

/** the vault: what is kept, asked and changed — the handler keeps the state and writes the journal */
sealed trait Store[X]
object Store {
  final case class Kinds[X](answer: List[Kind] => X) extends Store[X]
  final case class Find[X](text: String, tag: Option[String], answer: List[Record] => X) extends Store[X]
  final case class Get[X](id: RecordId, answer: Option[Record] => X) extends Store[X]
  final case class History[X](id: RecordId, answer: List[Record] => X) extends Store[X]
  final case class Define[X](kind: Kind, answer: Unit => X) extends Store[X]
  /** a record added: the id and the version are the handler's */
  final case class Add[X](kind: KindId, fields: Json.Obj, context: Context, valid: Period, tags: Set[String], note: Option[String], answer: Record => X) extends Store[X]
  /** a new version of a record */
  final case class Revise[X](id: RecordId, fields: Json.Obj, context: Context, valid: Period, tags: Set[String], note: Option[String], answer: Option[Record] => X) extends Store[X]
  final case class Retire[X](id: RecordId, reason: Option[String], answer: Unit => X) extends Store[X]
  final case class Audit[X](id: RecordId, field: String, audience: String, answer: Unit => X) extends Store[X]
  /** the journal as it stands, and how many events are unsaved */
  final case class Journaled[X](answer: ((List[Event], Int)) => X) extends Store[X]
  final case class Saved[X](answer: Unit => X) extends Store[X]
}

/** a secret in the clear — the ONLY way to one; a policy sits over it */
sealed trait Reveal[X]
object Reveal { final case class Show[X](id: RecordId, field: String, answer: Option[String] => X) extends Reveal[X] }

/** the OWNER's yes or no — whatever face the asker has: a person at a terminal is asked; an agent's request is put to
 * the owner through the client */
sealed trait Consent[X]
object Consent { final case class Ask[X](what: View, answer: Boolean => X) extends Consent[X] }

sealed trait Clipboard[X]
object Clipboard { final case class Put[X](text: String, answer: Unit => X) extends Clipboard[X] }

/** the file: its lines, read and written whole */
sealed trait Files[X]
object Files {
  final case class Open[X](path: String, answer: Either[String, List[String]] => X) extends Files[X]
  final case class Save[X](path: String, lines: List[String], answer: Either[String, Unit] => X) extends Files[X]
}

/** who is asking: the owner at their own face, or an agent through a client */
sealed trait Audience { def name: String }
object Audience {
  case object Owner extends Audience { val name = "owner" }
  final case class Agent(client: String) extends Audience { def name = s"agent:$client" }
}

/** the words and the handlers, over any implementation */
class Domain[F <: Effects](implicit implementation: F) {
  val fx: F = implementation
  import fx._

  // ---- words
  def kinds[R[_]](implicit in: In[Store, R]): List[Kind] ! R = perform[Store].apply[R, List[Kind]](Store.Kinds(x => x))
  def find[R[_]](text: String, tag: Option[String] = None)(implicit in: In[Store, R]): List[Record] ! R = perform[Store].apply[R, List[Record]](Store.Find(text, tag, x => x))
  def get[R[_]](id: RecordId)(implicit in: In[Store, R]): Option[Record] ! R = perform[Store].apply[R, Option[Record]](Store.Get(id, x => x))
  def history[R[_]](id: RecordId)(implicit in: In[Store, R]): List[Record] ! R = perform[Store].apply[R, List[Record]](Store.History(id, x => x))
  def define[R[_]](kind: Kind)(implicit in: In[Store, R]): Unit ! R = perform[Store].apply[R, Unit](Store.Define(kind, x => x))
  def add[R[_]](kind: KindId, fields: Json.Obj, context: Context, valid: Period, tags: Set[String], note: Option[String])(implicit in: In[Store, R]): Record ! R =
    perform[Store].apply[R, Record](Store.Add(kind, fields, context, valid, tags, note, x => x))
  def revise[R[_]](id: RecordId, fields: Json.Obj, context: Context, valid: Period, tags: Set[String], note: Option[String])(implicit in: In[Store, R]): Option[Record] ! R =
    perform[Store].apply[R, Option[Record]](Store.Revise(id, fields, context, valid, tags, note, x => x))
  def retire[R[_]](id: RecordId, reason: Option[String])(implicit in: In[Store, R]): Unit ! R = perform[Store].apply[R, Unit](Store.Retire(id, reason, x => x))
  def audit[R[_]](id: RecordId, field: String, audience: String)(implicit in: In[Store, R]): Unit ! R = perform[Store].apply[R, Unit](Store.Audit(id, field, audience, x => x))
  def journaled[R[_]](implicit in: In[Store, R]): (List[Event], Int) ! R = perform[Store].apply[R, (List[Event], Int)](Store.Journaled(x => x))
  def saved[R[_]](implicit in: In[Store, R]): Unit ! R = perform[Store].apply[R, Unit](Store.Saved(x => x))
  def reveal[R[_]](id: RecordId, field: String)(implicit in: In[Reveal, R]): Option[String] ! R = perform[Reveal].apply[R, Option[String]](Reveal.Show(id, field, x => x))
  def consent[R[_]](what: View)(implicit in: In[Consent, R]): Boolean ! R = perform[Consent].apply[R, Boolean](Consent.Ask(what, x => x))
  def copy[R[_]](text: String)(implicit in: In[Clipboard, R]): Unit ! R = perform[Clipboard].apply[R, Unit](Clipboard.Put(text, x => x))
  def open[R[_]](path: String)(implicit in: In[Files, R]): Either[String, List[String]] ! R = perform[Files].apply[R, Either[String, List[String]]](Files.Open(path, x => x))
  def save[R[_]](path: String, lines: List[String])(implicit in: In[Files, R]): Either[String, Unit] ! R = perform[Files].apply[R, Either[String, Unit]](Files.Save(path, lines, x => x))

  // ---- THE STORE'S HANDLER: the vault in a cell, in place; ids and the clock given, so a test is deterministic
  def store(initial: Vault, ids: () => String, now: () => Long): StoreHandler = new StoreHandler(initial, ids, now)
  final class StoreHandler(initial: Vault, ids: () => String, now: () => Long) {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Store, R, Rest]): (Vault, A) ! Rest =
      handle[Store, Vault, R, Rest, A, (Vault, A)](p, initial, new StatefulTailHandler[Store, Vault, Rest, A, (Vault, A)] {
        def ret(v: Vault, a: A) = pure((v, a))
        def answer[X](cell: Cell[Vault], op: Store[X]): X = {
          val v = cell.get
          op match {
            case Store.Kinds(answer) => answer(v.kinds.values.toList.sortBy(_.name))
            case Store.Find(text, tag, answer) =>
              val t = text.trim.toLowerCase
              answer(v.records.filter(r => tag.forall(r.tags.contains) && (t.isEmpty || Json.print(r.fields).toLowerCase.contains(t) || r.tags.exists(_.toLowerCase.contains(t)) || r.note.exists(_.toLowerCase.contains(t)))))
            case Store.Get(id, answer) => answer(v.current(id))
            case Store.History(id, answer) => answer(v.history(id))
            case Store.Define(kind, answer) => cell.set(v(if (v.kinds.contains(kind.id)) Event.KindRevised(kind, now()) else Event.KindDefined(kind, now()))); answer(())
            case Store.Add(kind, fields, context, valid, tags, note, answer) =>
              val r = Record(RecordId(ids()), VersionId(ids()), None, kind, fields, context, valid, tags, note, now())
              cell.set(v(Event.RecordAdded(r))); answer(r)
            case Store.Revise(id, fields, context, valid, tags, note, answer) => v.current(id) match {
              case Some(old) =>
                val r = old.copy(version = VersionId(ids()), parent = Some(old.version), fields = fields, context = context, valid = valid, tags = tags, note = note, at = now())
                cell.set(v(Event.RecordRevised(r))); answer(Some(r))
              case None => answer(None)
            }
            case Store.Retire(id, reason, answer) => cell.set(v(Event.RecordRetired(id, reason, now()))); answer(())
            case Store.Audit(id, field, audience, answer) => cell.set(v(Event.Revealed(id, field, audience, now()))); answer(())
            case Store.Journaled(answer) => answer((v.events, v.unsaved))
            case Store.Saved(answer) => cell.set(v.saved); answer(())
          }
        }
      })
  }

  /** THE POLICY over `Reveal`: who is asking decides. The owner at their own face is shown. An agent is shown only with
   * the owner's consent, asked each time, and every showing is audited into the journal. The clause runs in the row
   * outside, so it performs `Store` and `Consent` there */
  def policy(audience: Audience): Policy = new Policy(audience)
  final class Policy(audience: Audience) {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Reveal, R, Rest], s: In[Store, Rest], c: In[Consent, Rest]): A ! Rest =
      handle[Reveal, R, Rest, A, A](p, new Handler[Reveal, Rest, A, A] {
        def ret(a: A) = pure(a)
        def op[X](op: Reveal[X], k: X => A ! Rest) = op match {
          case Reveal.Show(id, field, answer) =>
            get[Rest](id).flatMap {
              case None => k(answer(None))
              case Some(r) =>
                val value = r.fields.get(field).map { case Json.Str(s) => s; case other => Json.print(other) }
                val allowed: Boolean ! Rest = audience match {
                  case Audience.Owner => pure[Boolean, Rest](true)
                  case Audience.Agent(client) => consent[Rest](View.stack(View.heading("An agent asks for a secret", 2),
                    View.fields("client" -> View.text(client), "record" -> View.text(id.value), "field" -> View.text(field)), View.text("Show it?")))
                }
                allowed.flatMap(ok => if (ok && value.isDefined) audit[Rest](id, field, audience.name).flatMap(_ => k(answer(value))) else k(answer(None)))
            }
        }
      })
  }

  // ---- the owner's consent: at their own face, asked through Face; in a test, a script
  def consenting(answers: List[Boolean]): Consenting = new Consenting(answers)
  final class Consenting(answers: List[Boolean]) {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Consent, R, Rest]): A ! Rest =
      handle[Consent, List[Boolean], R, Rest, A, A](p, answers, new StatefulTailHandler[Consent, List[Boolean], Rest, A, A] {
        def ret(s: List[Boolean], a: A) = pure(a)
        def answer[X](cell: Cell[List[Boolean]], op: Consent[X]): X = op match {
          case Consent.Ask(_, answer) => cell.get match { case b :: rest => cell.set(rest); answer(b); case Nil => answer(false) }
        }
      })
  }
  /** the owner asked at their face: `Consent` becomes a `confirm` of `Face` */
  def askingTheOwner(faces: okay.face.Faces[fx.type]): AskingTheOwner = new AskingTheOwner(faces)
  final class AskingTheOwner(faces: okay.face.Faces[fx.type]) {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Consent, R, Rest], f: In[okay.face.Face, Rest]): A ! Rest =
      handle[Consent, R, Rest, A, A](p, new Handler[Consent, Rest, A, A] {
        def ret(a: A) = pure(a)
        def op[X](op: Consent[X], k: X => A ! Rest) = op match {
          case Consent.Ask(what, answer) => faces.confirm[Rest](what).flatMap(b => k(answer(b)))
        }
      })
  }

  // ---- the clipboard: recorded, or the system's
  def clipboardRecording: ClipboardRecording = new ClipboardRecording
  final class ClipboardRecording {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Clipboard, R, Rest]): (List[String], A) ! Rest =
      handle[Clipboard, List[String], R, Rest, A, (List[String], A)](p, Nil, new StatefulTailHandler[Clipboard, List[String], Rest, A, (List[String], A)] {
        def ret(s: List[String], a: A) = pure((s.reverse, a))
        def answer[X](cell: Cell[List[String]], op: Clipboard[X]): X = op match { case Clipboard.Put(t, answer) => cell.set(t :: cell.get); answer(()) }
      })
  }
  def clipboardSystem: ClipboardSystem = new ClipboardSystem
  final class ClipboardSystem {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Clipboard, R, Rest]): A ! Rest =
      handle[Clipboard, R, Rest, A, A](p, new TailHandler[Clipboard, Rest, A, A] {
        def ret(a: A) = pure(a)
        def answer[X](op: Clipboard[X]): X = op match { case Clipboard.Put(t, answer) => SystemClipboard.put(t); answer(()) }
      })
  }

  // ---- files: in memory, or on disk
  def filesInMemory(initial: Map[String, List[String]]): FilesInMemory = new FilesInMemory(initial)
  final class FilesInMemory(initial: Map[String, List[String]]) {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Files, R, Rest]): (Map[String, List[String]], A) ! Rest =
      handle[Files, Map[String, List[String]], R, Rest, A, (Map[String, List[String]], A)](p, initial,
        new StatefulTailHandler[Files, Map[String, List[String]], Rest, A, (Map[String, List[String]], A)] {
          def ret(s: Map[String, List[String]], a: A) = pure((s, a))
          def answer[X](cell: Cell[Map[String, List[String]]], op: Files[X]): X = op match {
            case Files.Open(path, answer) => answer(Right(cell.get.getOrElse(path, Nil)))
            case Files.Save(path, lines, answer) => cell.set(cell.get.updated(path, lines)); answer(Right(()))
          }
        })
  }
  def filesOnDisk: FilesOnDisk = new FilesOnDisk
  final class FilesOnDisk {
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Files, R, Rest]): A ! Rest =
      handle[Files, R, Rest, A, A](p, new TailHandler[Files, Rest, A, A] {
        def ret(a: A) = pure(a)
        def answer[X](op: Files[X]): X = op match {
          case Files.Open(path, answer) => answer(Disk.read(path))
          case Files.Save(path, lines, answer) => answer(Disk.write(path, lines))
        }
      })
  }
}

object Domain {
  def apply()(implicit fx: Effects): Domain[fx.type] = {
    implicit val exact: fx.type = fx
    new Domain[fx.type]
  }
}

/** the JVM's clipboard, where there is one; printed where there is not (headless) */
object SystemClipboard {
  def put(text: String): Unit =
    try java.awt.Toolkit.getDefaultToolkit.getSystemClipboard.setContents(new java.awt.datatransfer.StringSelection(text), null)
    catch { case _: Throwable => () }
}

object Disk {
  import java.nio.file.{Files => NioFiles, Paths, StandardOpenOption}
  import scala.jdk.CollectionConverters._
  def read(path: String): Either[String, List[String]] = {
    val p = Paths.get(path)
    if (!NioFiles.exists(p)) Right(Nil)
    else try Right(NioFiles.readAllLines(p).asScala.toList) catch { case e: Exception => Left(e.getMessage) }
  }
  def write(path: String, lines: List[String]): Either[String, Unit] = try {
    val p = Paths.get(path); Option(p.getParent).foreach(d => NioFiles.createDirectories(d))
    NioFiles.write(p, lines.asJava, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE); Right(())
  } catch { case e: Exception => Left(e.getMessage) }
}
