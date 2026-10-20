package verity

import okay.effects.json.Json
import okay.effects.shape.{Day, Schema, Shape}
import okay.effects.shape.Shape.{any, bool, date, field, long, record, text}

/** A KIND OF RECORD: its fields as a `Schema` — a built-in kind's written in code, a user-defined one's read from the
 * file — which fields are secret, which name a record in a list, whether a record has a language or country context */
final case class KindId(value: String)
final case class Kind(id: KindId, name: String, schema: Schema, secret: Set[String], summary: List[String], contextual: Boolean) {
  /** the record's fields as a shape: the form, the validation */
  def shape: Shape[Json] = Shape.Loose(schema)
  /** a form for revising: the same shape, each field's default the current value */
  def shapeWith(current: Json.Obj): Shape[Json] = Shape.Loose(Kind.withDefaults(schema, current))
}
object Kind {
  def withDefaults(s: Schema, current: Json.Obj): Schema = s match {
    case Schema.Record(fields) => Schema.Record(fields.map(f => f.copy(default = current.get(f.name).orElse(f.default))))
    case Schema.Described(of, l, d) => Schema.Described(withDefaults(of, current), l, d)
    case other => other
  }
  /** the kind as a value, for the journal */
  val shape: Shape[Kind] = record(
    field("id", text.map(KindId(_))(_.value)), field("name", text), field("schema", Schema.shape),
    field("secret", text.many()).withDefault(Nil), field("summary", text.many()).withDefault(Nil), field("contextual", bool).withDefault(false))(
    (id, name, schema, secret, summary, contextual) => Kind(id, name, schema, secret.toSet, summary, contextual))(
    k => (k.id, k.name, k.schema, k.secret.toList.sorted, k.summary, k.contextual))
}

/** the built-in kinds: predefined schemas, nothing a user-defined kind could not say */
object Kinds {
  private def f(name: String, schema: Schema, label: String, doc: Option[String] = None, secret: Boolean = false, optional: Boolean = false): Schema.Field =
    Schema.Field(name, if (optional) Schema.Optional(schema) else schema, Some(label), doc, secret, None)
  private val text = Schema.Text(Nil)
  private def nonEmpty = Schema.Text(List(okay.effects.shape.Constraint.MinLength(1)))

  val personName: Kind = Kind(KindId("person-name"), "Name",
    Schema.Record(Vector(f("given", nonEmpty, "Given name"), f("family", nonEmpty, "Family name"), f("middle", text, "Middle name", optional = true),
      f("language", nonEmpty, "Language", Some("the language this spelling is in: en, uk, de…")))),
    Set.empty, List("given", "family"), contextual = true)
  val address: Kind = Kind(KindId("address"), "Address",
    Schema.Record(Vector(f("lines", Schema.Many(nonEmpty, 1, None), "Lines", Some("street and number, one per line")), f("city", nonEmpty, "City"),
      f("region", text, "Region", optional = true), f("postal", nonEmpty, "Postal code"), f("country", nonEmpty, "Country"))),
    Set.empty, List("city", "country"), contextual = true)
  val email: Kind = Kind(KindId("email"), "Email",
    Schema.Record(Vector(f("address", Schema.Text(List(okay.effects.shape.Constraint.Pattern(".+@.+", "an email address"))), "Address"), f("purpose", text, "Purpose", optional = true))),
    Set.empty, List("address"), contextual = false)
  val phone: Kind = Kind(KindId("phone"), "Phone",
    Schema.Record(Vector(f("number", Schema.Text(List(okay.effects.shape.Constraint.Pattern("\\+?[0-9 ()-]{5,}", "a phone number"))), "Number"), f("purpose", text, "Purpose", optional = true))),
    Set.empty, List("number"), contextual = false)
  val passport: Kind = Kind(KindId("passport"), "Passport",
    Schema.Record(Vector(f("country", nonEmpty, "Country"), f("number", nonEmpty, "Number", secret = true), f("issued", Schema.Date, "Issued"),
      f("expires", Schema.Date, "Expires"), f("authority", text, "Issuing authority", optional = true), f("holder", nonEmpty, "Name as printed"))),
    Set("number"), List("country", "number"), contextual = true)
  val identifier: Kind = Kind(KindId("identifier"), "Identifier",
    Schema.Record(Vector(f("type", nonEmpty, "Type", Some("tax id, SSN, driver licence…")), f("value", nonEmpty, "Value", secret = true), f("issuer", text, "Issuer", optional = true),
      f("issued", Schema.Date, "Issued", optional = true), f("expires", Schema.Date, "Expires", optional = true))),
    Set("value"), List("type", "value"), contextual = true)

  val builtIn: List[Kind] = List(personName, address, email, phone, passport, identifier)
}

final case class RecordId(value: String)
final case class VersionId(value: String)
final case class Context(language: Option[String], country: Option[String])
final case class Period(from: Option[Day], to: Option[Day])
/** A VERSIONED FACT: a change is a new version with `parent` the old; the versions of one `RecordId` form a chain */
final case class Record(id: RecordId, version: VersionId, parent: Option[VersionId], kind: KindId, fields: Json.Obj, context: Context,
                        valid: Period, tags: Set[String], note: Option[String], at: Long)
object Record {
  val context: Shape[Context] = record(field("language", text.optional), field("country", text.optional))(Context(_, _))(c => (c.language, c.country))
  val period: Shape[Period] = record(field("from", date.optional), field("to", date.optional))(Period(_, _))(p => (p.from, p.to))
  val fields: Shape[Json.Obj] = any.refine[Json.Obj] { case o: Json.Obj => Right(o); case other => Left(s"expected a record, got ${Json.kind(other)}") }(o => o)
  val shape: Shape[Record] = record(
    field("id", text.map(RecordId(_))(_.value)), field("version", text.map(VersionId(_))(_.value)), field("parent", text.map(VersionId(_))(_.value).optional),
    field("kind", text.map(KindId(_))(_.value)), field("fields", fields), field("context", context).withDefault(Context(None, None)),
    field("valid", period).withDefault(Period(None, None)), field("tags", text.many()).withDefault(Nil), field("note", text.optional), field("at", long))(
    (id, version, parent, kind, fields, context, valid, tags, note, at) => Record(id, version, parent, kind, fields, context, valid, tags.toSet, note, at))(
    r => (r.id, r.version, r.parent, r.kind, r.fields, r.context, r.valid, r.tags.toList.sorted, r.note, r.at))
}
