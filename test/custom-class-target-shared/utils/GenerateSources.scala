import java.io.File
import java.nio.file.{Files, Paths, StandardOpenOption}

object GenerateSources {
  def main(args: Array[String]): Unit = {
    val modulePath = sys.env("MODULE_PATH")
    val moduleSourcePaths =
      sys.env("MODULE_SOURCE_PATHS").split(File.pathSeparatorChar)
    val generatedPath =
      Paths.get(modulePath).resolve("demo").resolve("Generated.scala")
    val pathsScala = moduleSourcePaths.mkString("\"", "\", \"", "\"")
    val output     = s"object Generated { val modulePaths = List($pathsScala) }"

    Files.write(generatedPath, output.getBytes)
  }
}
