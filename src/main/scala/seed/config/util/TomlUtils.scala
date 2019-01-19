package seed.config.util

import java.nio.file.{Path, Paths}

import org.apache.commons.io.FileUtils
import seed.Log
import seed.cli.util.Ansi
import seed.model.Platform
import toml.{Codec, Value}

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

      case (value, _, _) => Left((List.empty, s"Platform expected, $value provided"))
    }

    def pathCodec(f: Path => Path): Codec[Path] = Codec {
      case (Value.Str(path), _, _) => Right(f(Paths.get(path)))
      case (value, _, _) => Left((List.empty, s"Path expected, $value provided"))
    }
  }

  def parseFile[T](path: Path, f: String => Either[Codec.Error, T], description: String): T = {
    val content = try FileUtils.readFileToString(path.toFile, "UTF-8") catch {
      case _: Throwable =>
        Log.error(s"The $description ${Ansi.italic(path.toString)} could not be loaded")
        sys.exit(1)
    }

    f(content) match {
      case Left((address, message)) =>
        Log.error(s"The $description ${Ansi.italic(path.toString)} is malformed")
        Log.error(s"${Ansi.bold("Message:")} $message")

        if (address.nonEmpty) {
          val trace = address.map(Ansi.italic).mkString(" → ")
          Log.error(s"${Ansi.bold("Trace:")} $trace")
        }

        sys.exit(1)

      case Right(c) => c
    }
  }
}
