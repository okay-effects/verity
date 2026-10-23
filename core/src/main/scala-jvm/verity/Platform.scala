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
  /** lines added at the end, what is there untouched: opened to append, the batch one write, one appender at a time in
   * this process (sessions of a server share the file) */
  def append(path: String, lines: List[String]): Either[String, Unit] = appending.synchronized {
    try {
      val p = Paths.get(path); Option(p.getParent).foreach(d => NioFiles.createDirectories(d))
      val bytes = java.nio.ByteBuffer.wrap(lines.map(_ + System.lineSeparator).mkString.getBytes("UTF-8"))
      val channel = java.nio.channels.FileChannel.open(p,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)
      try while (bytes.hasRemaining) channel.write(bytes) finally channel.close()
      Right(())
    } catch { case e: Exception => Left(e.getMessage) }
  }
  private val appending = new Object
}
