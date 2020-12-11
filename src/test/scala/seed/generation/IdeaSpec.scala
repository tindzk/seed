package seed.generation

import minitest.SimpleTestSuite
import java.nio.file.{Files, Path, Paths}

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
import seed.util.TestUtil

object IdeaSpec extends SimpleTestSuite {
  private val seedConfig = seed.model.Config()

  private val resolvers = Resolvers()

  private val log = Log.urgent

  private def dropPath(path: String): String =
    path.lastIndexOf('/') match {
      case -1 => path
      case n  => path.drop(n + 1)
    }

  private def createProject(name: String): (BuildConfig.Result, Path) = {
    val result     = BuildConfig.load(Paths.get("test", name), Log.urgent).get
    val outputPath = tempPath.resolve(name + "-idea")
    Files.createDirectory(outputPath)
    (result, outputPath)
  }

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

    val projectPath = Paths.get(".")
    val outputPath  = Paths.get("/tmp")

    val runtimeResolution = ArtefactResolution.runtimeResolution(
      build,
      seedConfig,
      resolvers,
      TestUtil.packageConfig,
      false,
      log
    )
    val compilerResolution = ArtefactResolution.compilerResolution(
      build,
      seedConfig,
      resolvers,
      TestUtil.packageConfig,
      false,
      log
    )

    Idea.build(
      projectPath,
      outputPath,
      build,
      runtimeResolution,
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
    val (result, outputPath) = createProject("platform-module-deps")

    cli.Generate.ui(
      Config(),
      result.projectPath,
      outputPath,
      result.resolvers,
      result.build,
      Command.Idea(TestUtil.packageConfig),
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
    assertEquals(moduleNames, List("base", "core"))
  }

  test("Generate non-JVM cross-platform module") {
    val (result, outputPath) = createProject("shared-module")

    cli.Generate.ui(
      Config(),
      result.projectPath,
      outputPath,
      result.resolvers,
      result.build,
      Command.Idea(TestUtil.packageConfig),
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

    val orderEntries = exampleModule.byTagAll["orderEntry"]

    // The IDEA module will take the Scala version of the JavaScript module and
    // assume that the libraries are also targeting the JavaScript platform.
    val libraryDeps = orderEntries
      .filter(_.attr("type").contains("library"))
      .flatMap(_.attr("name"))
    assertEquals(
      libraryDeps,
      List(
        "org.scala-lang-2.12.8",
        "sourcecode_sjs0.6_2.12-0.1.5.jar",
        "scalajs-library_2.12-0.6.28.jar"
      )
    )

    // A dependency to `example-native` is not needed since `example-js` will
    // have to expose the same public interface for cross-compilation to work.
    val moduleNames =
      orderEntries
        .filter(_.attr("type").contains("module"))
        .flatMap(_.attr("module-name"))
    assertEquals(moduleNames, List("example-js", "base", "base-js"))

    val modulesXml =
      pine.XmlParser.fromString(
        FileUtils.readFileToString(
          ideaPath.resolve("modules.xml").toFile,
          "UTF-8"
        )
      )
    val modulePaths =
      modulesXml.byTagAll["module"].flatMap(_.attr("fileurl")).map(dropPath)
    assertEquals(
      modulePaths,
      List(
        "base.iml",
        "base-js.iml",
        "base-native.iml",
        "example.iml",
        "example-js.iml"
      )
    )

    val scalaCompiler =
      pine.XmlParser.fromString(
        FileUtils.readFileToString(
          ideaPath.resolve("scala_compiler.xml").toFile,
          "UTF-8"
        )
      )

    val profileNodes = scalaCompiler
      .byTagAll["profile"]
      .map(
        profile =>
          profile.attr("modules").get -> profile
            .byTagAll["parameter"]
            .flatMap(_.attr("value"))
            .map(dropPath)
      )
      .toSet
    assertEquals(
      profileNodes,
      Set(
        "base,base-js,example,example-js" -> List(
          "scalajs-compiler_2.12.8-0.6.28.jar"
        ),
        "base-native" -> List("nscplugin_2.11.11-0.3.7.jar")
      )
    )
  }

  test("Generate project with custom compiler options") {
    val (result, outputPath) = createProject("compiler-options")

    cli.Generate.ui(
      Config(),
      result.projectPath,
      outputPath,
      result.resolvers,
      result.build,
      Command.Idea(TestUtil.packageConfig),
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

    val profileNodes = scalaCompiler
      .byTagAll["profile"]
      .map(
        profile =>
          profile.attr("modules").get -> profile
            .byTagAll["parameter"]
            .flatMap(_.attr("value"))
            .map(dropPath)
      )
      .toSet

    assertEquals(
      profileNodes,
      Set(
        "demo,demo-jvm" -> List("-Yliteral-types"),
        "demo-js" -> List(
          "-Yliteral-types",
          "scalajs-compiler_2.12.4-0.6.26.jar"
        )
      )
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
    val (result, outputPath) = createProject("module-scala-options")

    cli.Generate.ui(
      Config(),
      result.projectPath,
      outputPath,
      result.resolvers,
      result.build,
      Command.Idea(TestUtil.packageConfig),
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
    val (result, outputPath) = createProject("multiple-scala-versions")

    cli.Generate.ui(
      Config(),
      result.projectPath,
      outputPath,
      result.resolvers,
      result.build,
      Command.Idea(TestUtil.packageConfig),
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
    val (result, outputPath) = createProject("test-module")

    cli.Generate.ui(
      Config(),
      result.projectPath,
      outputPath,
      result.resolvers,
      result.build,
      Command.Idea(TestUtil.packageConfig),
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
    val (result, outputPath) = createProject("scala-native-module")

    cli.Generate.ui(
      Config(),
      result.projectPath,
      outputPath,
      result.resolvers,
      result.build,
      Command.Idea(TestUtil.packageConfig),
      Log.urgent
    )
  }

  test("Generate project with resource folder") {
    import pine._
    val (result, outputPath) = createProject("example-resources")

    cli.Generate.ui(
      Config(),
      result.projectPath,
      outputPath,
      result.resolvers,
      result.build,
      Command.Idea(TestUtil.packageConfig),
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

    def updateAttr[TagName <: Singleton with String](
      tag: Tag[TagName],
      attribute: String,
      f: String => String
    ): Tag[TagName] =
      if (!tag.hasAttr(attribute)) tag
      else tag.setAttr(attribute, f(tag.attr(attribute).get))

    val sourceFolders = exampleModule
      .byTagAll["sourceFolder"]
      .map(updateAttr(_, "url", v => v.drop(v.lastIndexOf('/'))))
    assertEquals(
      sourceFolders,
      List(
        xml"""<sourceFolder url="/src" isTestSource="false" />""",
        xml"""<sourceFolder url="/resources" type="java-resource" />"""
      )
    )
  }
}
