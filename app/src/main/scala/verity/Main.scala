package verity

import okay.effects.{Sum, Void}
import okay.effects.machine.Machine
import okay.effects.world.Console
import okay.face.{Face, Faces, Pages}
import okay.face.swing.SwingFace
import okay.face.term.Terminal

/** `verity [file]` at the terminal; `verity swing [file]` in a window. The default file: ~/.verity/verity.jsonl */
object Main {
  val faces: Faces[Machine.type] = new Faces[Machine.type]()(Machine)
  val app = new Verity[Machine.type](faces)
  import app.domain._
  import Machine._

  type C[X] = Sum[Void, Console, X]
  type CF[X] = Sum[C, Face, X]
  type N[X] = Sum[CF, app.Nav, X]
  type NF[X] = Sum[N, Files, X]
  type NFC[X] = Sum[NF, Clipboard, X]
  type NFCR[X] = Sum[NFC, Reveal, X]
  type NFCRC[X] = Sum[NFCR, Consent, X]
  type All[X] = Sum[NFCRC, Store, X]

  def defaultPath: String = sys.props.get("user.home").map(_ + "/.verity/verity.jsonl").getOrElse("verity.jsonl")

  /** the application wired with the world's handlers: the disk, the clipboard, the owner asked at the face; `Face` and
   * `Console` left for the face chosen */
  def wired(path: String, pages: Pages[app.Place]): (Vault, Unit) ! CF = {
    implicit val needs: app.Needs[All] = app.Needs[All]
    val initial = Disk.read(path).map(Journal.read).map { case (_, events, _) => Vault.of(events) }.getOrElse(Vault.empty)
    val ids = () => java.util.UUID.randomUUID().toString
    val now = () => System.currentTimeMillis()
    store(initial, ids, now)(askingTheOwner(faces)(policy(Audience.Owner)(clipboardSystem(filesOnDisk(app.navs.memory(pages)(app.run[All](path)))))))
  }

  def main(args: Array[String]): Unit = args.toList match {
    case "swing" :: rest =>
      val path = rest.headOption.getOrElse(defaultPath)
      val pages = new Pages[app.Place](app.Main)
      val face = new SwingFace[Machine.type](faces)
      // Console is in the row for the terminal's sake; a window has none: the real console still answers it
      val io = new okay.effects.world.Io[Machine.type]()(Machine)
      face.window("Verity", faces.steps[(Vault, Unit), CF, C](wired(path, pages)).asInstanceOf[okay.face.Step[(Vault, Unit), ({ type L[X] = X ! Void })#L] ! Void],
        (_: (Vault, Unit)) => sys.exit(0), pages = Some((pages, (p: Any) => p.toString)))
    case rest =>
      val path = rest.headOption.getOrElse(defaultPath)
      val pages = new Pages[app.Place](app.Main)
      val term = new Terminal[Machine.type](faces)
      pages.onChange(p => ())   // a terminal could print the place; the main screen says enough
      val result = Machine.run(term.io.realConsole(term.terminal[(Vault, Unit), C](faces.steps[(Vault, Unit), CF, C](wired(path, pages)))))
      println(result.fold("bye")(_ => "bye"))
  }
}
