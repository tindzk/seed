package seed.model

import seed.model.Build.{Dep, JavaDep, ScalaDep, VersionTag}

case class Artefact(organisation: String,
                    name: String,
                    versionTag: Option[VersionTag])

object Artefact {
  import VersionTag._

  def fromDep(dep: Dep): Artefact =
    dep match {
      case JavaDep(org, name, _) => Artefact(org, name, None)
      case ScalaDep(org, name, _, versionTag) =>
        Artefact(org, name, Some(versionTag))
    }

  def scalaCompiler(organisation: String) =
    Artefact(organisation, "scala-compiler", None)
  def scalaLibrary(organisation: String) =
    Artefact(organisation, "scala-library", None)
  def scalaReflect(organisation: String) =
    Artefact(organisation, "scala-reflect", None)

  val CompilerBridge = Artefact("ch.epfl.scala", "compiler-bridge", Some(Full))

  val ScalaJsCompiler = Artefact("org.scala-js", "scalajs-compiler", Some(Full))
  val ScalaJsLibrary  = Artefact("org.scala-js", "scalajs-library", Some(Binary))

  val ScalaNativePlugin    = Artefact("org.scala-native", "nscplugin", Some(Full))
  val ScalaNativeJavalib   = Artefact("org.scala-native", "javalib", Some(PlatformBinary))
  val ScalaNativeScalalib  = Artefact("org.scala-native", "scalalib", Some(PlatformBinary))
  val ScalaNativeNativelib = Artefact("org.scala-native", "nativelib", Some(PlatformBinary))
  val ScalaNativeAuxlib    = Artefact("org.scala-native", "auxlib", Some(PlatformBinary))

  val Minitest   = Artefact("io.monix", "minitest", Some(PlatformBinary))
  val ScalaTest  = Artefact("org.scalatest", "scalatest", Some(PlatformBinary))
  val ScalaCheck = Artefact("org.scalacheck", "scalacheck", Some(PlatformBinary))
  val Utest      = Artefact("com.lihaoyi", "utest", Some(PlatformBinary))
}
