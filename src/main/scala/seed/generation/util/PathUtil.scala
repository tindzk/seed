package seed.generation.util

import java.nio.file.{Files, Path, Paths}

import seed.Log
import seed.cli.util.Ansi

object PathUtil {
  def tmpfsPath(projectPath: Path, log: Log): Path = {
    val name = projectPath.toAbsolutePath.getFileName.toString
    log.info("Build path set to tmpfs")
    log.warn(s"Please ensure that no other project with the name ${Ansi.italic(name)} compiles to tmpfs")
    Paths.get("/tmp").resolve("build-" + name)
  }

  def buildPath(projectPath: Path, tmpfs: Boolean, log: Log): Path =
    if (tmpfs) tmpfsPath(projectPath, log) else projectPath.resolve("build")

  def normalisePath(pathVariable: String, root: Path)(path: Path): String = {
    val canonicalRoot = root.toFile.getCanonicalPath
    val canonicalPath = path.toFile.getCanonicalPath

    val rootElems = canonicalRoot.split("/").toList
    val pathElems = canonicalPath.split("/").toList
    val common = pathElems.zip(rootElems).takeWhile { case (a, b) => a == b }

    if (common.length == 1) canonicalPath
    else {
      val levels = rootElems.length - common.length
      val relativePath = (0 until levels).map(_ => "../").mkString
      pathVariable + "/" + relativePath + pathElems.drop(common.length).mkString("/")
    }
  }

  def buildFilePath(userPath: Path): Path =
    if (!Files.isDirectory(userPath)) userPath
    else userPath.resolve("build.toml")
}
