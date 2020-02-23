package seed.generation

import java.io.{File, FileInputStream, OutputStream}
import java.util.jar.{Attributes, JarEntry, JarOutputStream, Manifest}

import org.apache.commons.io.IOUtils
import java.nio.file.Path

import seed.Log
import seed.cli.util.Ansi

import scala.collection.mutable

// Adapted from https://stackoverflow.com/a/1281295
object Package {
  def create(
    source: List[(Path, String)],
    target: OutputStream,
    mainClass: Option[String],
    classPath: List[String],
    log: Log
  ): Unit = {
    val manifest       = new Manifest()
    val mainAttributes = manifest.getMainAttributes
    mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
    // TODO Set additional package fields: https://docs.oracle.com/javase/tutorial/deployment/jar/packageman.html
    mainClass.foreach(
      cls => mainAttributes.put(Attributes.Name.MAIN_CLASS, cls)
    )
    if (classPath.nonEmpty)
      mainAttributes.put(Attributes.Name.CLASS_PATH, classPath.mkString(" "))

    val targetFile = new JarOutputStream(target, manifest)
    val entryCache = mutable.Set[String]()
    source.foreach {
      case (path, jarPath) =>
        log.debug(s"Packaging ${Ansi.italic(path.toString)}...")
        add(path.toFile, jarPath, targetFile, entryCache, log)
    }
    targetFile.close()
  }

  def add(
    source: File,
    jarPath: String,
    target: JarOutputStream,
    entryCache: mutable.Set[String],
    log: Log
  ): Unit = {
    val path =
      if (source.isFile) jarPath
      else {
        require(!jarPath.endsWith("/"))
        jarPath + "/"
      }

    val addedEntry =
      if (entryCache.contains(path)) {
        if (source.isFile)
          log.warn(
            s"Skipping file ${Ansi.italic(source.toString)} as another module already added it"
          )

        false
      } else {
        val entry = new JarEntry(path)
        entry.setTime(source.lastModified)
        target.putNextEntry(entry)
        entryCache += path
        if (source.isFile) IOUtils.copy(new FileInputStream(source), target)

        true
      }

    if (!source.isFile)
      for (nestedFile <- source.listFiles)
        add(nestedFile, path + nestedFile.getName, target, entryCache, log)

    if (addedEntry) target.closeEntry()
  }
}
