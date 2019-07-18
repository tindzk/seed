package seed.config

import java.nio.file.{Files, Path, Paths}

import seed.Log
import seed.cli.util.Ansi
import seed.config.util.TomlUtils
import seed.model.Config

object SeedConfig {
  private val home = sys.props("user.home")

  val DefaultPath = Paths.get(home).resolve(".config").resolve("seed.toml")

  def load(userConfigPath: Option[Path]): Config = {
    import toml._
    import toml.Codecs._
    import TomlUtils.Codecs._

    implicit val pCodec = pathCodec(identity)

    val log = Log(Config())

    def parse(path: Path) =
      TomlUtils.parseFile(path, Toml.parseAs[Config](_), "Seed configuration", log)
        .getOrElse(sys.exit(1))

    userConfigPath match {
      case None =>
        if (Files.exists(DefaultPath)) parse(DefaultPath) else Config()

      case Some(path) =>
        if (Files.exists(path)) parse(path)
        else {
          log.error(s"Invalid path to Seed configuration file provided: ${Ansi.italic(path.toString)}")
          sys.exit(1)
        }
    }
  }
}
