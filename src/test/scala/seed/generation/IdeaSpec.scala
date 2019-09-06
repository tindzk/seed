package seed.generation

import minitest.SimpleTestSuite
import java.nio.file.{Files, Paths}

import org.apache.commons.io.FileUtils
import seed.Cli.{Command, PackageConfig}
import seed.{Log, cli}
import seed.artefact.ArtefactResolution
import seed.config.BuildConfig
import seed.config.BuildConfig.ModuleConfig
import seed.generation.util.PathUtil
import seed.model.Build.{Module, Resolvers}
import seed.model.{Build, Config}
import seed.generation.util.BuildUtil.tempPath

object IdeaSpec extends SimpleTestSuite {
  private val packageConfig = PackageConfig(
    tmpfs = false,
    silent = false,
    ivyPath = None,
    cachePath = None
  )

  test("Normalise paths") {
    assertEquals(
      PathUtil.normalisePath(Idea.ModuleDir, Paths.get("/tmp"))(
        Paths.get("/tmp")
      ),
      "$MODULE_DIR$/"
    )

    assertEquals(
      PathUtil.normalisePath(Idea.ModuleDir, Paths.get("/tmp/.idea/modules"))(
        Paths.get("/tmp/src")
      ),
      "$MODULE_DIR$/../../src"
    )

    assertEquals(
      PathUtil.normalisePath(Idea.ModuleDir, Paths.get(".idea/modules"))(
        Paths.get("/tmp/build")
      ),
      "/tmp/build"
    )
  }

  test("Do not resolve symbolic links when normalising paths") {
    Files.deleteIfExists(Paths.get("/tmp/tmp-link"))
    Files.createSymbolicLink(Paths.get("/tmp/tmp-link"), Paths.get("/tmp"))

    assertEquals(
      PathUtil.normalisePath(Idea.ModuleDir, Paths.get(".idea/modules"))(
        Paths.get("/tmp/tmp-link")
      ),
      "/tmp/tmp-link"
    )
  }

  test("Generate modules") {
    val build =
      Map(
        "a" -> Module(
          scalaVersion = Some("2.12.8"),
          jvm = Some(
            Module(
              root = Some(Paths.get("a")),
              sources = List(Paths.get("a/src"))
            )
          ),
          target = Map("assets" -> Build.Target())
        ),
        "b" -> Module(
          scalaVersion = Some("2.12.8"),
          jvm = Some(
            Module(
              root = Some(Paths.get("b")),
              sources = List(Paths.get("b/src"))
            )
          ),
          target = Map("assets" -> Build.Target(Some(Paths.get("b/assets"))))
        ),
        "c" -> Module(
          scalaVersion = Some("2.12.8"),
          // Module that only has test sources
          jvm = Some(Module(root = Some(Paths.get("c")))),
          test = Some(
            Module(jvm = Some(Module(sources = List(Paths.get("c/test")))))
          )
        )
      ).mapValues(
        m =>
          ModuleConfig(BuildConfig.inheritSettings(Module())(m), Paths.get("."))
      )

    assertEquals(
      build("c").module.test.get.jvm.get.sources,
      List(Paths.get("c/test"))
    )

    val projectPath   = Paths.get(".")
    val outputPath    = Paths.get("/tmp")
    val compilerDeps0 = ArtefactResolution.allCompilerDeps(build)
    val (_, platformResolution, compilerResolution) =
      ArtefactResolution.resolution(
        seed.model.Config(),
        Resolvers(),
        build,
        packageConfig,
        optionalArtefacts = false,
        Set(),
        compilerDeps0,
        Log.urgent
      )

    Idea.build(
      projectPath,
      outputPath,
      build,
      platformResolution,
      compilerResolution,
      false,
      Log.silent
    )

    val modulesPath = outputPath.resolve(".idea").resolve("modules")

    assert(Files.exists(modulesPath.resolve("a.iml")))
    assert(!Files.exists(modulesPath.resolve("a-assets.iml")))
    assert(Files.exists(modulesPath.resolve("b.iml")))
    assert(Files.exists(modulesPath.resolve("b-assets.iml")))
    assert(Files.exists(modulesPath.resolve("c.iml")))
  }

  test("Generate project with correct module dependencies") {
    val result = BuildConfig
      .load(Paths.get("test").resolve("platform-module-deps"), Log.urgent)
      .get
    val outputPath = tempPath.resolve("platform-module-deps")
    Files.createDirectory(outputPath)
    cli.Generate.ui(
      Config(),
      result.projectPath,
      outputPath,
      result.resolvers,
      result.build,
      Command.Idea(packageConfig),
      Log.urgent
    )

    val ideaPath    = outputPath.resolve(".idea")
    val modulesPath = ideaPath.resolve("modules")

    val exampleModule =
      pine.XmlParser.fromString(
        FileUtils.readFileToString(
          modulesPath.resolve("example.iml").toFile,
          "UTF-8"
        )
      )

    val moduleNames = exampleModule
      .byTagAll["orderEntry"]
      .filter(_.attr("type").contains("module"))
      .flatMap(_.attr("module-name"))
    assertEquals(moduleNames, List("core"))
  }

  test("Generate project with custom compiler options") {
    val result =
      BuildConfig.load(Paths.get("test/compiler-options"), Log.urgent).get
    val outputPath = tempPath.resolve("compiler-options")
    Files.createDirectory(outputPath)
    cli.Generate.ui(
      Config(),
      result.projectPath,
      outputPath,
      result.resolvers,
      result.build,
      Command.Idea(packageConfig),
      Log.urgent
    )

    val ideaPath = outputPath.resolve(".idea")

    val scalaCompiler =
      pine.XmlParser.fromString(
        FileUtils.readFileToString(
          ideaPath.resolve("scala_compiler.xml").toFile,
          "UTF-8"
        )
      )

    val profileNodes = scalaCompiler.byTagAll["profile"]
    assertEquals(profileNodes.length, 1)
    assertEquals(
      profileNodes.head.attr("modules"),
      Some("demo,demo-jvm,demo-js")
    )
    assertEquals(
      profileNodes.head.byTag["parameter"].attr("value"),
      Some("-Yliteral-types")
    )

    val modulesPath = ideaPath.resolve("modules")
    val demoJvm =
      pine.XmlParser.fromString(
        FileUtils.readFileToString(
          modulesPath.resolve("demo-jvm.iml").toFile,
          "UTF-8"
        )
      )

    val sourceFolderNodes = demoJvm.byTagAll["sourceFolder"]
    assertEquals(
      sourceFolderNodes
        .flatMap(_.attr("url"))
        .map(_.split("compiler-options").last),
      List("/jvm/src")
    )
  }

  test("Generate project with modules that have different Scala options") {
    val result = BuildConfig
      .load(Paths.get("test/module-scala-options"), Log.urgent)
      .get
    val outputPath = tempPath.resolve("module-scala-options-idea")
    Files.createDirectory(outputPath)
    cli.Generate.ui(
      Config(),
      result.projectPath,
      outputPath,
      result.resolvers,
      result.build,
      Command.Idea(packageConfig),
      Log.urgent
    )

    val ideaPath = outputPath.resolve(".idea")

    val scalaCompiler =
      pine.XmlParser.fromString(
        FileUtils.readFileToString(
          ideaPath.resolve("scala_compiler.xml").toFile,
          "UTF-8"
        )
      )

    val componentNodes = scalaCompiler.byTagAll["component"]
    assertEquals(componentNodes.length, 1)

    val profileNodes = componentNodes.head.byTagAll["profile"]
    assertEquals(profileNodes.length, 2)

    val coreProfile    = profileNodes(0)
    val exampleProfile = profileNodes(1)

    assertEquals(coreProfile.attr("modules"), Some("core"))
    assertEquals(
      coreProfile.byTag["parameter"].attr("value"),
      Some("-Yliteral-types")
    )

    assertEquals(exampleProfile.byTag["parameters"].children, List())
  }

  test("Generate project with different Scala versions") {
    val result = BuildConfig
      .load(Paths.get("test/multiple-scala-versions"), Log.urgent)
      .get
    val outputPath = tempPath.resolve("multiple-scala-versions-idea")
    Files.createDirectory(outputPath)
    cli.Generate.ui(
      Config(),
      result.projectPath,
      outputPath,
      result.resolvers,
      result.build,
      Command.Idea(packageConfig),
      Log.urgent
    )

    val ideaPath = outputPath.resolve(".idea")

    val scalaCompiler =
      pine.XmlParser.fromString(
        FileUtils.readFileToString(
          ideaPath.resolve("scala_compiler.xml").toFile,
          "UTF-8"
        )
      )

    val profileNodes = scalaCompiler.byTagAll["profile"]
    assertEquals(profileNodes.length, 1)
    assertEquals(profileNodes.head.attr("modules"), Some("module212,module211"))

    val scalaLibrary2_11_11 =
      pine.XmlParser.fromString(
        FileUtils.readFileToString(
          ideaPath
            .resolve("libraries")
            .resolve("org_scala_lang_2_11_11.xml")
            .toFile,
          "UTF-8"
        )
      )
    val scalaLibrary2_12_8 =
      pine.XmlParser.fromString(
        FileUtils.readFileToString(
          ideaPath
            .resolve("libraries")
            .resolve("org_scala_lang_2_12_8.xml")
            .toFile,
          "UTF-8"
        )
      )
    assertEquals(
      scalaLibrary2_11_11.byTagAll["language-level"].map(_.toText),
      List("Scala_2_11")
    )
    assert(scalaLibrary2_11_11.byTag["JAVADOC"].byTagOpt["root"].isDefined)
    assert(scalaLibrary2_11_11.byTag["SOURCES"].byTagOpt["root"].isDefined)
    assertEquals(
      scalaLibrary2_12_8.byTagAll["language-level"].map(_.toText),
      List("Scala_2_12")
    )
    assert(scalaLibrary2_12_8.byTag["JAVADOC"].byTagOpt["root"].isDefined)
    assert(scalaLibrary2_12_8.byTag["SOURCES"].byTagOpt["root"].isDefined)

    val module211 =
      pine.XmlParser.fromString(
        FileUtils.readFileToString(
          ideaPath.resolve("modules").resolve("module211.iml").toFile,
          "UTF-8"
        )
      )
    val libraries211 = module211
      .byTagAll["orderEntry"]
      .filter(_.attr("type").contains("library"))
      .flatMap(_.attr("name"))
    assertEquals(
      libraries211,
      List("org.scala-lang-2.11.11", "sourcecode_2.11-0.1.5.jar")
    )

    val module212 =
      pine.XmlParser.fromString(
        FileUtils.readFileToString(
          ideaPath.resolve("modules").resolve("module212.iml").toFile,
          "UTF-8"
        )
      )
    val libraries212 = module212
      .byTagAll["orderEntry"]
      .filter(_.attr("type").contains("library"))
      .flatMap(_.attr("name"))
    assertEquals(
      libraries212,
      List("org.scala-lang-2.12.8", "sourcecode_2.12-0.1.5.jar")
    )
  }

  test("Generate project with test project") {
    val result =
      BuildConfig.load(Paths.get("test/test-module"), Log.urgent).get
    val outputPath = tempPath.resolve("test-module")
    Files.createDirectory(outputPath)
    cli.Generate.ui(
      Config(),
      result.projectPath,
      outputPath,
      result.resolvers,
      result.build,
      Command.Idea(packageConfig),
      Log.urgent
    )

    val ideaPath = outputPath.resolve(".idea")
    val exampleModule =
      pine.XmlParser.fromString(
        FileUtils.readFileToString(
          ideaPath.resolve("modules").resolve("example.iml").toFile,
          "UTF-8"
        )
      )
    val exampleLibraries = exampleModule
      .byTagAll["orderEntry"]
      .filter(_.attr("type").contains("library"))
      .flatMap(l => l.attr("name").map(_ -> l.attr("scope")))
    assertEquals(
      exampleLibraries,
      List("org.scala-lang-2.12.8" -> None, "sourcecode_2.12-0.1.5.jar" -> None)
    )
  }

  test("Generate Scala Native project") {
    val result =
      BuildConfig.load(Paths.get("test/scala-native-module"), Log.urgent).get
    val outputPath = tempPath.resolve("scala-native-module")
    Files.createDirectory(outputPath)
    cli.Generate.ui(
      Config(),
      result.projectPath,
      outputPath,
      result.resolvers,
      result.build,
      Command.Idea(packageConfig),
      Log.urgent
    )
  }
}
