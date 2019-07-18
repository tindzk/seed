package seed.cli

import java.nio.file.{Files, Path}

import org.apache.commons.io.FileUtils
import seed.artefact.{MavenCentral, SemanticVersioning}
import seed.artefact.MavenCentral.{CompilerVersion, PlatformVersion}
import seed.cli.util.{Ansi, ColourScheme}
import seed.model.{Artefact, Organisation, Platform, TestFramework}
import seed.model.Platform.{JVM, JavaScript, Native}
import seed.Log
import toml.Node._
import toml.Value._
import toml._

import scala.util.{Success, Try}

class Scaffold(log: Log) {
  val console = System.console()

  def readInput[T](default: T, f: String => Option[T]): T = {
    val input = console.readLine().trim
    if (input.isEmpty) default else f(input).getOrElse {
      log.info("Try again")
      readInput(default, f)
    }
  }

  def askModuleName(): String = {
    log.info(s"${Ansi.italic("Module name?")} [default: ${Ansi.underlined("example")}]")
    readInput("example", x =>
      if (x.forall(_.isLetterOrDigit)) Some(x)
      else {
        log.error("The name may only consist of letters or digits")
        None
      })
  }

  def askStable(): Boolean = {
    log.info(s"${Ansi.italic("Do you want to use:")} 1) stable releases or 2) pre-releases? [default: ${Ansi.underlined("1")}]")
    readInput[Boolean](true, input =>
      Try(input.toInt) match {
        case Success(1) => Some(true)
        case Success(2) => Some(false)
        case _          =>
          log.error(s"Invalid number provided")
          None
      })
  }

  def askOrganisation(): Organisation = {
    log.info(s"${Ansi.italic("Do you want to use:")} 1) Lightbend or 2) Typelevel Scala? [default: ${Ansi.underlined("1")}]")
    readInput[Organisation](Organisation.Lightbend, input =>
      Try(input.toInt) match {
        case Success(1) => Some(Organisation.Lightbend)
        case Success(2) => Some(Organisation.Typelevel)
        case _          =>
          log.error(s"Invalid number provided")
          None
      })
  }

  def askPlatforms() = {
    log.info(s"${Ansi.italic("Which platform(s) do you want to support?")} [default: ${Ansi.underlined("1,2")}]")
    log.info(s" 1. JVM")
    log.info(s" 2. JavaScript")
    log.info(s" 3. Native (experimental)")

    readInput[Set[Platform]](Set(JVM, JavaScript), input => {
      val result = input.split(",").map(u => Try(u.toInt)).map {
        case Success(1) => Some(JVM)
        case Success(2) => Some(JavaScript)
        case Success(3) => Some(Native)
        case Success(i) =>
          log.error(s"Invalid number provided: $i")
          None
        case _          =>
          log.error(s"Invalid number provided")
          None
      }

      if (result.exists(_.isEmpty)) None else Some(result.flatten.toSet)
    })
  }

  def askTestFrameworks() = {
    log.info(s"${Ansi.italic("Which test framework(s) do you need?")} [default: ${Ansi.underlined("none")}]")
    log.info(s" 1. minitest")
    log.info(s" 2. ScalaTest")
    log.info(s" 3. ScalaCheck")
    log.info(s" 4. µTest")

    import TestFramework._
    readInput[Set[TestFramework]](Set(), input => {
      val result = input.split(",").map(u => Try(u.toInt)).map {
        case Success(1) => Some(Minitest)
        case Success(2) => Some(ScalaTest)
        case Success(3) => Some(ScalaCheck)
        case Success(4) => Some(Utest)
        case Success(i) =>
          log.error(s"Invalid number provided: $i")
          None
        case _          =>
          log.error(s"Invalid number provided")
          None
      }

      if (result.exists(_.isEmpty)) None else Some(result.flatten.toSet)
    })
  }

  type Libraries = Map[
    Artefact, List[
      (Platform, MavenCentral.PlatformVersion, MavenCentral.CompilerVersion)]]

  /** Find platform configuration supporting one of the compiler versions.
    * Choose (platform version, compiler version) tuple for each platform such
    * that the maximum number of libraries is supported.
    */
  def choosePlatformConfiguration(platforms: Set[Platform],
                                  compilerVersions: Map[Platform, List[String]],
                                  libraries: Libraries
                                 ): Map[Platform, (PlatformVersion, CompilerVersion)] = {
    platforms.toList.sorted(Platform.Ordering).flatMap { platform =>
      val versionTuples = libraries.flatMap(_._2).collect {
        case (p, platformVersion, compilerVersion) if p == platform =>
          (platformVersion, compilerVersion)
      }.toList.distinct.sortBy(_._1)(new SemanticVersioning(log).versionOrdering)

      val platformCompilerVersions =
        compilerVersions(platform).map(MavenCentral.trimCompilerVersion).toSet

      val versionTuplesForPlatform = versionTuples.filter { case (_, compilerVersion) =>
        platformCompilerVersions.contains(compilerVersion)
      }

      if (versionTuplesForPlatform.isEmpty) List()
      else List(platform ->
        versionTuplesForPlatform.reverse.maxBy {
          case (platformVersion, compilerVersion) =>
            libraries.count { case (_, versions) =>
              versions.contains((platform, platformVersion, compilerVersion))
            }
        }
      )
    }.toMap
  }

  def fetchCompilerVersions(organisation: String,
                            platforms: Set[Platform],
                            stable: Boolean): Map[Platform, List[String]] = {
    val compilerArtefacts: Map[Platform, Artefact] =
      Map((JVM, Artefact.scalaCompiler(organisation))) ++
      (if (platforms.contains(JavaScript)) Map((JavaScript, Artefact.ScalaJsCompiler)) else Map()) ++
      (if (platforms.contains(Native)) Map((Native, Artefact.ScalaNativePlugin)) else Map())

    compilerArtefacts.map { case (platform, artefact) =>
      if (artefact.versionTag.isEmpty)
        platform -> MavenCentral.fetchCompilerVersions(artefact, stable, log)
      else
        platform -> MavenCentral.fetchPlatformCompilerVersions(artefact, stable, log)
    }
  }

  def libraryPlatformCompatibility(platforms: Set[Platform],
                                   artefacts: Set[Artefact],
                                   compilerVersions: Map[Platform, List[String]],
                                   stable: Boolean
                                  ): Map[Platform, (PlatformVersion, CompilerVersion)] = {
    log.info("Fetching version matrix for libraries...")
    val libraries = artefacts.map { artefact =>
      val artefacts = MavenCentral.fetchLibraryArtefacts(artefact, stable, log)
      artefact -> artefacts
    }.toMap

    log.info("Resolving platform and compiler versions...")
    choosePlatformConfiguration(platforms, compilerVersions, libraries)
  }

  def checkVersions(organisation: String,
                    platforms: Set[Platform],
                    artefacts: Map[Platform, Set[Artefact]],
                    stable: Boolean
                   ) = {
    log.info("Fetching platform compiler versions...")
    val compilerVersions = fetchCompilerVersions(organisation, platforms, stable)

    val platformVersions: Map[Platform, (PlatformVersion, CompilerVersion)] =
      if (artefacts.isEmpty)
        Map[Platform, (PlatformVersion, CompilerVersion)]()
      else
        libraryPlatformCompatibility(platforms, artefacts.flatMap(_._2).toSet,
          compilerVersions, stable)

    println(Ansi.underlined("Version requirements"))

    println(
      util.Tabulator.format(List(
        List("Platform", "Compiler").map(fansi.Bold.On(_))
      ) ++ platforms.toList.sorted(Platform.Ordering).map { platform =>
        val (platformVersion, compilerVersion) =
          platformVersions.getOrElse(platform, ("latest", "latest"))

        List(
          if (platform == JVM)
            ColourScheme.yellow2.toFansi(fansi.Str(platform.caption))
          else
            ColourScheme.yellow2.toFansi(
              fansi.Str(platform.caption + " @ ") ++
              fansi.Bold.On(platformVersion)),

          fansi.Str("Scala @ ") ++ fansi.Bold.On(compilerVersion)
        )
      }))

    log.info("Resolving bridge and platform compiler versions...")
    val bridgeVersions = MavenCentral.fetchPlatformCompilerVersions(
      Artefact.CompilerBridge, stable, log)

    // When choosing Scala compiler version for each platform, ensure that
    // a corresponding plug-in exists
    val resolvedPlatformAndCompilerVersions: Map[Platform, (PlatformVersion, CompilerVersion)] =
      platforms.toList.sorted(Platform.Ordering).flatMap { platform =>
        compilerVersions.get(JVM).flatMap { jvmVersions =>
          // Only consider compiler versions for which a compatible bridge
          // exists
          val versions = jvmVersions.filter(cv =>
            bridgeVersions.exists(bv => cv.startsWith(bv)))

          val platformAndCompilerVersion = platformVersions.get(platform)
          val (platformVersion, suitableCompilerVersions) = platformAndCompilerVersion match {
            case None => ("", versions.reverse)
            case Some((pv, cv)) =>
              (pv, versions.filter(v =>
                MavenCentral.trimCompilerVersion(v) == cv
              ).reverse)
          }

          suitableCompilerVersions.view.flatMap { cv =>
            if (platform == JavaScript) {
              // We already know the compatible major version, but also need to
              // resolve the minor version for the platform compiler.
              val versions = MavenCentral.fetchVersions(
                Artefact.ScalaJsCompiler, platform, "", cv,
                stable, log)
              versions.reverse.find(_.startsWith(platformVersion)).map(_ -> cv)
            } else if (platform == Native) {
              val versions = MavenCentral.fetchVersions(
                Artefact.ScalaNativePlugin, platform, "",
                cv, stable, log)
              versions.reverse.find(_.startsWith(platformVersion)).map(_ -> cv)
            } else Some((cv, cv))
          }.headOption.map(platform -> _)
        }
      }.toMap

    val resolvedPlatformVersions: Map[Platform, PlatformVersion] =
      resolvedPlatformAndCompilerVersions.mapValues(_._1)
    val resolvedCompilerVersions: Map[Platform, CompilerVersion] =
      resolvedPlatformAndCompilerVersions.mapValues(_._2)

    val formattedOrganisation =
      Organisation.resolve(organisation).map(_.caption).getOrElse(organisation)

    println(Ansi.underlined("Platform compiler versions"))

    println(
      util.Tabulator.format(List(
        List("Platform", "Organisation", "Compiler", "Version").map(fansi.Bold.On(_))
      ) ++ platforms.toList.sorted(Platform.Ordering).flatMap { platform =>
        case class Item(organisation: String, compiler: String, version: Option[String])

        val platformCompiler =
          if (platform == JavaScript)
            Some(Item("Scala.js", "Plug-in", resolvedPlatformVersions.get(platform)))
          else if (platform == Native)
            Some(Item("Scala Native", "Plug-in", resolvedPlatformVersions.get(platform)))
          else
            None

        val compilerVersion = resolvedCompilerVersions.get(platform)
        val allArtefacts =
          List(Item(formattedOrganisation, "Scala", compilerVersion)) ++
          platformCompiler.toList

        allArtefacts.zipWithIndex.map { case (Item(org, compiler, version), i) =>
          List(
            if (i != 0) fansi.Str("")
            else ColourScheme.yellow2.toFansi(fansi.Str(platform.caption)),
            version match {
              case None    => ColourScheme.red1.toFansi(fansi.Str(org))
              case Some(_) => ColourScheme.green1.toFansi(fansi.Str(org))
            },
            version match {
              case None    => ColourScheme.red1.toFansi(fansi.Str(compiler))
              case Some(_) => ColourScheme.green1.toFansi(fansi.Str(compiler))
            },
            version match {
              case None    => ColourScheme.red1.toFansi(fansi.Str("Not available"))
              case Some(v) => ColourScheme.green1.toFansi(fansi.Str(v))
            }
          )
        }
      }
    ))

    val libraryArtefacts =
      if (!artefacts.exists(_._2.nonEmpty))
        Map[Platform, Map[Artefact, Option[String]]]()
      else {
        log.info("Resolving library versions...")

        val libraryArtefacts: Map[Platform, Map[Artefact, Option[String]]] =
          platforms.map { platform =>
            val platformVersion = platformVersions.get(platform).map(_._1)

            platform -> artefacts.getOrElse(platform, Set()).toList.flatMap { artefact =>
              resolvedCompilerVersions.get(platform).map { compilerVersion =>
                val allVersions = MavenCentral.fetchVersions(
                  artefact, platform, platformVersion.get, compilerVersion,
                  stable, log)
                val resolvedVersion = allVersions.lastOption
                artefact -> resolvedVersion
              }
            }.toMap
          }.toMap

        println(Ansi.underlined("Library versions"))

        println(
          util.Tabulator.format(List(
            List("Platform", "Organisation", "Artefact", "Version").map(fansi.Bold.On(_))
          ) ++ libraryArtefacts.toList.sortBy(_._1)(Platform.Ordering)
            .flatMap { case (platform, artefacts) =>
            // Keep toList, otherwise indices may be out of order
            artefacts.toList.zipWithIndex.map { case ((artefact, version), i) =>
              List(
                if (i != 0) fansi.Str("")
                else ColourScheme.yellow2.toFansi(fansi.Str(platform.caption)),
                version match {
                  case None    => ColourScheme.red1.toFansi(fansi.Str(artefact.organisation))
                  case Some(_) => ColourScheme.green1.toFansi(fansi.Str(artefact.organisation))
                },
                version match {
                  case None    => ColourScheme.red1.toFansi(fansi.Str(artefact.name))
                  case Some(_) => ColourScheme.green1.toFansi(fansi.Str(artefact.name))
                },
                version match {
                  case None    => ColourScheme.red1.toFansi(fansi.Str("Not available"))
                  case Some(v) => ColourScheme.green1.toFansi(fansi.Str(v))
                }
              )
            }
          }))

        libraryArtefacts
      }

    (resolvedCompilerVersions, resolvedPlatformVersions, libraryArtefacts)
  }

  def generateBuildFile(moduleName: String,
                        stable: Boolean,
                        organisation: Organisation,
                        platforms: Set[Platform],
                        testFrameworks: Set[TestFramework]): String = {
    val artefacts = testFrameworks.map(_.artefact)

    val (compilerVersions, platformVersions, libraryArtefacts) = checkVersions(
      organisation.packageName, platforms,
      Map(
        JVM -> artefacts,
        JavaScript -> artefacts,
        Native -> artefacts
      ), stable)

    val jvmScalaVersion    = compilerVersions(JVM)
    val jsScalaVersion     = compilerVersions.get(JavaScript)
    val nativeScalaVersion = compilerVersions.get(Native)
    val scalaJsVersion     = platformVersions.get(JavaScript)
    val scalaNativeVersion = platformVersions.get(Native)

    def resolveLibraryVersion(platform: Platform, artefact: Artefact): Option[String] =
      libraryArtefacts.get(platform)
        .flatMap(_.get(artefact))
        .flatten

    def scalaTestDeps(platform: Platform) = {
      val frameworks = testFrameworks.flatMap(tf =>
        resolveLibraryVersion(platform, tf.artefact).toList.map(version =>
          Arr(List(
            Str(tf.artefact.organisation),
            Str(tf.artefact.name),
            Str(version))))
      ).toList

      if (frameworks.isEmpty) Map() else Map("scalaDeps" -> Arr(frameworks))
    }

    toml.Toml.generate(Root(List(
      NamedTable(
        List("project"),
        Map("scalaVersion" -> Str(jvmScalaVersion)) ++
        (if (organisation == Organisation.Lightbend) Map()
         else Map("scalaOrganisation" -> Str(organisation.packageName))) ++
        (if (!platforms.contains(JavaScript)) Map()
         else Map("scalaJsVersion" -> Str(scalaJsVersion.get))) ++
        (if (!platforms.contains(Native)) Map() else
         Map("scalaNativeVersion" -> Str(scalaNativeVersion.get))) ++
        Map(
          "scalaOptions" -> Arr(List(
            Str("-encoding"), Str("UTF-8"),
            Str("-unchecked"),
            Str("-deprecation"),
            Str("-Xfuture")
          )),
          "testFrameworks" -> Arr(
            testFrameworks.map(tf => Str(tf.mainClass)).toList
          )
        )
      )
    ) ++
    (if (platforms.size == 1) List() else List(
      NamedTable(
        List("module", moduleName),
        Map(
          "root" -> Str("shared"),
          "sources" -> Arr(List(Str("shared/src"))),
          "targets" -> Arr(
            platforms.map(platform => Str(platform.id)).toList
          )
        )
      ),
      NamedTable(
        List("module", moduleName, "test"),
        Map("sources" -> Arr(List(Str("shared/test"))))
      )
    )) ++
    (if (!platforms.contains(JavaScript)) List() else List(
      NamedTable(
        List("module", moduleName, "js"),
        Map("root" -> Str("js")) ++
        (if (
          jsScalaVersion.isDefined && !jsScalaVersion.contains(jvmScalaVersion)
         ) Map("scalaVersion" -> Str(jsScalaVersion.get)) else Map()
        ) ++
        Map(
          "sources" -> Arr(List(
            if (platforms.size == 1) Str("src") else Str("js/src")
          ))
        )
      ),

      NamedTable(
        List("module", moduleName, "test", "js"),
        Map(
          "sources" -> Arr(List(
            if (platforms.size == 1) Str("test") else Str("js/test")
          ))
        ) ++ scalaTestDeps(JavaScript)
      )
    )) ++
    (if (!platforms.contains(JVM)) List() else List(
      NamedTable(
        List("module", moduleName, "jvm"),
        Map(
          "root" -> Str("jvm"),
          "sources" -> Arr(List(
            if (platforms.size == 1) Str("src") else Str("jvm/src")
          ))
        )
      ),
      NamedTable(
        List("module", moduleName, "test", "jvm"),
        Map(
          "sources" -> Arr(List(
            if (platforms.size == 1) Str("test") else Str("jvm/test")
          ))
        ) ++ scalaTestDeps(JVM)
      )
    )) ++
    (if (!platforms.contains(Native)) List() else List(
      NamedTable(
        List("module", moduleName, "native"),
        Map("root" -> Str("native")) ++
        (if (
          nativeScalaVersion.isDefined &&
          !nativeScalaVersion.contains(jvmScalaVersion)
         ) Map("scalaVersion" -> Str(nativeScalaVersion.get)) else Map()
        ) ++ Map(
          "sources" -> Arr(List(
            if (platforms.size == 1) Str("src") else Str("native/src")
          ))
        )
      ),
      NamedTable(
        List("module", moduleName, "test", "native"),
        Map(
          "sources" -> Arr(List(
            if (platforms.size == 1) Str("test") else Str("native/test")
          ))
        ) ++ scalaTestDeps(Native)
      )
    ))))
  }

  def createDirectories(seedPath: Path, platforms: Set[Platform]): Unit = {
    val basePath = seedPath.toAbsolutePath.getParent

    log.info("Creating folder structure...")

    val tree = (if (platforms.contains(JVM) && platforms.size > 1) {
      Files.createDirectories(basePath.resolve("jvm/src"))
      Files.createDirectories(basePath.resolve("jvm/test"))

      Seq(
        Seq(
          fansi.Bold.On("jvm/src"),
          fansi.Str("Source files for JVM platform")
        ),
        Seq(
          fansi.Bold.On("jvm/test"),
          fansi.Str("Test files for JVM platform")
        )
      )
    } else Seq()) ++
    (if (platforms.contains(Native) && platforms.size > 1) {
      Files.createDirectories(basePath.resolve("native/src"))
      Files.createDirectories(basePath.resolve("native/test"))

      Seq(
        Seq(
          fansi.Bold.On("native/src"),
          fansi.Str("Source files for Native platform")
        ),
        Seq(
          fansi.Bold.On("native/test"),
          fansi.Str("Test files for Native platform")
        )
      )
    } else Seq()) ++
    (if (platforms.contains(JavaScript) && platforms.size > 1) {
      Files.createDirectories(basePath.resolve("js/src"))
      Files.createDirectories(basePath.resolve("js/test"))

      Seq(
        Seq(
          fansi.Bold.On("js/src"),
          fansi.Str("Source files for JavaScript platform")
        ),
        Seq(
          fansi.Bold.On("js/test"),
          fansi.Str("Test files for JavaScript platform")
        )
      )
    } else Seq()) ++
    (if (platforms.size == 1) {
      Files.createDirectories(basePath.resolve("src"))
      Files.createDirectories(basePath.resolve("test"))

      Seq(
        Seq(
          fansi.Bold.On("src"),
          fansi.Str(s"Source files for ${platforms.head.caption} platform")
        ),
        Seq(
          fansi.Bold.On("test"),
          fansi.Str(s"Test files for ${platforms.head.caption} platform")
        )
      )
    } else {
      Files.createDirectories(basePath.resolve("shared/src"))
      Files.createDirectories(basePath.resolve("shared/test"))

      Seq(
        Seq(
          fansi.Bold.On("shared/src"),
          fansi.Str("Shared source files for all platforms")
        ),
        Seq(
          fansi.Bold.On("shared/test"),
          fansi.Str("Shared test files for all platforms")
        )
      )
    })

    println(util.Tree.format(tree))
  }

  def create(seedPath: Path): Unit = {
    log.info(s"Please answer the following questions to create the build file")
    log.info(s"The file will be named ${Ansi.italic(seedPath.toString)}")

    log.newLine()

    val moduleName = askModuleName()
    val stable = askStable()
    val organisation = askOrganisation()
    val platforms = askPlatforms()
    val testFrameworks = askTestFrameworks()
    val content =
      generateBuildFile(moduleName, stable, organisation, platforms, testFrameworks)

    FileUtils.write(seedPath.toFile, content, "UTF-8")
    createDirectories(seedPath, platforms)

    log.info("✓ Configuration written")
  }

  def ui(path: Path): Unit = {
    import Ansi._
    import ColourScheme._

    log.info(bold(foreground(blue2)("Welcome to Seed!")))

    val seedPath =
      if (Files.isRegularFile(path)) path
      else path.resolve("build.toml")

    if (Files.exists(seedPath))
      log.error(s"The file ${italic(seedPath.toString)} exists already. Please provide a different path.")
    else
      create(seedPath)
  }
}
