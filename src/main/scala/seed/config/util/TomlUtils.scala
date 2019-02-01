package seed.config.util

import java.nio.file.{Path, Paths}

import org.apache.commons.io.FileUtils
import seed.Log
import seed.cli.util.Ansi
import seed.model.Build.VersionTag
import seed.model.{Build, Platform}
import toml.{Codec, Value}

import scala.util.Try

object TomlUtils {
  object Codecs {
    implicit val platformCodec: Codec[Platform] = Codec {
      case (Value.Str(id), _, _) =>
        Platform.All.keys.find(_.id == id) match {
          case None    => Left((List(), s"Invalid target platform provided. Choose one of: " +
                                        Platform.All.keys.toList.sorted(Platform.Ordering)
                                          .map(p => Ansi.italic(p.id)).mkString(", ")))
          case Some(p) => Right(p)
        }

      case (value, _, _) => Left((List(), s"Platform expected, $value provided"))
    }

    implicit val versionTagCodec: Codec[VersionTag] = Codec {
      case (Value.Str(id), _, _) =>
        id match {
          case "full" => Right(VersionTag.Full)
          case "binary" => Right(VersionTag.Binary)
          case "platformBinary" => Right(VersionTag.PlatformBinary)
          case _ => Left((List(), "Invalid version tag provided"))
        }

      case (value, _, _) => Left((List(), s"Version tag expected, $value provided"))
    }

    def pathCodec(f: Path => Path): Codec[Path] = Codec {
      case (Value.Str(path), _, _) => Right(f(Paths.get(path)))
      case (value, _, _) => Left((List(), s"Path expected, $value provided"))
    }
  }

  def parseFile[T](path: Path, f: String => Either[Codec.Error, T], description: String, log: Log): Option[T] =
    Try(FileUtils.readFileToString(path.toFile, "UTF-8")).toOption match {
      case None =>
        log.error(s"The $description ${Ansi.italic(path.toString)} could not be loaded")
        None

      case Some(content) =>
        f(content) match {
          case Left((address, message)) =>
            log.error(s"The $description ${Ansi.italic(path.toString)} is malformed")
            log.error(s"${Ansi.bold("Message:")} $message")

            if (address.nonEmpty) {
              val trace = address.map(Ansi.italic).mkString(" → ")
              log.error(s"${Ansi.bold("Trace:")} $trace")
            }

            None

          case Right(c) => Some(c)
        }
    }

  def fixPath(projectPath: Path, path: Path): Path =
    if (path.toString.startsWith("/")) path
    else projectPath.resolve(path).normalize()

  def parseBuildToml(projectPath: Path)(content: String): Either[Codec.Error, Build] = {
    import toml._
    import toml.Codecs._
    import seed.config.util.TomlUtils.Codecs._

    implicit val pCodec = pathCodec(fixPath(projectPath, _))

    Toml.parseAs[Build](content)
  }
}
