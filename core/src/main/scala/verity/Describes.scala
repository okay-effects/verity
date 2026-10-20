package verity

import okay.effects.shape.Shape
import okay.effects.shape.Shape.{field, int, record, text, unit}
import okay.face.{Call, Describe, Op}

/** THE STORE DESCRIBED: every operation with its parameters' and result's shapes, so the store may be served — as REST
 * resources, as tools — and performed from afar: a program performs `Store` here and it happens on the server. The
 * shapes are the model's own (`Record.shape`, `Kind.shape`, `Event.shape`) */
object Describes {
  val recordId: Shape[RecordId] = text.map(RecordId(_))(_.value)
  val kindId: Shape[KindId] = text.map(KindId(_))(_.value)
  private val byId = record(field("id", recordId))(id => id)(id => id)
  private val tags: Shape[Set[String]] = text.many().map(_.toSet)(_.toList.sorted)
  private val written = record(field("kind", kindId), field("fields", Record.fields), field("context", Record.context), field("valid", Record.period), field("tags", tags), field("note", text.optional))(
    (k, f, c, v, t, n) => (k, f, c, v, t, n))(x => x)
  private val revised = record(field("id", recordId), field("fields", Record.fields), field("context", Record.context), field("valid", Record.period), field("tags", tags), field("note", text.optional))(
    (i, f, c, v, t, n) => (i, f, c, v, t, n))(x => x)

  val kinds: Op[Store, Unit, List[Kind]] = Op("kinds", unit, Kind.shape.many(), (_: Unit) => Store.Kinds(x => x), Some("the kinds of record"))
  val find: Op[Store, (String, Option[String]), List[Record]] =
    Op("find", record(field("text", text), field("tag", text.optional))((t, g) => (t, g))(x => x), Record.shape.many(), (a: (String, Option[String])) => Store.Find(a._1, a._2, x => x), Some("records by text in the fields, by tag"))
  val get: Op[Store, RecordId, Option[Record]] = Op("get", byId, Record.shape.optional, (id: RecordId) => Store.Get(id, x => x), Some("a record as it stands"))
  val history: Op[Store, RecordId, List[Record]] = Op("history", byId, Record.shape.many(), (id: RecordId) => Store.History(id, x => x), Some("a record's versions, newest first"))
  val define: Op[Store, Kind, Unit] = Op("define", Kind.shape, unit, (k: Kind) => Store.Define(k, x => x), Some("a kind defined or revised"), writes = true)
  val add: Op[Store, (KindId, okay.effects.json.Json.Obj, Context, Period, Set[String], Option[String]), Record] =
    Op("add", written, Record.shape, (a: (KindId, okay.effects.json.Json.Obj, Context, Period, Set[String], Option[String])) => Store.Add(a._1, a._2, a._3, a._4, a._5, a._6, x => x), Some("a record added"), writes = true)
  val revise: Op[Store, (RecordId, okay.effects.json.Json.Obj, Context, Period, Set[String], Option[String]), Option[Record]] =
    Op("revise", revised, Record.shape.optional, (a: (RecordId, okay.effects.json.Json.Obj, Context, Period, Set[String], Option[String])) => Store.Revise(a._1, a._2, a._3, a._4, a._5, a._6, x => x), Some("a new version"), writes = true)
  val retire: Op[Store, (RecordId, Option[String]), Unit] =
    Op("retire", record(field("id", recordId), field("reason", text.optional))((i, r) => (i, r))(x => x), unit, (a: (RecordId, Option[String])) => Store.Retire(a._1, a._2, x => x), Some("a record retired"), writes = true)
  val audit: Op[Store, (RecordId, String, String), Unit] =
    Op("audit", record(field("id", recordId), field("field", text), field("audience", text))((i, f, a) => (i, f, a))(x => x), unit, (a: (RecordId, String, String)) => Store.Audit(a._1, a._2, a._3, x => x), Some("a reveal written down"), writes = true)
  val journaled: Op[Store, Unit, (List[Event], Int)] =
    Op("journaled", unit, record(field("events", Event.shape.many()), field("unsaved", int))((e, n) => (e, n))(x => x), (_: Unit) => Store.Journaled(x => x), Some("the journal, and how many events are unsaved"))
  val saved: Op[Store, Unit, Unit] = Op("saved", unit, unit, (_: Unit) => Store.Saved(x => x), Some("the journal marked saved"), writes = true)

  val store: Describe[Store] = new Describe[Store]("store") {
    val operations = List(kinds, find, get, history, define, add, revise, retire, audit, journaled, saved)
    def split[X](op: Store[X]): Call[X] = op match {
      case Store.Kinds(k) => call(kinds, (), k)
      case Store.Find(t, g, k) => call(find, (t, g), k)
      case Store.Get(id, k) => call(get, id, k)
      case Store.History(id, k) => call(history, id, k)
      case Store.Define(kind, k) => call(define, kind, k)
      case Store.Add(kind, f, c, v, t, n, k) => call(add, (kind, f, c, v, t, n), k)
      case Store.Revise(id, f, c, v, t, n, k) => call(revise, (id, f, c, v, t, n), k)
      case Store.Retire(id, r, k) => call(retire, (id, r), k)
      case Store.Audit(id, f, a, k) => call(audit, (id, f, a), k)
      case Store.Journaled(k) => call(journaled, (), k)
      case Store.Saved(k) => call(saved, (), k)
    }
  }
}
