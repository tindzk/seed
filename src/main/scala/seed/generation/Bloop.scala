package seed.generation

import java.nio.file.{Files, Path, Paths}

import seed.config.BuildConfig.{
  Build,
  collectJsClassPath,
  collectJsDeps,
  collectJvmClassPath,
  collectJvmJavaDeps,
  collectJvmScalaDeps,
  collectNativeClassPath,
  collectNativeDeps
}
import seed.artefact.{ArtefactResolution, Coursier, SemanticVersioning}
import seed.cli.util.Ansi
import seed.model.Build.Module
import seed.model.Platform.{JVM, JavaScript, Native}
import seed.model.Resolution
import seed.Log
import seed.config.BuildConfig
import seed.generation.util.PathUtil

object Bloop {
  import bloop.config.Config

  def writeBloop(
    projectPath: Path,
    name: String,
    bloopPath: Path,
    dependencies: List[String],
    classesDir: Path,
    sources: List[Path],
    resources: List[Path] = List(),
    scalaCompiler: Option[Resolution.ScalaCompiler],
    scalaOptions: List[String],
    testFrameworks: List[String],
    platform: Option[Config.Platform]
  ): Unit = {
    require(sources.length == sources.distinct.length)

    val project = Config.Project(
      name = name,
      directory = projectPath.toAbsolutePath,
      sources = sources.map(_.toAbsolutePath),
      dependencies = dependencies,
      classpath = scalaCompiler.fold(List[Path]())(
        sc =>
          (sc.libraries.map(_.libraryJar) ++
            sc.classPath.map(_.toAbsolutePath)).sorted
      ),
      out = classesDir.toAbsolutePath,
      classesDir = classesDir.toAbsolutePath,
      `scala` = scalaCompiler.map(
        scalaCompiler =>
          bloop.config.Config.Scala(
            organization = scalaCompiler.scalaOrganisation,
            name = "scala-compiler",
            version = scalaCompiler.scalaVersion,
            options = scalaOptions,
            jars = scalaCompiler.compilerJars.sorted,
            analysis = Some(classesDir.resolve("analysis.bin").toAbsolutePath),
            setup = Some(
              Config.CompileSetup(
                order = Config.Mixed,
                addLibraryToBootClasspath = true,
                addCompilerToClasspath = false,
                addExtraJarsToClasspath = false,
                manageBootClasspath = true,
                filterLibraryFromClasspath = true
              )
            )
          )
      ),
      java = Some(Config.Java(options = List())),
      sbt = Some(Config.Sbt("", List())),
      test = Some(
        Config.Test(
          frameworks = testFrameworks
            .map(framework => Config.TestFramework(List(framework))),
          options = Config.TestOptions(excludes = List(), arguments = List())
        )
      ),
      platform = platform,
      resolution = Some(
        Config.Resolution(
          scalaCompiler.fold(List[Config.Module]())(
            sc =>
              sc.libraries.map { artefact =>
                val name = artefact.javaDep.artefact.takeWhile(_ != '_')
                Config.Module(
                  organization = artefact.javaDep.organisation,
                  name = name,
                  version = artefact.javaDep.version,
                  configurations = None,
                  artifacts = List(
                    Config.Artifact(
                      name = name,
                      classifier = None,
                      checksum = None,
                      path = artefact.libraryJar
                    )
                  ) ++
                    artefact.sourcesJar.toList.map { path =>
                      Config.Artifact(
                        name = name,
                        classifier = Some("sources"),
                        checksum = None,
                        path = path
                      )
                    } ++
                    artefact.javaDocJar.toList.map { path =>
                      Config.Artifact(
                        name = name,
                        classifier = Some("javadoc"),
                        checksum = None,
                        path = path
                      )
                    }
                )
              }
          )
        )
      ),
      resources = Some(resources.map(_.toAbsolutePath))
    )

    bloop.config.write(
      Config.File(Config.File.LatestVersion, project),
      bloopPath.resolve(name + ".json")
    )
  }

  def writeJsModule(
    build: Build,
    name: String,
    projectPath: Path,
    bloopPath: Path,
    buildPath: Path,
    jsOutputPath: Option[Path],
    module: Module,
    resolution: Coursier.ResolutionResult,
    compilerResolution: List[Coursier.ResolutionResult],
    test: Boolean,
    optionalArtefacts: Boolean,
    log: Log
  ): Unit =
    module.js.foreach { js =>
      val jsdom          = js.jsdom
      val emitSourceMaps = js.emitSourceMaps
      val mainClass      = js.mainClass

      val bloopName = if (!test) name else name + "-test"
      log.info(s"Writing JavaScript module ${Ansi.italic(bloopName)}...")

      val plugIns = util.ScalaCompiler.compilerPlugIns(
        build,
        js,
        compilerResolution,
        JavaScript,
        js.scalaVersion.get
      )

      val resolvedDeps = Coursier.localArtefacts(
        resolution,
        collectJsDeps(build, test, js)
          .map(
            dep =>
              ArtefactResolution.javaDepFromScalaDep(
                dep,
                JavaScript,
                js.scalaJsVersion.get,
                js.scalaVersion.get
              )
          )
          .toSet ++ ArtefactResolution.jsPlatformDeps(js),
        optionalArtefacts
      )
      val dependencies =
        if (test) List(name)
        else
          js.moduleDeps
            .filter(name => BuildConfig.hasTarget(build, name, JavaScript))
            .map(name => BuildConfig.targetName(build, name, JavaScript))

      val classesDir = buildPath.resolve(bloopName)
      val classPath =
        (if (test) List(buildPath.resolve(name)) else List()) ++
          collectJsClassPath(buildPath, build, js)

      val scalaCompiler = ArtefactResolution.resolveScalaCompiler(
        compilerResolution,
        js.scalaOrganisation.get,
        js.scalaVersion.get,
        resolvedDeps,
        classPath,
        optionalArtefacts
      )

      writeBloop(
        projectPath = projectPath,
        name = bloopName,
        bloopPath = bloopPath,
        dependencies = dependencies,
        classesDir = classesDir,
        sources = module.sources ++ js.sources,
        scalaCompiler = Some(scalaCompiler),
        scalaOptions = js.scalaOptions ++ plugIns,
        testFrameworks = if (test) js.testFrameworks else List(),
        platform = Some(
          Config.Platform.Js(
            Config.JsConfig(
              version =
                SemanticVersioning.majorMinorVersion(js.scalaJsVersion.get),
              mode = Config.LinkerMode.Debug,
              kind = Config.ModuleKindJS.NoModule,
              emitSourceMaps = emitSourceMaps,
              jsdom = Some(jsdom),
              output = jsOutputPath,
              nodePath = None,
              toolchain = List()
            ),
            mainClass = mainClass
          )
        )
      )
    }

  def writeNativeModule(
    build: Build,
    name: String,
    projectPath: Path,
    bloopPath: Path,
    buildPath: Path,
    outputPathBinary: Option[Path],
    module: Module,
    resolution: Coursier.ResolutionResult,
    compilerResolution: List[Coursier.ResolutionResult],
    test: Boolean,
    optionalArtefacts: Boolean,
    log: Log
  ): Unit =
    module.native.foreach { native =>
      val mainClass       = native.mainClass
      val gc              = native.gc.getOrElse("immix")
      val targetTriple    = native.targetTriple.getOrElse("")
      val clang           = native.clang.getOrElse(Paths.get("/usr/bin/clang"))
      val clangpp         = native.clangpp.getOrElse(Paths.get("/usr/bin/clang++"))
      val linkStubs       = native.linkStubs
      val linkerOptions   = native.linkerOptions.getOrElse(List())
      val compilerOptions = native.compilerOptions.getOrElse(List())

      val bloopName = if (!test) name else name + "-test"
      log.info(s"Writing native module ${Ansi.italic(bloopName)}...")

      val plugIns = util.ScalaCompiler.compilerPlugIns(
        build,
        native,
        compilerResolution,
        Native,
        native.scalaVersion.get
      )

      val resolvedDeps =
        Coursier.localArtefacts(
          resolution,
          collectNativeDeps(build, test, native)
            .map(
              dep =>
                ArtefactResolution.javaDepFromScalaDep(
                  dep,
                  Native,
                  native.scalaNativeVersion.get,
                  native.scalaVersion.get
                )
            )
            .toSet ++ ArtefactResolution.nativePlatformDeps(native),
          optionalArtefacts
        )

      val nativeLibDep = ArtefactResolution.nativeLibraryDep(native)
      val scalaNativelib = resolvedDeps
        .find(_.javaDep == nativeLibDep)
        .map(_.libraryJar)
        .get

      val dependencies =
        if (test) List(name)
        else
          native.moduleDeps
            .filter(name => BuildConfig.hasTarget(build, name, Native))
            .map(name => BuildConfig.targetName(build, name, Native))

      val classesDir = buildPath.resolve(bloopName)
      val classPath =
        (if (test) List(buildPath.resolve(name)) else List()) ++
          collectNativeClassPath(buildPath, build, native)

      val scalaCompiler = ArtefactResolution.resolveScalaCompiler(
        compilerResolution,
        native.scalaOrganisation.get,
        native.scalaVersion.get,
        resolvedDeps,
        classPath,
        optionalArtefacts
      )

      writeBloop(
        projectPath = projectPath,
        name = bloopName,
        bloopPath = bloopPath,
        dependencies = dependencies,
        classesDir = classesDir,
        sources = module.sources ++ native.sources,
        scalaCompiler = Some(scalaCompiler),
        scalaOptions = native.scalaOptions ++ plugIns,
        testFrameworks = if (test) native.testFrameworks else List(),
        platform = Some(
          Config.Platform.Native(
            Config.NativeConfig(
              version = native.scalaNativeVersion.get,
              mode = Config.LinkerMode.Debug,
              gc = gc,
              targetTriple = targetTriple,
              nativelib = scalaNativelib,
              clang = clang,
              clangpp = clangpp,
              toolchain = List(),
              options = Config.NativeOptions(
                linker = linkerOptions,
                compiler = compilerOptions
              ),
              linkStubs = linkStubs,
              output = outputPathBinary
            ),
            mainClass = mainClass
          )
        )
      )
    }

  def writeJvmModule(
    build: Build,
    name: String,
    projectPath: Path,
    bloopPath: Path,
    buildPath: Path,
    module: Module,
    resolution: Coursier.ResolutionResult,
    compilerResolution: List[Coursier.ResolutionResult],
    test: Boolean,
    optionalArtefacts: Boolean,
    log: Log
  ): Unit =
    module.jvm.foreach { jvm =>
      val bloopName = if (!test) name else name + "-test"
      log.info(s"Writing JVM module ${Ansi.italic(bloopName)}...")

      val scalaVersion = jvm.scalaVersion.get
      val javaDeps     = collectJvmJavaDeps(build, test, jvm)
      val scalaDeps = collectJvmScalaDeps(build, test, jvm).map(
        dep =>
          ArtefactResolution
            .javaDepFromScalaDep(dep, JVM, scalaVersion, scalaVersion)
      )

      val resolvedDeps = Coursier.localArtefacts(
        resolution,
        (javaDeps ++ scalaDeps).toSet,
        optionalArtefacts
      )

      val plugIns = util.ScalaCompiler.compilerPlugIns(
        build,
        jvm,
        compilerResolution,
        JVM,
        scalaVersion
      )

      val dependencies =
        if (test) List(name)
        else
          jvm.moduleDeps
            .filter(name => BuildConfig.hasTarget(build, name, JVM))
            .map(name => BuildConfig.targetName(build, name, JVM))

      val classesDir = buildPath.resolve(bloopName)
      val classPath =
        (if (test) List(buildPath.resolve(name)) else List()) ++
          collectJvmClassPath(buildPath, build, jvm)

      val scalaCompiler = ArtefactResolution.resolveScalaCompiler(
        compilerResolution,
        jvm.scalaOrganisation.get,
        scalaVersion,
        resolvedDeps,
        classPath,
        optionalArtefacts
      )

      writeBloop(
        projectPath = projectPath,
        name = bloopName,
        bloopPath = bloopPath,
        dependencies = dependencies,
        classesDir = classesDir,
        sources = module.sources ++ jvm.sources,
        resources = jvm.resources,
        scalaCompiler = Some(scalaCompiler),
        scalaOptions = jvm.scalaOptions ++ plugIns,
        testFrameworks = if (test) jvm.testFrameworks else List(),
        platform = Some(
          Config.Platform
            .Jvm(Config.JvmConfig(None, List()), mainClass = jvm.mainClass)
        )
      )
    }

  def moduleOutputPath(
    buildPath: Path,
    module: Module,
    defaultName: String
  ): Path =
    module.output match {
      case Some(p) if Paths.get(p).isAbsolute => Paths.get(p)
      case Some(p) =>
        val base = buildPath.toAbsolutePath.resolve(p).normalize()
        if (!p.endsWith("/")) base else base.resolve(defaultName)
      case None => buildPath.toAbsolutePath.resolve(defaultName)
    }

  def buildModule(
    projectPath: Path,
    bloopPath: Path,
    buildPath: Path,
    bloopBuildPath: Path,
    build: Build,
    resolution: Coursier.ResolutionResult,
    compilerResolution: List[Coursier.ResolutionResult],
    name: String,
    module: Module,
    optionalArtefacts: Boolean,
    log: Log
  ): Unit = {
    val isCrossBuild = module.targets.toSet.size > 1

    val jsOutputPath =
      module.js.map(js => moduleOutputPath(buildPath, js, name + ".js"))
    val nativeOutputPath = module.native.map(
      native => moduleOutputPath(buildPath, native, name + ".run")
    )

    jsOutputPath.foreach { path =>
      if (!Files.exists(path.getParent)) Files.createDirectories(path.getParent)
    }

    nativeOutputPath.foreach { path =>
      if (!Files.exists(path.getParent)) Files.createDirectories(path.getParent)
    }

    writeJsModule(
      build,
      if (!isCrossBuild) name else name + "-js",
      projectPath,
      bloopPath,
      bloopBuildPath,
      jsOutputPath,
      module,
      resolution,
      compilerResolution,
      test = false,
      optionalArtefacts,
      log
    )

    writeJvmModule(
      build,
      if (!isCrossBuild) name else name + "-jvm",
      projectPath,
      bloopPath,
      bloopBuildPath,
      module,
      resolution,
      compilerResolution,
      test = false,
      optionalArtefacts,
      log
    )

    writeNativeModule(
      build,
      if (!isCrossBuild) name else name + "-native",
      projectPath,
      bloopPath,
      bloopBuildPath,
      nativeOutputPath,
      module,
      resolution,
      compilerResolution,
      test = false,
      optionalArtefacts,
      log
    )

    if (isCrossBuild)
      writeBloop(
        projectPath = projectPath,
        name = name,
        bloopPath = bloopPath,
        dependencies = module.targets.map(t => name + "-" + t.id),
        classesDir = bloopBuildPath,
        sources = List(),
        scalaCompiler = None,
        scalaOptions = List(),
        testFrameworks = List(),
        platform = None
      )

    writeJsModule(
      build,
      if (!isCrossBuild) name else name + "-js",
      projectPath,
      bloopPath,
      bloopBuildPath,
      None,
      BuildConfig.mergeTestModule(build, module, JavaScript),
      resolution,
      compilerResolution,
      test = true,
      optionalArtefacts,
      log
    )

    writeNativeModule(
      build,
      if (!isCrossBuild) name else name + "-native",
      projectPath,
      bloopPath,
      bloopBuildPath,
      None,
      BuildConfig.mergeTestModule(build, module, Native),
      resolution,
      compilerResolution,
      test = true,
      optionalArtefacts,
      log
    )

    writeJvmModule(
      build,
      if (!isCrossBuild) name else name + "-jvm",
      projectPath,
      bloopPath,
      bloopBuildPath,
      BuildConfig.mergeTestModule(build, module, JVM),
      resolution,
      compilerResolution,
      test = true,
      optionalArtefacts,
      log
    )

    module.test.foreach(
      test =>
        if (isCrossBuild)
          writeBloop(
            projectPath = projectPath,
            name = name + "-test",
            bloopPath = bloopPath,
            dependencies = test.targets.map(t => name + "-" + t.id + "-test"),
            classesDir = bloopBuildPath,
            sources = List(),
            scalaCompiler = None,
            scalaOptions = List(),
            testFrameworks = List(),
            platform = None
          )
    )
  }

  def build(
    projectPath: Path,
    outputPath: Path,
    build: Build,
    resolution: Coursier.ResolutionResult,
    compilerResolution: List[Coursier.ResolutionResult],
    tmpfs: Boolean,
    optionalArtefacts: Boolean,
    log: Log
  ): Unit = {
    val bloopPath = outputPath.resolve(".bloop")
    if (!Files.exists(bloopPath)) Files.createDirectory(bloopPath)

    val buildPath = PathUtil.buildPath(outputPath, tmpfs, log)
    log.info(s"Build path: ${Ansi.italic(buildPath.toString)}")

    val bloopBuildPath = buildPath.resolve("bloop")

    import scala.collection.JavaConverters._
    Files
      .newDirectoryStream(bloopPath, "*.json")
      .iterator()
      .asScala
      .foreach(Files.delete)

    build.foreach {
      case (name, module) =>
        log.info(s"Building module ${Ansi.italic(name)}...")
        buildModule(
          projectPath,
          bloopPath,
          buildPath,
          bloopBuildPath,
          build,
          resolution,
          compilerResolution,
          name,
          module.module,
          optionalArtefacts,
          log
        )
    }

    log.info("Bloop project has been created")
  }
}
