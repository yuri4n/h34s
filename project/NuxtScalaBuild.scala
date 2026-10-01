import sbt._

object NuxtScalaBuild {
  def stage(source: File, destination: File, log: Logger): File = {
    require((source ** "*.js").get.nonEmpty, s"No linked Scala.js modules in $source")
    // Each linker keeps its own cache. Replace the import directory so switching
    // modes cannot retain files from a previous development or production link.
    IO.delete(destination)
    IO.copyDirectory(source, destination)
    log.info(s"Staged Scala.js modules from $source to $destination")
    destination
  }
}
