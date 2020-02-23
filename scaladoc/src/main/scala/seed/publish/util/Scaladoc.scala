package seed.publish.util

import java.io.File
import java.util.function.Consumer

import scala.reflect.internal.util.Position
import scala.tools.nsc.doc.{DocFactory, Settings}
import scala.tools.nsc.reporters.Reporter

/** @author Gilles Dubochet, Stephane Micheloud */
class Scaladoc {

  /** Transforms a file into a scalac-readable string
    *
    *  @param file file to convert
    *  @return string representation of the file like `/x/k/a.scala`
    */
  private def asString(file: File): String = file.getAbsolutePath

  /** Transforms a path into a scalac-readable string
    *
    *  @param path path to convert
    *  @return string representation of the path like `a.jar:b.jar`
    */
  private def asString(path: Array[File]): String =
    path.toList.map(asString).mkString("", File.pathSeparator, "")

  private object Defaults {

    /** character encoding of the files to compile */
    val encoding: Option[String] = None

    /** fully qualified name of a doclet class, which will be used to generate the documentation */
    val docGenerator: Option[String] = None

    /** file from which the documentation content of the root package will be taken */
    val docRootContent: Option[File] = None

    /** document title of the generated HTML documentation */
    val docTitle: Option[String] = None

    /** document footer of the generated HTML documentation */
    val docFooter: Option[String] = None

    /** document version, added to the title */
    val docVersion: Option[String] = None

    /** generate links to sources */
    val docSourceUrl: Option[String] = None

    /** point out uncompilable sources */
    val docUncompilable: Option[String] = None

    /** ciompiler: generate deprecation information */
    val deprecation: Boolean = false

    /** compiler: generate unchecked information */
    val unchecked: Boolean = false

    /** document implicit conversions */
    val docImplicits: Boolean = false

    /** document all (including impossible) implicit conversions */
    val docImplicitsShowAll: Boolean = false

    /** output implicits debugging information */
    val docImplicitsDebug: Boolean = false

    /** create diagrams */
    val docDiagrams: Boolean = false

    /** output diagram creation debugging information */
    val docDiagramsDebug: Boolean = false

    /** use the binary given to create diagrams */
    val docDiagramsDotPath: Option[String] = None

    /** produce textual output from html pages, for easy diff-ing */
    val docRawOutput: Boolean = false

    /** do not generate prefixes */
    val docNoPrefixes: Boolean = false

    /** group similar functions together */
    val docGroups: Boolean = false

    /** skip certain packages */
    val docSkipPackages: String = ""
  }

  def settings(
    sourcePath: Array[File],
    classpath: Array[File],
    bootClasspath: Array[File],
    externalFolders: Array[File],
    destination: File,
    addParams: String
  ): Settings = {
    def error(msg: String): Unit = require(false)
    val docSettings              = new Settings(error)
    docSettings.outdir.value = asString(destination)
    if (classpath.nonEmpty)
      docSettings.classpath.value = asString(classpath)
    if (sourcePath.nonEmpty)
      docSettings.sourcepath.value = asString(sourcePath)
    if (bootClasspath.nonEmpty)
      docSettings.bootclasspath.value = asString(bootClasspath)
    if (externalFolders.nonEmpty)
      docSettings.extdirs.value = asString(externalFolders)

    import Defaults._
    encoding.foreach(docSettings.encoding.value = _)
    docTitle.foreach(docSettings.doctitle.value = _)
    docFooter.foreach(docSettings.docfooter.value = _)
    docVersion.foreach(docSettings.docversion.value = _)
    docSourceUrl.foreach(docSettings.docsourceurl.value = _)
    docUncompilable.foreach(docSettings.docUncompilable.value = _)

    docSettings.deprecation.value = deprecation
    docSettings.unchecked.value = unchecked
    docSettings.docImplicits.value = docImplicits
    docSettings.docImplicitsDebug.value = docImplicitsDebug
    docSettings.docImplicitsShowAll.value = docImplicitsShowAll
    docSettings.docDiagrams.value = docDiagrams
    docSettings.docDiagramsDebug.value = docDiagramsDebug
    docSettings.docRawOutput.value = docRawOutput
    docSettings.docNoPrefixes.value = docNoPrefixes
    docSettings.docGroups.value = docGroups
    docSettings.docSkipPackages.value = docSkipPackages
    docDiagramsDotPath.foreach(docSettings.docDiagramsDotPath.value = _)

    docGenerator.foreach(docSettings.docgenerator.value = _)
    docRootContent.foreach(
      value => docSettings.docRootContent.value = value.getAbsolutePath
    )

    // Enabled because DocFactory uses println() before 2.13.2 which clashes
    // with Seed's progress bars
    // See https://github.com/scala/scala/pull/8442
    docSettings.scaladocQuietRun = true

    docSettings.processArgumentString(addParams)
    docSettings
  }

  def execute(
    docSettings: Settings,
    sourceFiles: Array[File],
    error: Consumer[String],
    warn: Consumer[String]
  ): java.lang.Boolean = {
    val e = error
    val w = warn

    var errors = false

    val reporter = new Reporter {
      override protected def info0(
        pos: Position,
        msg: String,
        severity: Severity,
        force: Boolean
      ): Unit =
        if (!force) {
          if (severity == WARNING) w.accept(msg)
          else if (severity == ERROR) {
            errors = true
            e.accept(msg)
          }
        }
    }

    try {
      val docProcessor = new DocFactory(reporter, docSettings)
      docProcessor.document(sourceFiles.toList.map(_.toString): List[String])
      !errors
    } catch {
      case t: Throwable =>
        Option(t.getMessage) match {
          case None =>
            error.accept("Scaladoc failed due to an internal error")
          case Some(msg) =>
            error.accept(s"Scaladoc failed due to an internal error ($msg)")
        }
        false
    }
  }
}
