package seed.generation.util

import java.nio.file.Files

object BuildUtil {
  val tempPath = Files.createTempDirectory("seed")
}
