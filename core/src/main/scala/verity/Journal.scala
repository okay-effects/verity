package verity

import okay.effects.json.Json
import okay.effects.shape.{Problem, Shape}
import okay.effects.shape.Shape.{field, int, long, record, sub, sum, text}

/** WHAT HAPPENED, in order: the file is this, a line a JSON object per event, appended and never rewritten. The audit
 * of reveals is part of it: who was shown what, when */
sealed trait Event
object Event {
  final case class KindDefined(kind: Kind, at: Long) extends Event
  final case class KindRevised(kind: Kind, at: Long) extends Event
  final case class RecordAdded(record: Record) extends Event
  final case class RecordRevised(record: Record) extends Event
  final case class RecordRetired(id: RecordId, reason: Option[String], at: Long) extends Event
  final case class Revealed(id: RecordId, field: String, audience: String, at: Long) extends Event

  val shape: Shape[Event] = sum(
    sub[Event, KindDefined]("kind-defined", record(field("kind", Kind.shape), field("at", long))(KindDefined(_, _))(e => (e.kind, e.at))) { case e: KindDefined => Some(e); case _ => None },
    sub[Event, KindRevised]("kind-revised", record(field("kind", Kind.shape), field("at", long))(KindRevised(_, _))(e => (e.kind, e.at))) { case e: KindRevised => Some(e); case _ => None },
    sub[Event, RecordAdded]("record-added", record(field("record", Record.shape))(RecordAdded(_))(_.record)) { case e: RecordAdded => Some(e); case _ => None },
    sub[Event, RecordRevised]("record-revised", record(field("record", Record.shape))(RecordRevised(_))(_.record)) { case e: RecordRevised => Some(e); case _ => None },
    sub[Event, RecordRetired]("record-retired", record(field("id", text.map(RecordId(_))(_.value)), field("reason", text.optional), field("at", long))(
      RecordRetired(_, _, _))(e => (e.id, e.reason, e.at))) { case e: RecordRetired => Some(e); case _ => None },
    sub[Event, Revealed]("revealed", record(field("id", text.map(RecordId(_))(_.value)), field("field", text), field("audience", text), field("at", long))(
      Revealed(_, _, _, _))(e => (e.id, e.field, e.audience, e.at))) { case e: Revealed => Some(e); case _ => None })
}

/** THE FILE: a header line, then an event a line. Plain in v1; the header is made for an encryption wrapper later */
object Journal {
  final case class Header(verity: Int, created: Long)
  val header: Shape[Header] = record(field("verity", int), field("created", long))(Header(_, _))(h => (h.verity, h.created))
  final case class Damaged(line: Int, problems: List[Problem])

  /** the lines of a file: the header checked, every event decoded, a damaged line reported by number and skipped */
  def read(lines: List[String]): (Option[Header], List[Event], List[Damaged]) = {
    val numbered = lines.zipWithIndex.filter(_._1.trim.nonEmpty)
    numbered match {
      case Nil => (None, Nil, Nil)
      case (first, _) :: rest =>
        val h = okay.effects.json.parse(first).left.map(e => List(Problem(Nil, e.message))).flatMap(header.decode)
        val events = rest.map { case (l, i) =>
          okay.effects.json.parse(l).left.map(e => List(Problem(Nil, e.message))).flatMap(Event.shape.decode).left.map(Damaged(i + 1, _))
        }
        (h.toOption, events.collect { case Right(e) => e }, (h.left.toOption.map(Damaged(1, _)).toList ++ events.collect { case Left(d) => d }))
    }
  }
  def write(h: Header, events: List[Event]): List[String] = Json.print(header.encode(h)) :: events.map(e => Json.print(Event.shape.encode(e)))
}

/** THE STATE: the journal folded — the kinds, every record's versions newest first, the retired, what is unsaved */
final case class Vault(kinds: Map[KindId, Kind], versions: Map[RecordId, List[Record]], retired: Set[RecordId], events: List[Event], unsaved: Int) {
  def kind(id: KindId): Option[Kind] = kinds.get(id)
  def current(id: RecordId): Option[Record] = if (retired(id)) None else versions.get(id).flatMap(_.headOption)
  /** the records alive, newest first */
  def records: List[Record] = versions.toList.collect { case (id, v :: _) if !retired(id) => v }.sortBy(-_.at)
  def history(id: RecordId): List[Record] = versions.getOrElse(id, Nil)
  def apply(e: Event): Vault = {
    val next = e match {
      case Event.KindDefined(k, _) => copy(kinds = kinds.updated(k.id, k))
      case Event.KindRevised(k, _) => copy(kinds = kinds.updated(k.id, k))
      case Event.RecordAdded(r) => copy(versions = versions.updated(r.id, List(r)))
      case Event.RecordRevised(r) => copy(versions = versions.updated(r.id, r :: versions.getOrElse(r.id, Nil)))
      case Event.RecordRetired(id, _, _) => copy(retired = retired + id)
      case Event.Revealed(_, _, _, _) => this
    }
    next.copy(events = events :+ e, unsaved = unsaved + 1)
  }
  def saved: Vault = copy(unsaved = 0)
}
object Vault {
  val empty: Vault = Vault(Kinds.builtIn.map(k => k.id -> k).toMap, Map.empty, Set.empty, Nil, 0)
  def of(events: List[Event]): Vault = events.foldLeft(empty)(_ apply _).saved
}
