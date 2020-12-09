package seed.config.util

import java.nio.file.{Path, Paths}

import org.apache.commons.io.FileUtils
import seed.{Log, LogLevel}
import seed.cli.util.Ansi
import seed.model.Build.{ModuleKindJs, PlatformModule, VersionTag}
import seed.model.{Licence, Platform, TomlBuild}
import toml.{Codec, Extension, Parse, Value}

import scala.util.Try

object TomlUtils {
  val Extensions: Set[Extension] = Set(Extension.MultiLineInlineTables)

  object Codecs {
    implicit val logLevelCodec: Codec[LogLevel] = Codec {
      case (Value.Str(level), _, _) if level == "detail" =>
        Log.urgent.error(
          "The log level 'detail' was deprecated. Using 'info' instead..."
        )
        Right(LogLevel.Info)
      case (Value.Str(level), _, _) =>
        LogLevel.All.get(level) match {
          case None =>
            Left(
              (
                List(),
                s"Invalid log level ${Ansi.italic(level)} provided. Choose one of: " +
                  LogLevel.All.keys.map(Ansi.italic).mkString(", ")
              )
            )
          case Some(l) => Right(l)
        }

      case (value, _, _) =>
        Left((List(), s"Log level expected, $value provided"))
    }

    implicit val platformCodec: Codec[Platform] = Codec {
      case (Value.Str(id), _, _) =>
        Platform.All.keys.find(_.id == id) match {
          case None =>
            Left(
              (
                List(),
                s"Invalid target platform provided. Choose one of: " +
                  Platform.All.keys.toList
                    .sorted(Platform.Ordering)
                    .map(p => Ansi.italic(p.id))
                    .mkString(", ")
              )
            )
          case Some(p) => Right(p)
        }

      case (value, _, _) =>
        Left((List(), s"Platform expected, $value provided"))
    }

    implicit val versionTagCodec: Codec[VersionTag] = Codec {
      case (Value.Str(id), _, _) =>
        id match {
          case "full"           => Right(VersionTag.Full)
          case "binary"         => Right(VersionTag.Binary)
          case "platformBinary" => Right(VersionTag.PlatformBinary)
          case _                => Left((List(), "Invalid version tag provided"))
        }

      case (value, _, _) =>
        Left((List(), s"Version tag expected, $value provided"))
    }

    implicit val moduleKindJs: Codec[ModuleKindJs] = Codec {
      case (Value.Str(id), _, _) =>
        id match {
          case "default"  => Right(ModuleKindJs.Default)
          case "commonjs" => Right(ModuleKindJs.CommonJs)
          case _          => Left((List(), "Invalid module kind provided"))
        }

      case (value, _, _) =>
        Left((List(), s"Module kind expected, $value provided"))
    }

    implicit val licenceCodec: Codec[Licence] = Codec {
      case (Value.Str(id), _, _) =>
        Licence.All.find(_.id == id) match {
          case Some(licence) => Right(licence)
          case _ =>
            Left(
              (
                List(),
                s"Invalid licence ID provided. Choose one of: ${Licence.All.map(_.id).mkString(", ")}"
              )
            )
        }

      case (value, _, _) =>
        Left((List(), s"Licence ID expected, $value provided"))
    }

    def pathCodec(f: Path => Path): Codec[Path] = Codec {
      case (Value.Str(path), _, _) => Right(f(Paths.get(path)))
      case (value, _, _)           => Left((List(), s"Path expected, $value provided"))
    }

    implicit val platformModuleCodec: Codec[PlatformModule] = Codec {
      case (Value.Str(platformModule), _, _) =>
        val parts = platformModule.split(":")
        if (parts.length != 2)
          Left(
            (List(), s"Expected format: ${Ansi.italic("<module>:<platform>")}")
          )
        else {
          val (name, target) = (parts(0), parts(1))

          Platform.All.keys.find(_.id == target) match {
            case None =>
              Left(
                (List(), s"Invalid platform ${Ansi.italic(target)} provided")
              )
            case Some(tgt) => Right(PlatformModule(name, tgt))
          }
        }

      case (value, _, _) =>
        Left(
          (List(), s"String (<module>:<platform>) expected, $value provided")
        )
    }
  }

  def parseFile[T](
    path: Path,
    f: String => Either[Parse.Error, T],
    description: String,
    log: Log
  ): Option[T] =
    Try(FileUtils.readFileToString(path.toFile, "UTF-8")).toOption match {
      case None =>
        log.error(
          s"The $description ${Ansi.italic(path.toString)} could not be loaded"
        )
        None

      case Some(content) =>
        f(content) match {
          case Left((address, message)) =>
            log.error(
              s"The $description ${Ansi.italic(path.toString)} is malformed"
            )
            log.error(s"${Ansi.bold("Message:")} $message", detail = true)

            if (address.nonEmpty) {
              val trace = address.map(Ansi.italic).mkString(" → ")
              log.error(s"${Ansi.bold("Trace:")} $trace", detail = true)
            }

            None

          case Right(c) => Some(c)
        }
    }

  def fixPath(projectPath: Path, path: Path): Path =
    if (path.toString.startsWith("/")) path
    else projectPath.resolve(path).normalize()

  def parseBuildToml(
    projectPath: Path
  )(content: String): Either[Parse.Error, TomlBuild] = {
    import toml._
    import toml.Codecs._
    import seed.config.util.TomlUtils.Codecs._

    implicit val pCodec = pathCodec(fixPath(projectPath, _))

    Toml.parseAs[TomlBuild](content, Extensions)
  }
}
