import java.nio.file.{Files, Paths, StandardOpenOption}

object GenerateSources {
  def main(args: Array[String]): Unit = {
    val modulePath = sys.env("MODULE_PATH")
    val generatedPath =
      Paths.get(modulePath).resolve("demo").resolve("Generated.scala")
    val output = "object Generated { val constant = 42 }"

    Files.write(generatedPath, output.getBytes)
  }
}
