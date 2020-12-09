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

    def parse(tomlPath: Path) = {
      def parseRaw(
        toml: String
      ): Either[_root_.toml.Parse.Error, List[Value.Tbl]] =
        Toml.parse(toml) match {
          case Left(l) => Left(l)
          case Right(r) =>
            r.values.get("import") match {
              case Some(Value.Arr(values))
                  if values.forall(_.isInstanceOf[Value.Str]) =>
                val included = values.flatMap {
                  case Value.Str(path) =>
                    val resolved =
                      TomlUtils.fixPath(tomlPath.getParent, Paths.get(path))
                    TomlUtils
                      .parseFile(resolved, parseRaw, "Seed configuration", log)
                      .getOrElse(sys.exit(1))
                }
                Right(included :+ Value.Tbl(r.values - "import"))
              case None => Right(List(r))
              case _    => Left(List("import") -> "Only paths may be specified")
            }
        }

      def f(toml: String): Either[_root_.toml.Parse.Error, Config] =
        parseRaw(toml).right.flatMap { configurations =>
          val parsedAll = configurations.foldLeft(Map[String, Value]()) {
            case (acc, cur) =>
              acc ++ cur.values
          }

          Toml.parseAs[Config](Value.Tbl(parsedAll))
        }

      TomlUtils
        .parseFile(tomlPath, f, "Seed configuration", log)
        .getOrElse(sys.exit(1))
    }

    userConfigPath match {
      case None =>
        if (Files.exists(DefaultPath)) parse(DefaultPath) else Config()

      case Some(path) =>
        if (Files.exists(path)) parse(path)
        else {
          log.error(
            s"Invalid path to Seed configuration file provided: ${Ansi.italic(path.toString)}"
          )
          sys.exit(1)
        }
    }
  }
}
