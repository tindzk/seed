package seed.cli

import java.net.URI
import java.nio.file.Path

import seed.Log
import seed.cli.util.{Ansi, WsClient}
import seed.config.BuildConfig
import seed.model.Platform
import seed.Cli.Command

object Link {
  def parseCliModuleName(module: String): (String, Option[Platform]) =
    if (!module.contains(":")) (module, None)
    else {
      val parts = module.split(":")
      if (parts.length != 2) {
        Log.error(s"Expected syntax: ${Ansi.italic("<name>:<platform>")}")
        sys.exit(1)
      }

      val name = parts(0)
      val platform = Platform.All.find(_._1.id == parts(1)).map(_._1).getOrElse {
        Log.error(s"Invalid platform ${Ansi.italic(parts(1))} provided")
        sys.exit(1)
      }

      (name, Some(platform))
    }

  def ui(buildPath: Path, command: Command.Link): Unit = {
    command.webSocket match {
      case Some(connection) =>
        if (command.watch)
          Log.error("--watch cannot be combined with --connect")

        val uri = s"ws://${connection.host}:${connection.port}"
        Log.debug(s"Sending command to $uri...")
        val client = new WsClient(new URI(uri), () => {
          import io.circe.syntax._
          val build =
            if (buildPath.isAbsolute) buildPath else buildPath.toAbsolutePath
          (WsCommand.Link(build, command.modules.map { module =>
            val (name, platform) = parseCliModuleName(module)
            WsCommand.Module(name, platform)
          }): WsCommand).asJson.noSpaces
        })
        client.connect()

      case None =>
        BuildConfig.load(buildPath, Log) match {
          case None => sys.exit(1)
          case Some((projectPath, build)) =>
            val modules = command.modules.map(parseCliModuleName)
            seed.build.Link.link(build, projectPath, modules, command.watch,
              Log, println)
        }
    }
  }
}
