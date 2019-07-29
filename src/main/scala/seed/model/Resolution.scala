package seed.model

import java.nio.file.Path

import seed.model.Build.JavaDep

object Resolution {
  case class Artefact(javaDep: JavaDep,
                      libraryJar: Path,
                      javaDocJar: Option[Path],
                      sourcesJar: Option[Path])

  case class ScalaCompiler(scalaOrganisation: String,
                           scalaVersion: String,
                           libraries: List[Artefact],
                           classPath: List[Path],
                           compilerJars: List[Path])
}
