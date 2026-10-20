package verity

import okay.effects.Effects
import okay.effects.json.Json
import okay.effects.shape.{Schema, Shape}
import okay.effects.shape.Shape.{bool, choice, date, field, record, text}
import okay.face.{Action, Face, Faces, Navigation, Navigations, View}

/**
 * THE SCENARIO: a dialogue in `Face + Store + Reveal + Consent + Clipboard + Files + Navigation[Place]`. The main screen
 * lists the records; a record is opened, its fields shown with the secrets masked, copied, revealed, revised, retired,
 * its history read; a record is added by choosing a kind and filling the kind's form; a kind of one's own is defined;
 * the file is saved. No line of it knows a face: the terminal, the window, the test script and, later, an agent run it.
 */
class Verity[F <: Effects](val faces: Faces[F]) {
  import faces.fx._
  import faces.{choose, confirm, fill, fillOrCancel, offer, show}
  /** the domain and the navigation over the SAME implementation value, so one `In` serves all three */
  val domain: Domain[faces.fx.type] = new Domain[faces.fx.type]()(faces.fx)
  val navs: Navigations[faces.fx.type] = new Navigations[faces.fx.type]()(faces.fx)
  import domain._
  import navs.{push, replace}

  /** where the program is, for a face that shows the place: a window's title, a terminal's line, a browser's URL */
  sealed trait Place
  case object Main extends Place
  final case class Opened(id: RecordId) extends Place
  case object Adding extends Place
  case object Defining extends Place
  final case class Versions(id: RecordId) extends Place

  type Nav[X] = Navigation[Place, X]
  /** the row a scenario needs: the six effects in it, witnessed once */
  trait Needs[R[_]] {
    implicit def face: In[Face, R]; implicit def store: In[Store, R]; implicit def revealing: In[Reveal, R]
    implicit def clipboard: In[Clipboard, R]; implicit def files: In[Files, R]; implicit def nav: In[Nav, R]
  }
  object Needs {
    def apply[R[_]](implicit f: In[Face, R], s: In[Store, R], rv: In[Reveal, R], cb: In[Clipboard, R], fl: In[Files, R], nv: In[Nav, R]): Needs[R] = new Needs[R] {
      def face = f; def store = s; def revealing = rv; def clipboard = cb; def files = fl; def nav = nv
    }
  }

  // ---- what is shown
  def summary(k: Kind, r: Record): String =
    k.summary.flatMap(f => r.fields.get(f).map(v => if (k.secret(f)) masked(v) else plain(v))).mkString(", ")
  def plain(v: Json): String = v match { case Json.Str(s) => s; case Json.Null => ""; case Json.Arr(items) => items.map(plain).mkString("; "); case other => Json.print(other) }
  def masked(v: Json): String = { val s = plain(v); if (s.length <= 4) "••••" else "•••• " + s.takeRight(4) }
  def period(p: Period): String = (p.from, p.to) match { case (None, None) => ""; case (f, t) => f.fold("")(_.toString) + " – " + t.fold("")(_.toString) }
  def context(c: Context): String = (c.language.toList ++ c.country.toList).mkString(" ")

  def listing(kinds: Map[KindId, Kind], rs: List[Record]): View =
    if (rs.isEmpty) View.stack(View.heading("Verity"), View.text("Nothing kept yet. Add a record."))
    else View.stack(View.heading("Verity"), View.Table(Vector("#", "kind", "summary", "context", "valid", "tags"),
      rs.zipWithIndex.map { case (r, i) => Vector(View.text((i + 1).toString), View.text(kinds.get(r.kind).fold(r.kind.value)(_.name)),
        View.text(kinds.get(r.kind).fold("")(summary(_, r))), View.text(context(r.context)), View.text(period(r.valid)), View.text(r.tags.toList.sorted.mkString(" "))) }.toVector))
  def card(k: Kind, r: Record): View = View.stack(View.heading(k.name, 2),
    View.Fields(fieldsOf(k).map(f => f.label.getOrElse(f.name) -> r.fields.get(f.name).fold[View](View.Empty)(v => if (k.secret(f.name)) View.Masked(masked(v)) else View.text(plain(v))))),
    View.fields("context" -> View.text(context(r.context)), "valid" -> View.text(period(r.valid)), "tags" -> View.text(r.tags.toList.sorted.mkString(" ")),
      "note" -> View.text(r.note.getOrElse("")), "version" -> View.text(r.version.value)))
  def fieldsOf(k: Kind): Vector[Schema.Field] = k.schema match { case Schema.Record(fs) => fs; case Schema.Described(Schema.Record(fs), _, _) => fs; case _ => Vector.empty }

  // ---- the forms
  final case class Meta(language: Option[String], country: Option[String], from: Option[okay.effects.shape.Day], to: Option[okay.effects.shape.Day], tags: Option[String], note: Option[String]) {
    def ctx: Context = Context(language, country)
    def valid: Period = Period(from, to)
    def tagSet: Set[String] = tags.toList.flatMap(_.split("[,\\s]+")).map(_.trim).filter(_.nonEmpty).toSet
  }
  def meta(current: Option[Record]): Shape[Meta] = record(
    field("language", text.optional).labelled("Language").documented("en, uk, de…"), field("country", text.optional).labelled("Country"),
    field("from", date.optional).labelled("Valid from"), field("to", date.optional).labelled("Valid to"),
    field("tags", text.optional).labelled("Tags").documented("separated by spaces or commas"), field("note", text.optional).labelled("Note"))(
    Meta(_, _, _, _, _, _))(m => (m.language, m.country, m.from, m.to, m.tags, m.note)) match {
    case shape => current.fold(shape)(r => Shape.Loose(Kind.withDefaults(shape.erased, Json.Obj(Vector(
      "language" -> r.context.language.fold[Json](Json.Null)(Json.Str(_)), "country" -> r.context.country.fold[Json](Json.Null)(Json.Str(_)),
      "from" -> r.valid.from.fold[Json](Json.Null)(d => Json.Str(d.toString)), "to" -> r.valid.to.fold[Json](Json.Null)(d => Json.Str(d.toString)),
      "tags" -> Json.Str(r.tags.toList.sorted.mkString(" ")), "note" -> r.note.fold[Json](Json.Null)(Json.Str(_))).filter(_._2 != Json.Null))))
      .refine[Meta](j => shape.decode(j).left.map(_.map(_.render).mkString("; ")))(m => shape.encode(m)))
  }

  /** a kind of one's own: a name and its fields */
  final case class FieldSpec(name: String, label: String, kind: String, secret: Boolean, optional: Boolean)
  final case class KindSpec(name: String, fields: List[FieldSpec], contextual: Boolean)
  val kindForm: Shape[KindSpec] = record(
    field("name", text(okay.effects.shape.Constraint.MinLength(1))).labelled("Name of the kind"),
    field("fields", record(
      field("name", text(okay.effects.shape.Constraint.Pattern("[a-z][a-z0-9_-]*", "a name of letters, digits, - and _"))).labelled("Field name"),
      field("label", text).labelled("Label"),
      field("kind", choice[String]("text" -> "text", "number" -> "number", "date" -> "date", "yes/no" -> "bool")).labelled("Type"),
      field("secret", bool).withDefault(false).labelled("Secret"), field("optional", bool).withDefault(false).labelled("Optional"))(
      FieldSpec(_, _, _, _, _))(f => (f.name, f.label, f.kind, f.secret, f.optional)).many(min = 1)).labelled("Fields"),
    field("contextual", bool).withDefault(false).labelled("Has a language or country"))(KindSpec(_, _, _))(k => (k.name, k.fields, k.contextual))
  def kindOf(spec: KindSpec): Kind = {
    val fields = spec.fields.map { f =>
      val base: Schema = f.kind match { case "number" => Schema.Number(Nil); case "date" => Schema.Date; case "bool" => Schema.Bool; case _ => Schema.Text(Nil) }
      Schema.Field(f.name, if (f.optional) Schema.Optional(base) else base, Some(f.label).filter(_.nonEmpty), None, f.secret, None)
    }
    Kind(KindId(Action.nameOf(spec.name)), spec.name, Schema.Record(fields.toVector), spec.fields.filter(_.secret).map(_.name).toSet, spec.fields.take(2).map(_.name), spec.contextual)
  }

  // ---- THE SCREENS
  sealed trait MainChoice
  final case class FindIt(text: String, tag: Option[String]) extends MainChoice
  final case class OpenIt(n: Int) extends MainChoice
  case object AddIt extends MainChoice
  case object DefineIt extends MainChoice
  case object SaveIt extends MainChoice
  case object QuitIt extends MainChoice

  def main[R[_]](path: String, query: (String, Option[String]) = ("", None))(implicit n: Needs[R]): Unit ! R = {
    import n._
    replace[Place, R](Main).flatMap(_ => kinds[R]).flatMap(ks => find[R](query._1, query._2).flatMap { rs =>
      val byKind = ks.map(k => k.id -> k).toMap
      val openIt = if (rs.isEmpty) Nil else List(Action[Int, MainChoice]("open", "Open", record(field("n", choice[Int](rs.indices.map(i => (i + 1).toString -> i): _*).described("Record", Some("its number in the list"))))(i => i)(i => i))(OpenIt(_)))
      offer[MainChoice, R](listing(byKind, rs), openIt ++ List(
        Action.plain[MainChoice]("add", "Add a record")(AddIt),
        Action[FindIt, MainChoice]("find", "Find", record(field("text", text).withDefault("").labelled("Text"), field("tag", text.optional).labelled("Tag"))(FindIt(_, _))(q => (q.text, q.tag)), Some("by text in the fields, by tag"))(q => q),
        Action.plain[MainChoice]("define", "Define a kind")(DefineIt),
        Action.plain[MainChoice]("save", "Save")(SaveIt),
        Action.plain[MainChoice]("quit", "Quit")(QuitIt))).flatMap {
        case OpenIt(i) => opened[R](path, rs(i).id).flatMap(_ => main[R](path, query))
        case FindIt(t, tag) => main[R](path, (t, tag.filter(_.nonEmpty)))
        case AddIt => adding[R].flatMap(_ => main[R](path))
        case DefineIt => defining[R].flatMap(_ => main[R](path))
        case SaveIt => saving[R](path).flatMap(_ => main[R](path, query))
        case QuitIt => journaled[R].flatMap { case (_, unsaved) =>
          if (unsaved == 0) pure[Unit, R](())
          else confirm[R](View.text(s"$unsaved unsaved changes. Save before quitting?")).flatMap(yes => if (yes) saving[R](path) else pure[Unit, R](()))
        }
      }
    })
  }

  sealed trait CardChoice
  final case class CopyIt(field: String) extends CardChoice
  final case class RevealIt(field: String) extends CardChoice
  case object ReviseIt extends CardChoice
  case object HistoryIt extends CardChoice
  final case class RetireIt(reason: Option[String]) extends CardChoice
  case object DoneIt extends CardChoice

  def opened[R[_]](path: String, id: RecordId)(implicit n: Needs[R]): Unit ! R = {
    import n._
    push[Place, R](Opened(id)).flatMap(_ => get[R](id)).flatMap {
      case None => show[R](View.problem("the record is gone"))
      case Some(r) => kinds[R].flatMap { ks =>
        val k = ks.find(_.id == r.kind).getOrElse(Kind(r.kind, r.kind.value, Schema.Record(Vector.empty), Set.empty, Nil, false))
        val names = fieldsOf(k).map(_.name).toList
        val which = choice[String](names.map(x => x -> x): _*).described("Field")
        offer[CardChoice, R](card(k, r), List(
          Action[String, CardChoice]("copy", "Copy a field", record(field("field", which))(x => x)(x => x), Some("to the clipboard, exactly"))(CopyIt(_)),
          Action[String, CardChoice]("reveal", "Reveal a field", record(field("field", which))(x => x)(x => x), Some("shown in the clear"))(RevealIt(_)),
          Action.plain[CardChoice]("revise", "New version")(ReviseIt),
          Action.plain[CardChoice]("history", "History")(HistoryIt),
          Action[Option[String], CardChoice]("retire", "Retire", record(field("reason", text.optional).labelled("Reason"))(x => x)(x => x))(RetireIt(_)),
          Action.plain[CardChoice]("done", "Back to the list")(DoneIt))).flatMap {
          case CopyIt(fld) => reveal[R](id, fld).flatMap {
            case Some(v) => copy[R](v).flatMap(_ => show[R](View.info(s"$fld copied")))
            case None => show[R](View.problem(s"$fld not shown"))
          }.flatMap(_ => opened[R](path, id))
          case RevealIt(fld) => reveal[R](id, fld).flatMap {
            case Some(v) => show[R](View.fields(fld -> View.text(v)))
            case None => show[R](View.problem(s"$fld not shown"))
          }.flatMap(_ => opened[R](path, id))
          case ReviseIt => revising[R](k, r).flatMap(_ => opened[R](path, id))
          case HistoryIt => versions[R](k, id).flatMap(_ => opened[R](path, id))
          case RetireIt(reason) => confirm[R](View.text("Retire this record? Its history stays.")).flatMap(yes =>
            if (yes) retire[R](id, reason).flatMap(_ => show[R](View.info("retired"))) else opened[R](path, id))
          case DoneIt => pure[Unit, R](())
        }
      }
    }
  }

  def adding[R[_]](implicit n: Needs[R]): Unit ! R = {
    import n._
    push[Place, R](Adding).flatMap(_ => kinds[R]).flatMap { ks =>
      choose[Option[Kind], R](View.heading("What kind of record?", 2), ks.map(k => k.name -> (Some(k): Option[Kind])) :+ ("Cancel" -> (None: Option[Kind]))).flatMap {
        case None => pure[Unit, R](())
        case Some(k) => fillOrCancel[Json, R](View.heading(k.name, 2), k.shape).flatMap {
          case None => pure[Unit, R](())
          case Some(fields) => fillOrCancel[Meta, R](View.heading("Context, validity, tags", 2), meta(None)).flatMap {
            case None => pure[Unit, R](())
            case Some(m) => fields match {
              case o: Json.Obj => add[R](k.id, o, m.ctx, m.valid, m.tagSet, m.note).flatMap(r => show[R](View.info(s"added: ${summary(k, r)}")))
              case _ => show[R](View.problem("not a record"))
            }
          }
        }
      }
    }
  }

  def revising[R[_]](k: Kind, r: Record)(implicit n: Needs[R]): Unit ! R = {
    import n._
    fillOrCancel[Json, R](View.heading(s"${k.name}: a new version", 2), k.shapeWith(r.fields)).flatMap {
      case None => pure[Unit, R](())
      case Some(fields) => fillOrCancel[Meta, R](View.heading("Context, validity, tags", 2), meta(Some(r))).flatMap {
        case None => pure[Unit, R](())
        case Some(m) => fields match {
          case o: Json.Obj => revise[R](r.id, o, m.ctx, m.valid, m.tagSet, m.note).flatMap(_ => show[R](View.info("a new version kept")))
          case _ => show[R](View.problem("not a record"))
        }
      }
    }
  }

  def versions[R[_]](k: Kind, id: RecordId)(implicit n: Needs[R]): Unit ! R = {
    import n._
    push[Place, R](Versions(id)).flatMap(_ => history[R](id)).flatMap { vs =>
      val table = View.Table(Vector("#", "version", "at", "summary"), vs.zipWithIndex.map { case (v, i) =>
        Vector(View.text((i + 1).toString), View.text(v.version.value), View.text(v.at.toString), View.text(summary(k, v))) }.toVector)
      offer[Option[Int], R](View.stack(View.heading("Versions, newest first", 2), table),
        List(Action[Int, Option[Int]]("open", "Open a version", record(field("n", choice[Int](vs.indices.map(i => (i + 1).toString -> i): _*)))(i => i)(i => i))(Some(_)),
          Action.plain[Option[Int]]("done", "Back")(None))).flatMap {
        case Some(i) => show[R](card(k, vs(i))).flatMap(_ => versions[R](k, id))
        case None => pure[Unit, R](())
      }
    }
  }

  def defining[R[_]](implicit n: Needs[R]): Unit ! R = {
    import n._
    push[Place, R](Defining).flatMap(_ => fillOrCancel[KindSpec, R](View.heading("A kind of your own", 2), kindForm)).flatMap {
      case None => pure[Unit, R](())
      case Some(spec) => define[R](kindOf(spec)).flatMap(_ => show[R](View.info(s"defined: ${spec.name}")))
    }
  }

  def saving[R[_]](path: String)(implicit n: Needs[R]): Unit ! R = {
    import n._
    journaled[R].flatMap { case (events, _) =>
      save[R](path, Journal.write(Journal.Header(1, events.headOption.map(at).getOrElse(0L)), events)).flatMap {
        case Right(()) => saved[R].flatMap(_ => show[R](View.info(s"saved to $path")))
        case Left(e) => show[R](View.problem(s"not saved: $e"))
      }
    }
  }
  private def at(e: Event): Long = e match {
    case Event.KindDefined(_, t) => t; case Event.KindRevised(_, t) => t; case Event.RecordAdded(r) => r.at
    case Event.RecordRevised(r) => r.at; case Event.RecordRetired(_, _, t) => t; case Event.Revealed(_, _, _, t) => t
  }

  /** the whole: the file opened, the main screen until quit */
  def run[R[_]](path: String)(implicit n: Needs[R]): Unit ! R = {
    import n._
    open[R](path).flatMap {
      case Left(e) => show[R](View.problem(s"$path: $e"))
      case Right(lines) =>
        val (_, _, damaged) = Journal.read(lines)
        (if (damaged.isEmpty) pure[Unit, R](()) else show[R](View.warning(s"${damaged.size} damaged line(s) skipped: ${damaged.map(_.line).mkString(", ")}"))).flatMap(_ => main[R](path))
    }
  }
}
