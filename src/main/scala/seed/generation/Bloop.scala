package seed.generation

import java.nio.file.{Files, Path, Paths}

import seed.config.BuildConfig.{collectJsClassPath, collectJsDeps, collectJvmClassPath, collectJvmJavaDeps, collectJvmScalaDeps, collectNativeClassPath, collectNativeDeps}
import seed.artefact.{ArtefactResolution, Coursier}
import seed.cli.util.Ansi
import seed.model.Build.{Module, Project}
import seed.model.Platform.{JVM, JavaScript, Native}
import seed.model.{Artefact, Build, Resolution}
import seed.Log
import seed.config.BuildConfig
import seed.generation.util.PathUtil

object Bloop {
  import bloop.config.Config

  def majorMinorVersion(version: String): String =
    version.reverse.dropWhile(_ != '.').tail.reverse

  def writeBloop(projectPath: Path,
                 name: String,
                 bloopPath: Path,
                 dependencies: List[String],
                 classesDir: Path,
                 classPath: List[Path],
                 sources: List[Path],
                 resources: List[Path] = List(),
                 scalaCompiler: Option[Resolution.ScalaCompiler],
                 scalaOptions: List[String],
                 testFrameworks: List[String],
                 platform: Option[Config.Platform]): Unit = {
    val project = Config.Project(
      name = name,
      directory = projectPath.toAbsolutePath,
      sources = sources.map(_.toAbsolutePath),
      dependencies = dependencies,
      classpath = scalaCompiler.fold(List[Path]())(_.fullClassPath.map(_.toAbsolutePath).sorted),
      out = classesDir.toAbsolutePath,
      classesDir = classesDir.toAbsolutePath,
      `scala` = scalaCompiler.map(scalaCompiler =>
        bloop.config.Config.Scala(
          organization = scalaCompiler.scalaOrganisation,
          name = "scala-compiler",
          version = scalaCompiler.scalaVersion,
          options = scalaOptions,
          jars = scalaCompiler.compilerJars.sorted,
          analysis = Some(classesDir.resolve("analysis.bin").toAbsolutePath),
          setup = Some(Config.CompileSetup(
            order = Config.Mixed,
            addLibraryToBootClasspath = true,
            addCompilerToClasspath = false,
            addExtraJarsToClasspath = false,
            manageBootClasspath = true,
            filterLibraryFromClasspath = true
          ))
        )
      ),
      java = Some(Config.Java(options = List())),
      sbt = Some(Config.Sbt("", List())),
      test = Some(Config.Test(
        frameworks = testFrameworks.map(framework =>
          Config.TestFramework(List(framework))
        ),
        options = Config.TestOptions(excludes = List(), arguments = List())
      )),
      platform = platform,
      resolution = Some(Config.Resolution(List())),
      resources = Some(resources.map(_.toAbsolutePath)))

    bloop.config.write(
      Config.File(Config.File.LatestVersion, project),
      bloopPath.resolve(name + ".json"))
  }

  def writeJsModule(build: Build,
                    name: String,
                    projectPath: Path,
                    bloopPath: Path,
                    buildPath: Path,
                    jsOutputPath: Option[Path],
                    parentModule: Module,
                    parentClassPaths: List[Path],
                    jsModule: Option[Module],
                    project: Project,
                    resolution: Coursier.ResolutionResult,
                    compilerResolution: List[Coursier.ResolutionResult],
                    jsdom: Boolean,
                    emitSourceMaps: Boolean,
                    test: Boolean,
                    log: Log
                   ): Unit = {
    import parentModule.{moduleDeps, scalaDeps, sources, targets}
    import project.{scalaJsVersion, scalaOptions, scalaOrganisation, testFrameworks}

    val mainClass = jsModule.flatMap(_.mainClass)
      .orElse(parentModule.mainClass)

    jsModule
      .orElse(if (!targets.contains(JavaScript)) None else Some(Module()))
      .foreach
      { js =>
        val bloopName = if (!test) name else name + "-test"
        log.info(s"Writing JavaScript module ${Ansi.italic(bloopName)}...")

        val scalaVersion = BuildConfig.scalaVersion(project,
          List(js, parentModule.js.getOrElse(Module()), parentModule))

        def resolveLibrary(artefact: Artefact): Path =
          Coursier.artefactPath(resolution, artefact, JavaScript,
            scalaJsVersion.get, scalaVersion, scalaJsVersion.get).get

        val plugIns = util.ScalaCompiler.compilerPlugIns(build,
          parentModule, compilerResolution, JavaScript, scalaVersion)

        val resolvedDeps = Coursier.localArtefacts(
          resolution,
          (scalaDeps ++ js.scalaDeps).map(dep =>
            ArtefactResolution.javaDepFromScalaDep(
              dep, JavaScript, scalaJsVersion.get, scalaVersion)
          ).toSet)
        val dependencies =
          if (test) List(name)
          else (moduleDeps ++ js.moduleDeps)
            .filter(name => BuildConfig.hasTarget(build, name, JavaScript))
            .map(name => BuildConfig.targetName(build, name, JavaScript))

        val classesDir = buildPath.resolve(bloopName)
        val classPath  = resolvedDeps.map(_.libraryJar) ++
          (if (test) List(buildPath.resolve(name))
           else List()
          ) ++ parentClassPaths ++ List(resolveLibrary(Artefact.ScalaJsLibrary))

        val scalaCompiler = ArtefactResolution.resolveScalaCompiler(
          compilerResolution, scalaOrganisation, scalaVersion, classPath)

        writeBloop(
          projectPath = projectPath,
          name = bloopName,
          bloopPath = bloopPath,
          dependencies = dependencies,
          classesDir = classesDir,
          classPath = classPath,
          sources = sources ++ js.sources,
          scalaCompiler = Some(scalaCompiler),
          scalaOptions = scalaOptions ++ plugIns,
          testFrameworks = if (test) testFrameworks else List(),
          platform = Some(Config.Platform.Js(
            Config.JsConfig(
              version = majorMinorVersion(scalaJsVersion.get),
              mode = Config.LinkerMode.Debug,
              kind = Config.ModuleKindJS.NoModule,
              emitSourceMaps = emitSourceMaps,
              jsdom = Some(jsdom),
              output = jsOutputPath,
              nodePath = None,
              toolchain = List()
            ),
            mainClass = mainClass))
        )
      }
  }

  def writeNativeModule(build: Build,
                        name: String,
                        projectPath: Path,
                        bloopPath: Path,
                        buildPath: Path,
                        outputPathBinary: Option[Path],
                        parentModule: Module,
                        parentClassPaths: List[Path],
                        nativeModule: Option[Module],
                        project: Project,
                        resolution: Coursier.ResolutionResult,
                        compilerResolution: List[Coursier.ResolutionResult],
                        test: Boolean,
                        log: Log
                       ): Unit = {
    import parentModule.{moduleDeps, scalaDeps, sources, targets}
    import project.{scalaNativeVersion, scalaOptions, scalaOrganisation, testFrameworks}

    val mainClass = nativeModule.flatMap(_.mainClass)
      .orElse(parentModule.mainClass)

    val gc = nativeModule.flatMap(_.gc)
      .orElse(parentModule.gc)
      .getOrElse("immix")
    val targetTriple = nativeModule.flatMap(_.targetTriple)
      .orElse(parentModule.targetTriple)
      .getOrElse("")
    val clang = nativeModule.flatMap(_.clang)
      .orElse(parentModule.clang)
      .getOrElse(Paths.get("/usr/bin/clang"))
    val clangpp = nativeModule.flatMap(_.clangpp)
      .orElse(parentModule.clangpp)
      .getOrElse(Paths.get("/usr/bin/clang++"))
    val linkStubs = nativeModule.exists(_.linkStubs) || parentModule.linkStubs
    val linkerOptions = nativeModule.flatMap(_.linkerOptions)
      .orElse(parentModule.linkerOptions)
      .getOrElse(List())
    val compilerOptions = nativeModule.flatMap(_.compilerOptions)
      .orElse(parentModule.compilerOptions)
      .getOrElse(List())

    nativeModule
      .orElse(if (!targets.contains(Native)) None else Some(Module()))
      .foreach
      { native =>
        val bloopName = if (!test) name else name + "-test"
        log.info(s"Writing native module ${Ansi.italic(bloopName)}...")

        val scalaVersion = BuildConfig.scalaVersion(project,
          List(native, parentModule.native.getOrElse(Module()), parentModule))

        val scalaNativeArtefacts = Set(
          Artefact.ScalaNativeJavalib,
          Artefact.ScalaNativeScalalib,
          Artefact.ScalaNativeNativelib,
          Artefact.ScalaNativeAuxlib
        ).map(a =>
          a -> Coursier.artefactPath(resolution, a, Native,
            scalaNativeVersion.get, scalaVersion, scalaNativeVersion.get).get
        ).toMap

        val plugIns = util.ScalaCompiler.compilerPlugIns(build,
          parentModule, compilerResolution, Native, scalaVersion)

        val resolvedDeps =
          Coursier.localArtefacts(resolution,
            (scalaDeps ++ native.scalaDeps).map(dep =>
              ArtefactResolution.javaDepFromScalaDep(dep, Native,
                scalaNativeVersion.get, scalaVersion)
            ).toSet)

        val dependencies =
          if (test) List(name)
          else (moduleDeps ++ native.moduleDeps)
            .filter(name => BuildConfig.hasTarget(build, name, Native))
            .map(name => BuildConfig.targetName(build, name, Native))

        val classesDir = buildPath.resolve(bloopName)
        val classPath  = resolvedDeps.map(_.libraryJar) ++
                           (if (test) List(buildPath.resolve(name))
                            else List()
                           ) ++ parentClassPaths ++
                           List(
                             Artefact.ScalaNativeJavalib,
                             Artefact.ScalaNativeScalalib,
                             Artefact.ScalaNativeNativelib,
                             Artefact.ScalaNativeAuxlib
                           ).map(scalaNativeArtefacts)

        val scalaCompiler = ArtefactResolution.resolveScalaCompiler(
          compilerResolution, scalaOrganisation, scalaVersion, classPath)

        writeBloop(
          projectPath = projectPath,
          name = bloopName,
          bloopPath = bloopPath,
          dependencies = dependencies,
          classesDir = classesDir,
          classPath = classPath,
          sources = sources ++ native.sources,
          scalaCompiler = Some(scalaCompiler),
          scalaOptions = scalaOptions ++ plugIns,
          testFrameworks = if (test) testFrameworks else List(),
          platform = Some(Config.Platform.Native(Config.NativeConfig(
            version = scalaNativeVersion.get,
            mode = Config.LinkerMode.Debug,
            gc = gc,
            targetTriple = targetTriple,
            nativelib = scalaNativeArtefacts(Artefact.ScalaNativeNativelib),
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
          mainClass = mainClass))
        )
      }
  }

  def writeJvmModule(build: Build,
                     name: String,
                     projectPath: Path,
                     bloopPath: Path,
                     buildPath: Path,
                     parentModule: Module,
                     parentClassPaths: List[Path],
                     jvmModule: Option[Module],
                     project: Project,
                     resolution: Coursier.ResolutionResult,
                     compilerResolution: List[Coursier.ResolutionResult],
                     test: Boolean,
                     log: Log
                    ): Unit = {
    import parentModule.{moduleDeps, sources, targets}
    import project.{scalaOptions, scalaOrganisation, testFrameworks}

    val mainClass = jvmModule.flatMap(_.mainClass)
      .orElse(parentModule.mainClass)

    jvmModule
      .orElse(if (!targets.contains(JVM)) None else Some(Module()))
      .foreach
      { jvm =>
        val bloopName = if (!test) name else name + "-test"
        log.info(s"Writing JVM module ${Ansi.italic(bloopName)}...")

        val scalaVersion = BuildConfig.scalaVersion(project,
          List(jvm, parentModule.jvm.getOrElse(Module()), parentModule))

        val javaDeps = parentModule.javaDeps ++ jvm.javaDeps
        val scalaDeps = (parentModule.scalaDeps ++ jvm.scalaDeps).map(dep =>
          ArtefactResolution.javaDepFromScalaDep(dep, JVM, scalaVersion,
            scalaVersion))
        val resolvedDeps = Coursier.localArtefacts(resolution,
          (javaDeps ++ scalaDeps).toSet)

        val plugIns = util.ScalaCompiler.compilerPlugIns(build,
          parentModule, compilerResolution, JVM, scalaVersion)

        val dependencies =
          if (test) List(name)
          else (moduleDeps ++ jvm.moduleDeps)
            .filter(name => BuildConfig.hasTarget(build, name, JVM))
            .map(name => BuildConfig.targetName(build, name, JVM))

        val classesDir = buildPath.resolve(bloopName)
        val classPath  = resolvedDeps.map(_.libraryJar) ++
                        (if (test)
                          List(buildPath.resolve(name))
                         else List()) ++ parentClassPaths

        val scalaCompiler = ArtefactResolution.resolveScalaCompiler(
          compilerResolution, scalaOrganisation, scalaVersion, classPath)

        writeBloop(
          projectPath = projectPath,
          name = bloopName,
          bloopPath = bloopPath,
          dependencies = dependencies,
          classesDir = classesDir,
          classPath = classPath,
          sources = sources ++ jvm.sources,
          resources = jvm.resources,
          scalaCompiler = Some(scalaCompiler),
          scalaOptions = scalaOptions ++ plugIns,
          testFrameworks = if (test) testFrameworks else List(),
          platform = Some(Config.Platform.Jvm(
            Config.JvmConfig(None, List()),
            mainClass = mainClass)))
      }
  }

  def moduleOutputPath(buildPath: Path,
                       module: Option[Module],
                       defaultName: String): Path =
    module.flatMap(_.output) match {
      case Some(p) if p.isAbsolute => p
      case Some(p) => buildPath.toAbsolutePath.resolve(p).normalize()
      case None => buildPath.toAbsolutePath.resolve(defaultName)
    }

  def buildModule(projectPath: Path,
                  bloopPath: Path,
                  buildPath: Path,
                  bloopBuildPath: Path,
                  build: Build,
                  resolution: Coursier.ResolutionResult,
                  compilerResolution: List[Coursier.ResolutionResult],
                  name: String,
                  module: Module,
                  log: Log
                 ): Unit = {
    val isCrossBuild = module.targets.toSet.size > 1

    val jsOutputPath = moduleOutputPath(buildPath, module.js, name + ".js")
    val nativeOutputPath =
      moduleOutputPath(buildPath, module.native, name + ".run")

    writeJsModule(build, if (!isCrossBuild) name else name + "-js",
      projectPath, bloopPath, bloopBuildPath, Some(jsOutputPath),
      module.copy(scalaDeps = collectJsDeps(build, module)),
      collectJsClassPath(bloopBuildPath, build, module),
      module.js, build.project, resolution, compilerResolution,
      jsdom = module.js.exists(_.jsdom),
      emitSourceMaps = module.js.exists(_.emitSourceMaps),
      test = false, log)
    writeJvmModule(build, if (!isCrossBuild) name else name + "-jvm",
      projectPath, bloopPath, bloopBuildPath,
      module.copy(
        scalaDeps = collectJvmScalaDeps(build, module),
        javaDeps = collectJvmJavaDeps(build, module)
      ),
      collectJvmClassPath(bloopBuildPath, build, module),
      module.jvm, build.project, resolution, compilerResolution, test = false,
      log)
    writeNativeModule(build, if (!isCrossBuild) name else name + "-native",
      projectPath, bloopPath, bloopBuildPath, Some(nativeOutputPath),
      module.copy(scalaDeps = collectNativeDeps(build, module)),
      collectJvmClassPath(bloopBuildPath, build, module),
      module.native, build.project, resolution, compilerResolution, test = false,
      log)

    module.test.foreach { test =>
      val targets = if (test.targets.nonEmpty) test.targets else module.targets
      val jsdom = test.js.exists(_.jsdom)
      val emitSourceMaps = test.js.exists(_.emitSourceMaps)

      writeJsModule(build, if (!isCrossBuild) name else name + "-js",
        projectPath, bloopPath, bloopBuildPath, None,
        module.copy(
          sources = test.sources,
          scalaDeps = collectJsDeps(build, module) ++ test.scalaDeps,
          targets = targets
        ),
        collectJsClassPath(bloopBuildPath, build, module),
        test.js, build.project, resolution, compilerResolution, jsdom,
        emitSourceMaps, test = true, log)

      writeNativeModule(build, if (!isCrossBuild) name else name + "-native",
        projectPath, bloopPath, bloopBuildPath, None,
        module.copy(
          sources = test.sources,
          scalaDeps = collectNativeDeps(build, module) ++ test.scalaDeps,
          targets = targets
        ),
        collectNativeClassPath(bloopBuildPath, build, module),
        test.native, build.project, resolution, compilerResolution, test = true,
        log)

      writeJvmModule(build, if (!isCrossBuild) name else name + "-jvm",
        projectPath, bloopPath, bloopBuildPath,
        module.copy(
          sources = test.sources,
          scalaDeps = collectJvmScalaDeps(build, module) ++ test.scalaDeps,
          javaDeps = collectJvmJavaDeps(build, module) ++ test.javaDeps,
          targets = targets
        ),
        collectJvmClassPath(bloopBuildPath, build, module),
        test.jvm, build.project, resolution, compilerResolution, test = true,
        log)

      if (isCrossBuild)
        writeBloop(
          projectPath = projectPath,
          name = name + "-test",
          bloopPath = bloopPath,
          dependencies = targets.map(t => name + "-" + t.id + "-test"),
          classesDir = bloopBuildPath,
          classPath = List(),
          sources = List(),
          scalaCompiler = None,
          scalaOptions = List(),
          testFrameworks = List(),
          platform = None)
    }

    if (isCrossBuild)
      writeBloop(
        projectPath = projectPath,
        name = name,
        bloopPath = bloopPath,
        dependencies = module.targets.map(t => name + "-" + t.id),
        classesDir = bloopBuildPath,
        classPath = List(),
        sources = List(),
        scalaCompiler = None,
        scalaOptions = List(),
        testFrameworks = List(),
        platform = None)
  }

  def build(projectPath: Path,
            build: Build,
            resolution: Coursier.ResolutionResult,
            compilerResolution: List[Coursier.ResolutionResult],
            tmpfs: Boolean,
            log: Log): Unit = {
    val bloopPath = projectPath.resolve(".bloop")
    val buildPath = PathUtil.buildPath(projectPath, tmpfs, log)
    val bloopBuildPath = buildPath.resolve("bloop")

    log.info(s"Build path: ${Ansi.italic(buildPath.toString)}")

    if (!Files.exists(bloopPath)) Files.createDirectory(bloopPath)
    if (!Files.exists(bloopBuildPath)) Files.createDirectories(bloopBuildPath)

    import scala.collection.JavaConverters._
    Files.newDirectoryStream(bloopPath, "*.json").iterator().asScala
      .foreach(Files.delete)

    build.module.foreach { case (name, module) =>
      log.info(s"Building module ${Ansi.italic(name)}...")
      buildModule(projectPath, bloopPath, buildPath, bloopBuildPath, build,
        resolution, compilerResolution, name, module, log)
    }

    log.info("Bloop project has been created")
  }
}
