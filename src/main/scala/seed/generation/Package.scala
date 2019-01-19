package seed.generation

import java.io.{File, FileInputStream, FileOutputStream}
import java.util.jar.{Attributes, JarEntry, JarOutputStream, Manifest}

import org.apache.commons.io.IOUtils
import java.nio.file.Path

import seed.Log
import seed.cli.util.Ansi

// Adapted from https://stackoverflow.com/a/1281295
object Package {
  def create(source: List[(Path, String)],
             target: Path,
             mainClass: Option[String],
             classPath: List[String]): Unit = {
    val manifest = new Manifest()
    val mainAttributes = manifest.getMainAttributes
    mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
    mainClass.foreach(cls =>
      mainAttributes.put(Attributes.Name.MAIN_CLASS, cls))
    if (classPath.nonEmpty)
      mainAttributes.put(Attributes.Name.CLASS_PATH, classPath.mkString(" "))
    val targetFile = new JarOutputStream(
      new FileOutputStream(target.toFile), manifest)
    source.foreach { case (path, jarPath) =>
      Log.debug(s"Packaging ${Ansi.italic(path.toString)}...")
      add(path.toFile, jarPath, targetFile)
    }
    Log.info(s"Written $target")
    targetFile.close()
  }

  def add(source: File, jarPath: String, target: JarOutputStream): Unit = {
    val path =
      if (source.isFile) jarPath
      else {
        require(!jarPath.endsWith("/"))
        jarPath + "/"
      }

    val entry = new JarEntry(path)
    entry.setTime(source.lastModified)
    target.putNextEntry(entry)

    if (source.isFile)
      IOUtils.copy(new FileInputStream(source), target)
    else
      for (nestedFile <- source.listFiles)
        add(nestedFile, path + nestedFile.getName, target)

    target.closeEntry()
  }
}
