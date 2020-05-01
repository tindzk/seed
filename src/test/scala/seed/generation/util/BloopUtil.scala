package seed.generation.util

import java.nio.file.Path

import bloop.config.ConfigEncoderDecoders
import org.apache.commons.io.FileUtils

object BloopUtil {
  def readJson(path: Path): bloop.config.Config.File = {
    val content = FileUtils.readFileToString(path.toFile, "UTF-8")

    import io.circe.parser._
    decode[bloop.config.Config.File](content)(
      ConfigEncoderDecoders.allDecoder
    ).right.get
  }
}
