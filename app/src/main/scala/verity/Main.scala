package verity

import okay.effects.{Sum, Void}
import okay.effects.machine.Machine
import okay.effects.world.Console
import okay.face.{Face, Faces, Look, Paint, Pages}
import okay.face.term.Ansi
import okay.face.swing.SwingFace
import okay.face.term.Terminal
import okay.store.jdbc.{Jdbcs, Sqlite}

/** `verity [file]` at the terminal; `verity swing [file]` in a window; `verity mcp` for an agent; `verity web`, `app`,
 * `browser`, `api` on a port: pages from the server, a page over REST, the dialogue in the page, the JSON API. The file
 * is the journal, ~/.verity/verity.jsonl by default; a `.db` file is a SQLite database instead (`Storage`) */
object Main {
  val faces: Faces[Machine.type] = new Faces[Machine.type]()(Machine)
  val agents = new Agents[Machine.type](faces)
  val app = new Verity[Machine.type](faces)
  val jdbcs: Jdbcs[Machine.type] = new Jdbcs[Machine.type]()(Machine)
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

  /** WHERE THE STORE IS: the journal file folded in memory (`keeping`), or a SQLite database when the file is a `.db`
   * (okay-store's `jdbc`). One per process, shared by every session; the dialogue is the same either way. `journal` is
   * the file the dialogue's Save writes: the file itself, or `<db>.jsonl` beside the database as its backup */
  final class Storage(file: String) {
    val onDatabase: Boolean = file.endsWith(".db")
    val journal: String = if (onDatabase) file + ".jsonl" else file
    private val connection = if (onDatabase) Some(Sqlite.open(file, Kinds.builtIn)) else None
    @volatile var vault: Vault = if (onDatabase) Vault.empty else Disk.read(file).map(Journal.read).map { case (_, events, _) => Vault.of(events) }.getOrElse(Vault.empty)
    val ids: () => String = () => java.util.UUID.randomUUID().toString
    val now: () => Long = () => System.currentTimeMillis()
    def apply[R[_], Rest[_], A](p: A ! R)(implicit sp: Split[Store, R, Rest]): A ! Rest = connection match {
      case Some(c) => jdbcs.on(c, ids, now)(p)
      case None => store(vault, ids, now)(p).map { case (v, a) => vault = v; a }
    }
    /** the file's handler: the journal, whichever file the dialogue names */
    def onDisk: FilesOn = filesOn(_ => Disk.read(journal), (_, lines) => Disk.write(journal, lines))
    def where: String = if (onDatabase) s"$file (SQLite), the journal's backup $journal" else file
  }

  /** the application wired with the world's handlers: the storage, the disk, the clipboard, the owner asked at the face;
   * `Face` and `Console` left for the face chosen */
  def wired(storage: Storage, pages: Pages[app.Place]): Unit ! CF = {
    implicit val needs: app.Needs[All] = app.Needs[All]
    storage(askingTheOwner(faces)(policy(Audience.Owner)(clipboardTo(SystemClipboard.put)(storage.onDisk(app.navs.memory(pages)(app.run[All](storage.journal)))))))
  }

  /** the owner in a browser (`web`: pages from the server; `app`: one page, okay-faces' remote face over REST; `browser`:
   * the dialogue in the page, the store and the file served) or a program over a JSON API (`api`): one JDK server, one
   * storage, the dialogue a session per visitor */
  def served(mode: String, port: Int, path: String): Unit = {
    import okay.http.{Http, Https, JdkHttp, JdkServer}
    type L[X] = Sum[Void, Http, X]
    type HF[X] = Sum[L, Face, X]
    type HN[X] = Sum[HF, app.Nav, X]
    type HNF[X] = Sum[HN, Files, X]
    type HNFC[X] = Sum[HNF, Clipboard, X]
    type HNFCR[X] = Sum[HNFC, Reveal, X]
    type HNFCRC[X] = Sum[HNFCR, Consent, X]
    type HAll[X] = Sum[HNFCRC, Store, X]
    implicit val needs: app.Needs[HAll] = app.Needs[HAll]
    val https = new Https[Machine.type]()(Machine)
    val jdk = new JdkHttp[Machine.type]()(Machine)
    val storage = new Storage(path)
    def session(): faces.Dialogue[Unit, L] = {
      // each visitor: the one storage, the owner at their own face (web, app) or a program with no consent (api)
      val pages = new Pages[app.Place](app.Main)
      val inner = clipboardTo(SystemClipboard.put)(storage.onDisk(app.navs.memory(pages)(app.run[HAll](storage.journal))))
      val program = if (mode != "api") storage(askingTheOwner(faces)(policy(Audience.Owner)(inner)))
                    else storage(consenting(Nil)(policy(Audience.Agent("api"))(inner)))
      faces.dialogue[Unit, L](faces.steps[Unit, HF, L](program))
    }
    val sessions = okay.face.rest.RestFace(faces).sessions[Unit, L](() => session()).service
    // `browser`: the dialogue runs in the page; the server is the storage and the file, as REST domains
    type LS[X] = Sum[L, Store, X]
    type LFi[X] = Sum[L, Files, X]
    val domains = new okay.face.rest.RestDomains[Machine.type]()(Machine)
    val storeServed = domains.serve[Store, LS](okay.store.Stores.describe, "/store")
    val filesServed = domains.serve[Files, LFi](Describes.files, "/files")
    def pages(req: okay.http.Request): Option[okay.http.Response ! L] = (req.method, req.path) match {
      case ("GET", "/") => Some(pure[okay.http.Response, L](okay.http.Response.html(Shell.page("Verity", if (mode == "app") "remote" else "local", new java.io.File(storage.journal).getName))))
      case ("GET", "/okay.css") => Some(pure[okay.http.Response, L](okay.http.Response.ok(okay.face.Css(Look.default), "text/css; charset=utf-8")))
      case ("GET", "/app.js") => Some(pure[okay.http.Response, L](Shell.script.fold(e => okay.http.Response.text(e, 503), js => okay.http.Response.ok(js, "application/javascript; charset=utf-8"))))
      case _ => None
    }
    val service: okay.http.Request => okay.http.Response ! L = mode match {
      case "web" => okay.face.html.HtmlFace(faces).pages[Unit, L](() => session(), "Verity").service
      case "app" => req => pages(req).getOrElse(sessions(req))
      case "browser" => req => pages(req).getOrElse(
        if (req.path.startsWith("/store")) storage(storeServed.service(req))
        else if (req.path.startsWith("/files")) storage.onDisk(filesServed.service(req))
        else pure[okay.http.Response, L](okay.http.Response.notFound(req.path)))
      case _ => sessions
    }
    val server = JdkServer.start(port)
    System.err.println(s"verity $mode at http://127.0.0.1:${server.port}/" + (if (mode == "api") "sessions (POST to open one)" else "") + s", on ${storage.where}")
    if (mode == "app" || mode == "browser") Shell.script.left.foreach(System.err.println)
    Machine.run(jdk.jdk(server)(https.serve[L](service)))
  }

  def main(args: Array[String]): Unit = args.toList match {
    case ("web" | "app" | "browser" | "api") :: rest =>
      val (port, file) = rest match { case p :: f :: _ if p.forall(_.isDigit) => (p.toInt, f); case p :: Nil if p.forall(_.isDigit) => (p.toInt, defaultPath); case f :: _ => (8080, f); case Nil => (8080, defaultPath) }
      served(args.head, port, file)
    case "mcp" :: rest =>
      // for an agent over stdio: the audience an agent, the owner's consent asked through the client
      val storage = new Storage(rest.headOption.getOrElse(defaultPath))
      type L[X] = Sum[Void, okay.mcp.Link, X]
      type ALF[X] = Sum[L, Face, X]
      type AN[X] = Sum[ALF, app.Nav, X]
      type ANF[X] = Sum[AN, Files, X]
      type ANFC[X] = Sum[ANF, Clipboard, X]
      type ANFCR[X] = Sum[ANFC, Reveal, X]
      type ANFCRC[X] = Sum[ANFCR, Consent, X]
      type AAll[X] = Sum[ANFCRC, Store, X]
      implicit val needs: app.Needs[AAll] = app.Needs[AAll]
      val pages = new Pages[app.Place](app.Main)
      val program = storage(agents.consentByElicitation(policy(Audience.Agent("mcp"))(clipboardTo(SystemClipboard.put)(storage.onDisk(app.navs.memory(pages)(app.run[AAll](storage.journal)))))))
      Machine.run(agents.face.servers.links.stdio(agents.face.servers.serve[L](agents.server[Unit, L](faces.steps[Unit, ALF, L](program)))))
    case "swing" :: rest =>
      val storage = new Storage(rest.headOption.getOrElse(defaultPath))
      val pages = new Pages[app.Place](app.Main)
      val face = new SwingFace[Machine.type](faces)
      // Console is in the row for the terminal's sake; a window has none: the real console still answers it
      val io = new okay.effects.world.Io[Machine.type]()(Machine)
      face.window("Verity", faces.steps[Unit, CF, C](wired(storage, pages)).asInstanceOf[okay.face.Step[Unit, ({ type L[X] = X ! Void })#L] ! Void],
        (_: Unit) => sys.exit(0), pages = Some((pages, (p: Any) => p.toString)), look = Some(Look.default))
    case rest =>
      val storage = new Storage(rest.headOption.getOrElse(defaultPath))
      val pages = new Pages[app.Place](app.Main)
      // colour at a terminal, plain in a pipe: the look is the same, the paint is the console's
      val term = new Terminal[Machine.type](faces, if (System.console() != null) Ansi(Look.default) else Paint.none)
      pages.onChange(p => ())   // a terminal could print the place; the main screen says enough
      val result = Machine.run(term.io.realConsole(term.terminal[Unit, C](faces.steps[Unit, CF, C](wired(storage, pages)))))
      println(result.fold("bye")(_ => "bye"))
  }
}

/** THE SHELL of `verity app`: a page with nothing but the look, a root and the script; the script is the linked page of
 * the `web` project (`sbt web/fullLinkJS`), found by `-Dverity.js=path` or at its usual place under `web/target` */
object Shell {
  def page(title: String, mode: String, file: String): String =
    s"""<!DOCTYPE html>
       |<html lang="en"><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/>
       |<title>$title</title><link rel="stylesheet" href="/okay.css"/></head>
       |<body><div id="app" data-mode="$mode" data-file="${file.replace("\"", "")}"></div><script src="/app.js"></script></body></html>""".stripMargin
  def script: Either[String, String] = {
    val candidates = sys.props.get("verity.js").toList ++ List("web/target/scala-3.3.8/verity-web-opt/main.js", "web/target/scala-3.3.8/verity-web-fastopt/main.js")
    candidates.map(new java.io.File(_)).find(_.isFile) match {
      case Some(f) => Right(new String(java.nio.file.Files.readAllBytes(f.toPath), "UTF-8"))
      case None => Left(s"no page script: `sbt web/fullLinkJS` makes it, or -Dverity.js=path names it (looked at ${candidates.mkString(", ")})")
    }
  }
}
