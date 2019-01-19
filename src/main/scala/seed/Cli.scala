package seed

import java.nio.file.{Path, Paths}

import scala.util.{Failure, Success}
import com.joefkelley.argyle._
import com.joefkelley.argyle.reader.Reader
import seed.artefact.Coursier
import seed.cli.util.{Ansi, ColourScheme}
import seed.config.{BuildConfig, SeedConfig}

object Cli {
  case class PackageConfig(tmpfs: Boolean,
                           silent: Boolean,
                           ivyPath: Option[Path],
                           cachePath: Option[Path])

  sealed trait Command
  object Command {
    case object Help extends Command
    case object Version extends Command
    case object Init extends Command
    case class Update(preRelease: Boolean) extends Command
    case class Package(packageConfig: PackageConfig,
                       libs: Boolean,
                       output: Option[Path],
                       module: String
                      ) extends Command

    sealed trait Build extends Command {
      def packageConfig: PackageConfig
    }

    case class Idea(packageConfig: PackageConfig)  extends Build
    case class Bloop(packageConfig: PackageConfig) extends Build
    case class All(packageConfig: PackageConfig)   extends Build
  }

  case class Config(configPath: Option[Path], buildPath: Path, command: Command)

  implicit val PathParser: Reader[Path] = path => Success(Paths.get(path))

  val packageConfigArg = (
    flag("--tmpfs") and
    flag("--silent") and
    optional[Path]("--ivy-path") and
    optional[Path]("--cache-path")
  ).to[PackageConfig]

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

      "update" -> flag("--pre-releases").to[Command.Update],
      "package" -> packageCommand
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
          val (projectPath, build) = BuildConfig.load(buildPath)
          cli.Package.ui(config, projectPath, build, module, output, libs,
            packageConfig)
        case Success(Config(configPath, buildPath, command: Command.Build)) =>
          val config = SeedConfig.load(configPath)
          val (projectPath, build) = BuildConfig.load(buildPath)
          cli.Build.ui(config, projectPath, build, command)
        case Failure(e) =>
          help()
          println()
          Log.error(e.getMessage)
          sys.exit(1)
      }
    }
}
