package seed.model

import java.nio.file.Path

object Resolution {
  case class Artefact(libraryJar: Path,
                      javaDocJar: Option[Path],
                      sourcesJar: Option[Path])

  case class ScalaCompiler(scalaOrganisation: String,
                           scalaVersion: String,
                           fullClassPath: List[Path],
                           compilerJars: List[Path])
}
