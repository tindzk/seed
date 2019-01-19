package seed.model

case class Artefact(organisation: String,
                    name: String,
                    platformSuffix: Artefact.PlatformSuffix)

object Artefact {
  case class Versioned(artefact: Artefact, version: String)

  sealed trait PlatformSuffix
  object PlatformSuffix {
    case object PlatformAndCompiler extends PlatformSuffix
    case object Compiler extends PlatformSuffix
    case object CompilerLibrary extends PlatformSuffix
    case object Regular extends PlatformSuffix
  }

  import PlatformSuffix._

  def scalaCompiler(organisation: String) =
    Artefact(organisation, "scala-compiler", Regular)
  def scalaLibrary(organisation: String) =
    Artefact(organisation, "scala-library", Regular)
  def scalaReflect(organisation: String) =
    Artefact(organisation, "scala-reflect", Regular)

  val CompilerBridge = Artefact("ch.epfl.scala", "compiler-bridge", Compiler)

  val ScalaJsCompiler = Artefact("org.scala-js", "scalajs-compiler", Compiler)
  val ScalaJsLibrary  = Artefact("org.scala-js", "scalajs-library", CompilerLibrary)

  val ScalaNativePlugin    = Artefact("org.scala-native", "nscplugin", Compiler)
  val ScalaNativeJavalib   = Artefact("org.scala-native", "javalib", PlatformAndCompiler)
  val ScalaNativeScalalib  = Artefact("org.scala-native", "scalalib", PlatformAndCompiler)
  val ScalaNativeNativelib = Artefact("org.scala-native", "nativelib", PlatformAndCompiler)
  val ScalaNativeAuxlib    = Artefact("org.scala-native", "auxlib", PlatformAndCompiler)

  val Minitest   = Artefact("io.monix", "minitest", PlatformAndCompiler)
  val ScalaTest  = Artefact("org.scalatest", "scalatest", PlatformAndCompiler)
  val ScalaCheck = Artefact("org.scalacheck", "scalacheck", PlatformAndCompiler)
  val Utest      = Artefact("com.lihaoyi", "utest", PlatformAndCompiler)
}
