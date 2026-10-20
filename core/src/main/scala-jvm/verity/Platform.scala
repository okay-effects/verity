package verity

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
