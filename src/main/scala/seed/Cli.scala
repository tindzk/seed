package seed

import java.nio.file.{Path, Paths}

import scala.util.{Failure, Success, Try}
import com.joefkelley.argyle._
import com.joefkelley.argyle.reader.Reader
import seed.artefact.Coursier
import seed.cli.util.{Ansi, ColourScheme}
import seed.config.{BuildConfig, SeedConfig}
import seed.cli.util.ArgyleHelpers._

object Cli {
  case class PackageConfig(tmpfs: Boolean,
                           silent: Boolean,
                           ivyPath: Option[Path],
                           cachePath: Option[Path])
  case class WebSocketConfig(host: String, port: Short) {
    def format: String = host + ":" + port
  }

  sealed trait Command
  object Command {
    case object Help extends Command
    case object Version extends Command
    case object Init extends Command
    case class Server(packageConfig: PackageConfig,
                      webSocket: WebSocketConfig
                     ) extends Command
    case class Build(packageConfig: PackageConfig,
                     webSocket: Option[WebSocketConfig],
                     watch: Boolean,
                     modules: List[String]
                    ) extends Command
    case class Link(packageConfig: PackageConfig,
                    webSocket: Option[WebSocketConfig],
                    watch: Boolean,
                    modules: List[String]
                   ) extends Command
    case class BuildEvents(webSocket: WebSocketConfig) extends Command
    case class Update(preRelease: Boolean) extends Command
    case class Package(packageConfig: PackageConfig,
                       libs: Boolean,
                       output: Option[Path],
                       module: String
                      ) extends Command

    sealed trait Generate extends Command {
      def packageConfig: PackageConfig
    }

    case class Idea(packageConfig: PackageConfig)  extends Generate
    case class Bloop(packageConfig: PackageConfig) extends Generate
    case class All(packageConfig: PackageConfig)   extends Generate
  }

  case class Config(configPath: Option[Path], buildPath: Path, command: Command)

  implicit val pathParser: Reader[Path] = path => Success(Paths.get(path))

  val webSocketDefaultConnection = WebSocketConfig("localhost", 8275)

  def parseWebSocketArg(arg: String, name: String): Try[Cli.WebSocketConfig] = {
    val parts = arg.split(':').toList
    if (parts.length != 2 || parts.exists(_.isEmpty) || parts(1).exists(!_.isDigit))
      Failure(new Exception(s"Format: --$name=<host>:<port>"))
    else Success(WebSocketConfig(parts(0), parts(1).toShort))
  }

  val webSocketListenArg =
    optional[String]("--listen").flatMap {
      case None => Success(webSocketDefaultConnection)
      case Some(arg) => parseWebSocketArg(arg, "listen")
    }

  val webSocketConnectArg =
    optionalFreeFlag("--connect").flatMap {
      case None      => Success(None)
      case Some("")  => Success(Some(webSocketDefaultConnection))
      case Some(arg) => parseWebSocketArg(arg, "connect").map(Some(_))
    }

  val packageConfigArg = (
    flag("--tmpfs") and
    flag("--silent") and
    optional[Path]("--ivy-path") and
    optional[Path]("--cache-path")
  ).to[PackageConfig]

  val serverCommand = (
    packageConfigArg and
    webSocketListenArg
  ).to[Command.Server]

  val buildCommand = (
    packageConfigArg and
    webSocketConnectArg and
    flag("--watch") and
    repeatedAtLeastOnceFree[String]
  ).to[Command.Build]

  val linkCommand = (
    packageConfigArg and
    webSocketConnectArg and
    flag("--watch") and
    repeatedAtLeastOnceFree[String]
  ).to[Command.Link]

  val buildEventsCommand =
    webSocketConnectArg.map(_.getOrElse(webSocketDefaultConnection))
      .to[Command.BuildEvents]

  val packageCommand = (
    packageConfigArg and
    flag("--libs") and
    optional[Path]("--output") and
    requiredFree[String]
  ).to[Command.Package]

  val cliArgs = (
    optional[Path]("--config") and
    optional[Path]("--build").default(Paths.get("")) and
    requiredBranch[Command](
      "help" -> constant(Command.Help),
      "version" -> constant(Command.Version),
      "init" -> constant(Command.Init),

      "idea"  -> packageConfigArg.to[Command.Idea],
      "bloop" -> packageConfigArg.to[Command.Bloop],
      "all" -> packageConfigArg.to[Command.All],

      "server" -> serverCommand,
      "build" -> buildCommand,
      "link" -> linkCommand,
      "buildEvents" -> buildEventsCommand,
      "update" -> flag("--pre-releases").to[Command.Update],
      "package" -> packageCommand,
    )
  ).to[Config]

  def help(): Unit = {
    import seed.cli.util.Ansi._

    println(s"""${bold("seed")}
${underlined("Usage:")} seed [--build=<path>] [--config=<path>] <command>

  ${bold("Commands:")}
    ${italic("help")}          Show help
    ${italic("version")}       Show version
    ${italic("init")}          Interactively create a Seed configuration
    ${italic("bloop")}         Create Bloop project in the directory ${Ansi.italic(".bloop")}
    ${italic("idea")}          Create IDEA project in the directory ${Ansi.italic(".idea")}
    ${italic("all")}           Create Bloop and IDEA projects
    ${italic("server")}        Run Seed in server mode
    ${italic("build")}         Build module(s)
    ${italic("link")}          Link module(s)
    ${italic("buildEvents")}   Subscribe to build events on Seed server
    ${italic("update")}        Check library dependencies for updates
    ${italic("package")}       Create JAR package for given module and its dependent modules
                  Also sets the main class from the configuration file
                  Specify --libs to copy all library dependencies for distribution

  ${bold("Parameters:")}
    ${italic("--build")}       Path to the build file (default: ${Ansi.italic("build.toml")})
    ${italic("--config")}      Path to the Seed configuration file (default: ${Ansi.italic(config.SeedConfig.DefaultPath.toString)})

  ${bold("Command:")} ${underlined("bloop")}|${underlined("idea")}|${underlined("all")} [--tmpfs] [--silent] [--ivy-path=<path>] [--cache-path=<path>]
    ${italic("--tmpfs")}       Place the build directory in tmpfs
    ${italic("--silent")}      Hide download progress of dependency resolution, e.g. for Continuous Integration builds
    ${italic("--ivy-path")}    Path to local Ivy cache (default: ${Ansi.italic(Coursier.DefaultIvyPath.toString)})
    ${italic("--cache-path")}  Path to local Coursier artefact cache (default: ${Ansi.italic(Coursier.DefaultCachePath.toString)})

  ${bold("Command:")} ${underlined("server")} [--listen=${webSocketDefaultConnection.format}]
    ${italic("--listen")}     Host and port on which WebSocket server listens

  ${bold("Command:")} ${underlined("build")} [--connect[=${webSocketDefaultConnection.format}]] [--watch] [--tmpfs] <modules>
    ${italic("--connect")}     Run build command on remote Seed server
    ${italic("--watch")}       Build upon source changes (cannot be combined with ${Ansi.italic("--connect")})
    ${italic("<modules>")}     One or multiple space-separated modules. The syntax of a module is: ${italic("<name>")} or ${italic("<name>:<platform>")}
                  ${italic("Examples:")}
                  - app          Compile all available platforms of module ${Ansi.italic("app")}
                  - app:js       Only compile JavaScript platform of module ${Ansi.italic("app")}
                  - app:native   Only compile Native platform of module ${Ansi.italic("app")}
                  - app:<target> Only build ${Ansi.italic("<target>")} of the module ${Ansi.italic("app")}

  ${bold("Command:")} ${underlined("link")} [--connect[=${webSocketDefaultConnection.format}]] [--watch] <modules>
    ${italic("--connect")}     Run link command on remote Seed server
    ${italic("--watch")}       Link upon source changes (cannot be combined with ${Ansi.italic("--connect")})
    ${italic("<modules>")}     One or multiple space-separated modules. The syntax of a module is: ${italic("<name>")} or ${italic("<name>:<platform>")}
                  ${italic("Examples:")}
                  - app         Link all available platforms of module ${Ansi.italic("app")}
                  - app:js      Only link JavaScript platform of module ${Ansi.italic("app")}
                  - app:native  Only link Native platform of module ${Ansi.italic("app")}

    ${italic("Examples:")}
      1) seed link app:js app:native  Link JavaScript and native module ${Ansi.italic("app")}, then exit
      2) seed link --watch app:js     Continuously link JavaScript module ${Ansi.italic("app")}

  ${bold("Command:")} ${underlined("buildEvents")} [--connect[=${webSocketDefaultConnection.format}]]
    Connect to Seed server and subscribe to events from all triggered builds.
    The events will be printed to standard output as JSON.

  ${bold("Command:")} ${underlined("update")} [--pre-releases]
    ${italic("--pre-releases")}   When searching for updates, also consider pre-releases

  ${bold("Command:")} ${underlined("package")} [--tmpfs] [--libs] [--output=<path>] <module>
    ${italic("--tmpfs")}       Read build directory in tmpfs
    ${italic("--libs")}        Copy libraries and reference them in the JAR's class path.
    ${italic("--output")}      Output path (default: ${Ansi.italic("dist/")})
    ${italic("<module>")}      Module to package""")
  }

  def main(args: Array[String]): Unit =
    if (args.isEmpty) {
      Log.error("Invalid command.")
      Log.error("")

      Log.error("Create new Seed project file:")
      Log.error(Ansi.foreground(ColourScheme.green2)("$ seed init"))
      Log.error("")

      Log.error("Generate new Bloop configuration:")
      Log.error(Ansi.foreground(ColourScheme.green2)("$ seed bloop"))
      Log.error("")

      Log.error("Generate new IDEA configuration:")
      Log.error(Ansi.foreground(ColourScheme.green2)("$ seed idea"))
      Log.error("")

      Log.error("List all available commands:")
      Log.error(Ansi.foreground(ColourScheme.green2)("$ seed help"))

      sys.exit(1)
    } else {
      cliArgs.parse(args, EqualsSeparated) match {
        case Success(Config(_, _, Command.Help)) =>
          help()
          sys.exit(0)
        case Success(Config(_, _, Command.Version)) =>
          Log.info(Ansi.bold("Seed v" + BuildInfo.Version +
                             " for Bloop v" + BuildInfo.Bloop + "+ " +
                             "and Coursier v" + BuildInfo.Coursier))
        case Success(Config(_, buildPath, Command.Init)) =>
          cli.Scaffold.ui(buildPath)
        case Success(Config(_, buildPath, Command.Update(preRelease))) =>
          cli.Update.ui(buildPath, !preRelease)
        case Success(Config(configPath, buildPath, command: Command.Package)) =>
          import command._
          val config = SeedConfig.load(configPath)
          val BuildConfig.Result(build, projectPath, _) =
            BuildConfig.load(buildPath, Log).getOrElse(sys.exit(1))
          cli.Package.ui(config, projectPath, build, module, output, libs,
            packageConfig)
        case Success(Config(configPath, buildPath, command: Command.Generate)) =>
          val config = SeedConfig.load(configPath)
          val BuildConfig.Result(build, projectPath, _) =
            BuildConfig.load(buildPath, Log).getOrElse(sys.exit(1))
          cli.Generate.ui(config, projectPath, build, command)
        case Success(Config(configPath, _, command: Command.Server)) =>
          val config = SeedConfig.load(configPath)
          cli.Server.ui(config, command)
        case Success(Config(configPath, buildPath, command: Command.Build)) =>
          val config = SeedConfig.load(configPath)
          cli.Build.ui(buildPath, config, command)
        case Success(Config(configPath, buildPath, command: Command.Link)) =>
          val config = SeedConfig.load(configPath)
          cli.Link.ui(buildPath, config, command)
        case Success(Config(_, _, command: Command.BuildEvents)) =>
          cli.BuildEvents.ui(command)
        case Failure(e) =>
          help()
          println()
          Log.error(e.getMessage)
          sys.exit(1)
      }
    }
}
