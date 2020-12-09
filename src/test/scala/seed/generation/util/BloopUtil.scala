package seed.generation.util

import java.nio.file.Path

import bloop.config.ConfigCodecs
import org.apache.commons.io.FileUtils

object BloopUtil {
  def readJson(path: Path): bloop.config.Config.File = {
    val bytes = FileUtils.readFileToByteArray(path.toFile)
    ConfigCodecs.read(bytes).right.get
  }
}
