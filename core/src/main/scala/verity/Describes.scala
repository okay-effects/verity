package verity

import okay.effects.shape.Shape
import okay.effects.shape.Shape.{field, record, text, unit}
import okay.effects.describe.{Call, Describe, Op}

/** VERITY'S OWN EFFECTS DESCRIBED (the store's description is okay-store's): `Files`, so a page performs them and the
 * server's disk answers. A result that may fail crosses as `{lines}` or `{error}` */
object Describes {
  private val opened: Shape[Either[String, List[String]]] = record(field("lines", text.many().optional), field("error", text.optional))(
    (ls, e) => e.toLeft(ls.getOrElse(Nil)))(x => x.fold(e => (None, Some(e)), ls => (Some(ls), None)))
  private val saved: Shape[Either[String, Unit]] = record(field("error", text.optional))(e => e.toLeft(()))(x => x.left.toOption)
  val open: Op[Files, String, Either[String, List[String]]] =
    Op("open", record(field("path", text))(p => p)(p => p), opened, (p: String) => Files.Open(p, x => x), Some("the file's lines"))
  val append: Op[Files, (String, List[String]), Either[String, Unit]] =
    Op("append", record(field("path", text), field("lines", text.many()))((p, ls) => (p, ls))(x => x), saved,
      (a: (String, List[String])) => Files.Append(a._1, a._2, x => x), Some("lines appended to the file"),
      writes = true)
  val files: Describe[Files] = new Describe[Files]("files") {
    val operations = List(open, append)
    def split[X](op: Files[X]): Call[X] = op match {
      case Files.Open(p, k) => call(open, p, k)
      case Files.Append(p, ls, k) => call(append, (p, ls), k)
    }
  }
}
