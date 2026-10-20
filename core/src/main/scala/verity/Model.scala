package verity

import okay.effects.json.Json
import okay.effects.shape.{Day, Schema, Shape}
import okay.effects.shape.Shape.{date, field, record, text}

/** the built-in kinds: predefined schemas, nothing a user-defined kind could not say */
object Kinds {
  private def f(name: String, schema: Schema, label: String, doc: Option[String] = None, secret: Boolean = false, optional: Boolean = false): Schema.Field =
    Schema.Field(name, if (optional) Schema.Optional(schema) else schema, Some(label), doc, secret, None)
  private val text = Schema.Text(Nil)
  private def nonEmpty = Schema.Text(List(okay.effects.shape.Constraint.MinLength(1)))

  /** a kind of verity's: `contextual` says a record has a language or country */
  def kind(id: String, name: String, schema: Schema, secret: Set[String], summary: List[String], contextual: Boolean): Kind =
    Kind(KindId(id), name, schema, secret, summary, Json.obj("contextual" -> Json.Bool(contextual)))
  def contextual(k: Kind): Boolean = k.meta.get("contextual").contains(Json.Bool(true))

  val personName: Kind = kind("person-name", "Name",
    Schema.Record(Vector(f("given", nonEmpty, "Given name"), f("family", nonEmpty, "Family name"), f("middle", text, "Middle name", optional = true),
      f("language", nonEmpty, "Language", Some("the language this spelling is in: en, uk, de…")))),
    Set.empty, List("given", "family"), contextual = true)
  val address: Kind = kind("address", "Address",
    Schema.Record(Vector(f("lines", Schema.Many(nonEmpty, 1, None), "Lines", Some("street and number, one per line")), f("city", nonEmpty, "City"),
      f("region", text, "Region", optional = true), f("postal", nonEmpty, "Postal code"), f("country", nonEmpty, "Country"))),
    Set.empty, List("city", "country"), contextual = true)
  val email: Kind = kind("email", "Email",
    Schema.Record(Vector(f("address", Schema.Text(List(okay.effects.shape.Constraint.Pattern(".+@.+", "an email address"))), "Address"), f("purpose", text, "Purpose", optional = true))),
    Set.empty, List("address"), contextual = false)
  val phone: Kind = kind("phone", "Phone",
    Schema.Record(Vector(f("number", Schema.Text(List(okay.effects.shape.Constraint.Pattern("\\+?[0-9 ()-]{5,}", "a phone number"))), "Number"), f("purpose", text, "Purpose", optional = true))),
    Set.empty, List("number"), contextual = false)
  val passport: Kind = kind("passport", "Passport",
    Schema.Record(Vector(f("country", nonEmpty, "Country"), f("number", nonEmpty, "Number", secret = true), f("issued", Schema.Date, "Issued"),
      f("expires", Schema.Date, "Expires"), f("authority", text, "Issuing authority", optional = true), f("holder", nonEmpty, "Name as printed"))),
    Set("number"), List("country", "number"), contextual = true)
  val identifier: Kind = kind("identifier", "Identifier",
    Schema.Record(Vector(f("type", nonEmpty, "Type", Some("tax id, SSN, driver licence…")), f("value", nonEmpty, "Value", secret = true), f("issuer", text, "Issuer", optional = true),
      f("issued", Schema.Date, "Issued", optional = true), f("expires", Schema.Date, "Expires", optional = true))),
    Set("value"), List("type", "value"), contextual = true)

  val builtIn: List[Kind] = List(personName, address, email, phone, passport, identifier)
}


final case class Context(language: Option[String], country: Option[String])
final case class Period(from: Option[Day], to: Option[Day])
/** WHAT A RECORD CARRIES BESIDE ITS FIELDS, in okay-store's `meta`: where it holds (a language, a country) and when */
object Extra {
  val context: Shape[Context] = record(field("language", text.optional), field("country", text.optional))(Context(_, _))(c => (c.language, c.country))
  val period: Shape[Period] = record(field("from", date.optional), field("to", date.optional))(Period(_, _))(p => (p.from, p.to))
  def of(c: Context, p: Period): Json.Obj = Json.obj("context" -> context.encode(c), "valid" -> period.encode(p))
  def context(r: Record): Context = r.meta.get("context").flatMap(context.decode(_).toOption).getOrElse(Context(None, None))
  def valid(r: Record): Period = r.meta.get("valid").flatMap(period.decode(_).toOption).getOrElse(Period(None, None))
}

/** what is kept: okay-store's fold, with verity's built-in kinds */
object Vault {
  val empty: Vault = okay.store.Kept.empty(Kinds.builtIn)
  def of(events: List[Event]): Vault = okay.store.Kept.of(events, Kinds.builtIn)
}
