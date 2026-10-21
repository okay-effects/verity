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

/** VERITY'S OWN WORDS on its screens — what a part MEANS beyond what it is. A face dresses them by `Marking.look`;
 * an agent is told them by `Marking.marks.vocabulary`; a test reads them back as the type */
sealed trait Mark
object Mark {
  /** the kind of a record, as a card's heading */
  case object Kind extends Mark
  /** a secret field of a record, masked */
  case object Secret extends Mark
  /** a secret shown in the clear, by the owner's consent */
  case object Revealed extends Mark
  /** the period a record holds */
  case object Valid extends Mark
  /** a validity that ends within thirty days */
  case object Expiring extends Mark
  /** a validity that is over */
  case object Expired extends Mark
}
object Marking {
  import okay.face.{Look, Marks, Rule, Style, Theme, Tone}
  val marks: Marks[Mark] = Marks.of(Shape.derived[Mark]).explained(
    Mark.Kind -> "the kind of the record: Passport, Address, Name…",
    Mark.Secret -> "a secret field of the record, masked; shown only with the owner's consent",
    Mark.Revealed -> "a secret shown in the clear with the owner's consent; the reveal is written down",
    Mark.Valid -> "the period the record holds, from – to",
    Mark.Expiring -> "the record's validity ends within thirty days",
    Mark.Expired -> "the record's validity is over")
  /** the look: the library's style, and a rule per mark */
  val style: Style = Style.default
    .set(marks.role(Mark.Kind), Rule(color = Some(Tone.Muted)))
    .set(marks.role(Mark.Secret), Rule(mono = true))
    .set(marks.role(Mark.Revealed), Rule(bold = true, mono = true, background = Some(Tone.Surface)))
    .set(marks.role(Mark.Valid), Rule(color = Some(Tone.Muted)))
    .set(marks.role(Mark.Expiring), Rule(color = Some(Tone.Warning), bold = true))
    .set(marks.role(Mark.Expired), Rule(color = Some(Tone.Problem), bold = true))
  val look: Look = Look(Theme.light, style)
  val dark: Look = Look(Theme.dark, style)

  /** days since 1970-01-01, for the thirty days before an end — our own, as `Day` is, so the browser needs no java.time */
  def ordinal(d: Day): Long = {
    val y = if (d.month <= 2) d.year - 1 else d.year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (d.month > 2) d.month - 3 else d.month + 9) + 2) / 5 + d.day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    era * 146097L + doe - 719468L
  }
}
