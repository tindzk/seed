package seed.generation

import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import org.apache.commons.io.FileUtils
import seed.config.BuildConfig.{
  Build,
  collectJsDeps,
  collectJsModuleDeps,
  collectJvmJavaDeps,
  collectJvmModuleDeps,
  collectJvmScalaDeps,
  collectNativeDeps,
  collectNativeModuleDeps
}
import seed.artefact.{ArtefactResolution, Coursier}
import seed.cli.util.Ansi
import seed.generation.util.{IdeaFile, PathUtil}
import seed.model.Resolution
import seed.model.Build.Module
import seed.model.Platform.{JVM, JavaScript, Native}
import seed.Log
import seed.config.BuildConfig
import seed.generation.util.PathUtil.normalisePath

object Idea {
  val ModuleDir  = "$MODULE_DIR$"
  val ProjectDir = "$PROJECT_DIR$"

  /** Replace non alpha-numerical characters, otherwise IntelliJ will rename
    * such files.
    */
  def ideaName(str: String): String =
    str.map(c => if (c.isLetterOrDigit) c else '_')

  def createLibrary(
    librariesPath: Path,
    libraryJar: Path,
    javaDocJar: Option[Path],
    sourcesJar: Option[Path]
  ): Unit = {
    val xml = IdeaFile.createLibrary(
      IdeaFile.Library(
        libraryJar.getFileName.toString,
        compilerInfo = None,
        classes = List(libraryJar.toAbsolutePath.toString),
        javaDoc = javaDocJar.toList.map(_.toAbsolutePath.toString),
        sources = sourcesJar.toList.map(_.toAbsolutePath.toString)
      )
    )

    FileUtils.write(
      librariesPath
        .resolve(ideaName(libraryJar.getFileName.toString) + ".xml")
        .toFile,
      xml,
      "UTF-8"
    )
  }

  def createModule(
    root: Path,
    name: String,
    sources: List[Path],
    tests: List[Path],
    resolvedDeps: List[Resolution.Artefact],
    resolvedTestDeps: List[Resolution.Artefact],
    moduleDeps: List[String],
    projectPath: Path,
    buildPath: Path,
    modulesPath: Path,
    librariesPath: Path,
    scalaOrganisation: String,
    scalaVersion: String
  ): Unit = {
    val filteredResolvedDeps =
      resolvedDeps.filter(l => !ArtefactResolution.isScalaLibrary(l.javaDep))
    val filteredResolvedTestDeps = resolvedTestDeps.filter(
      l => !ArtefactResolution.isScalaLibrary(l.javaDep)
    )

    (filteredResolvedDeps ++ filteredResolvedTestDeps).foreach(
      dep =>
        createLibrary(
          librariesPath,
          dep.libraryJar,
          dep.javaDocJar,
          dep.sourcesJar
        )
    )

    val scalaDep = scalaOrganisation + "-" + scalaVersion

    val classPathOut     = buildPath.resolve(name).resolve("main")
    val testClassPathOut = buildPath.resolve(name).resolve("test")

    if (!Files.exists(classPathOut)) Files.createDirectories(classPathOut)
    if (!Files.exists(testClassPathOut))
      Files.createDirectories(testClassPathOut)

    val xml = IdeaFile.createModule(
      IdeaFile.Module(
        projectId = name,
        rootPath = normalisePath(ModuleDir, modulesPath)(root),
        sourcePaths = sources.map(normalisePath(ModuleDir, modulesPath)),
        testPaths = tests.map(normalisePath(ModuleDir, modulesPath)),
        libraries = List(scalaDep) ++
          filteredResolvedDeps.map(_.libraryJar.getFileName.toString),
        testLibraries =
          filteredResolvedTestDeps.map(_.libraryJar.getFileName.toString),
        moduleDeps = moduleDeps,
        output = Some(
          IdeaFile.Output(
            classPath = normalisePath(ModuleDir, modulesPath)(classPathOut),
            testClassPath =
              normalisePath(ModuleDir, modulesPath)(testClassPathOut)
          )
        )
      )
    )

    FileUtils.write(modulesPath.resolve(name + ".iml").toFile, xml, "UTF-8")
  }

  def createCompilerLibraries(
    modules: Build,
    resolution: List[Coursier.ResolutionResult],
    librariesPath: Path
  ): Unit = {
    val scalaVersions = modules.values.toList
      .map(_.module)
      .flatMap(
        module =>
          (module.jvm.toList ++ module.js.toList ++ module.native.toList)
            .map(s => s.scalaOrganisation.get -> s.scalaVersion.get)
      )
      .distinct

    scalaVersions.foreach {
      case (scalaOrganisation, scalaVersion) =>
        val scalaCompiler = ArtefactResolution.resolveScalaCompiler(
          resolution,
          scalaOrganisation,
          scalaVersion,
          List(),
          List(),
          optionalArtefacts = false
        )

        val xml = IdeaFile.createLibrary(
          IdeaFile.Library(
            name = scalaOrganisation + "-" + scalaVersion,
            compilerInfo = Some(
              IdeaFile.CompilerInfo(
                scalaVersion,
                scalaCompiler.compilerJars.map(_.toString)
              )
            ),
            classes = scalaCompiler.libraries.map(_.libraryJar.toString),
            javaDoc = List(),
            sources = List()
          )
        )

        val fileName = ideaName(scalaOrganisation) + "_" + ideaName(
          scalaVersion
        ) + ".xml"
        FileUtils.write(librariesPath.resolve(fileName).toFile, xml, "UTF-8")
    }
  }

  def createCompilerSettings(
    build: Build,
    compilerResolution: List[Coursier.ResolutionResult],
    ideaPath: Path,
    modules: List[String]
  ): Unit = {
    // Group all modules by additional settings; create compiler configuration
    // for each unique set of parameters
    val modulePlugIns = modules.filter(build.contains).map { m =>
      val module         = build(m).module
      val target         = if (module.jvm.isDefined) JVM else module.targets.head
      val platformModule = BuildConfig.platformModule(module, target).get

      m -> (platformModule.scalaOptions ++ util.ScalaCompiler.compilerPlugIns(
        build,
        platformModule,
        compilerResolution,
        target,
        platformModule.scalaVersion.get
      ))
    }
    val compilerSettings =
      modulePlugIns.groupBy(_._2).mapValues(_.map(_._1)).toList.map {
        case (settings, modules) =>
          val allModules = modules.flatMap { module =>
            val targets = build(module).module.targets
            val all = module +: targets
              .map(target => BuildConfig.targetName(build, module, target))
            all.distinct
          }

          (settings, allModules)
      }

    val xml = IdeaFile.createScalaCompiler(compilerSettings)
    FileUtils.write(ideaPath.resolve("scala_compiler.xml").toFile, xml, "UTF-8")
  }

  /**
    * For each target with at least one source path, a separate IDEA module will
    * be created.
    *
    *  @note Since the tests cannot run with JavaScript in IntelliJ, the shared
    *       project will use JVM.
    */
  def buildModule(
    build: Build,
    projectPath: Path,
    buildPath: Path,
    ideaPath: Path,
    modulesPath: Path,
    librariesPath: Path,
    compilerResolution: List[Coursier.ResolutionResult],
    resolution: Coursier.ResolutionResult,
    name: String,
    module: Module,
    log: Log
  ): List[String] = {
    val isCrossBuild = module.targets.toSet.size > 1

    val jsModule  = module.js.getOrElse(Module())
    val jsSources = jsModule.sources
    val jsTests   = module.test.toList.flatMap(_.js.toList.flatMap(_.sources))
    val js =
      if (jsSources.isEmpty && jsTests.isEmpty) List()
      else {
        val moduleName = if (!isCrossBuild) name else name + "-js"
        log.info(s"Creating JavaScript project ${Ansi.italic(moduleName)}...")

        if (jsModule.root.isEmpty) {
          log.error(
            s"Module ${Ansi.italic(moduleName)} does not specify root path, skipping..."
          )
          List()
        } else {
          createModule(
            root = jsModule.root.get,
            name = moduleName,
            sources = jsSources,
            tests = jsTests,
            resolvedDeps = Coursier.localArtefacts(
              resolution,
              collectJsDeps(build, false, jsModule)
                .map(
                  dep =>
                    ArtefactResolution.javaDepFromScalaDep(
                      dep,
                      JavaScript,
                      jsModule.scalaJsVersion.get,
                      jsModule.scalaVersion.get
                    )
                )
                .toSet,
              optionalArtefacts = true
            ),
            resolvedTestDeps = module.test
              .flatMap(_.js)
              .toList
              .flatMap(
                test =>
                  Coursier.localArtefacts(
                    resolution,
                    collectJsDeps(build, true, test)
                      .map(
                        dep =>
                          ArtefactResolution.javaDepFromScalaDep(
                            dep,
                            JavaScript,
                            test.scalaJsVersion.get,
                            test.scalaVersion.get
                          )
                      )
                      .toSet,
                    optionalArtefacts = true
                  )
              ),
            moduleDeps =
              (if (!isCrossBuild) List() else List(name)) ++
                collectJsModuleDeps(build, jsModule).flatMap(
                  name => BuildConfig.targetNames(build, name, JavaScript)
                ),
            projectPath = projectPath,
            buildPath = buildPath,
            modulesPath = modulesPath,
            librariesPath = librariesPath,
            scalaOrganisation = jsModule.scalaOrganisation.get,
            scalaVersion = jsModule.scalaVersion.get
          )

          List(moduleName)
        }
      }

    val jvmModule  = module.jvm.getOrElse(Module())
    val jvmSources = jvmModule.sources
    val jvmTests   = module.test.toList.flatMap(_.jvm.toList.flatMap(_.sources))
    val jvm =
      if (jvmSources.isEmpty && jvmTests.isEmpty) List()
      else {
        val moduleName = if (!isCrossBuild) name else name + "-jvm"
        log.info(s"Creating JVM project ${Ansi.italic(moduleName)}...")

        if (jvmModule.root.isEmpty) {
          log.error(
            s"Module ${Ansi.italic(moduleName)} does not specify root path, skipping..."
          )
          List()
        } else {
          createModule(
            root = jvmModule.root.get,
            name = moduleName,
            sources = jvmSources,
            tests = jvmTests,
            resolvedDeps = Coursier.localArtefacts(
              resolution,
              collectJvmJavaDeps(build, false, jvmModule).toSet ++
                collectJvmScalaDeps(build, false, jvmModule)
                  .map(
                    dep =>
                      ArtefactResolution.javaDepFromScalaDep(
                        dep,
                        JVM,
                        jvmModule.scalaVersion.get,
                        jvmModule.scalaVersion.get
                      )
                  )
                  .toSet,
              optionalArtefacts = true
            ),
            resolvedTestDeps = module.test
              .flatMap(_.jvm)
              .toList
              .flatMap(
                test =>
                  Coursier.localArtefacts(
                    resolution,
                    collectJvmJavaDeps(build, true, test).toSet ++
                      collectJvmScalaDeps(build, true, test)
                        .map(
                          dep =>
                            ArtefactResolution.javaDepFromScalaDep(
                              dep,
                              JVM,
                              test.scalaVersion.get,
                              test.scalaVersion.get
                            )
                        )
                        .toSet,
                    optionalArtefacts = true
                  )
              ),
            moduleDeps =
              (if (!isCrossBuild) List() else List(name)) ++
                collectJvmModuleDeps(build, jvmModule)
                  .flatMap(name => BuildConfig.targetNames(build, name, JVM)),
            projectPath = projectPath,
            buildPath = buildPath,
            modulesPath = modulesPath,
            librariesPath = librariesPath,
            scalaOrganisation = jvmModule.scalaOrganisation.get,
            scalaVersion = jvmModule.scalaVersion.get
          )

          List(moduleName)
        }
      }

    val nativeModule  = module.native.getOrElse(Module())
    val nativeSources = nativeModule.sources
    val nativeTests =
      module.test.toList.flatMap(_.native.toList.flatMap(_.sources))
    val native =
      if (nativeSources.isEmpty && nativeTests.isEmpty) List()
      else {
        val moduleName = if (!isCrossBuild) name else name + "-native"
        log.info(s"Creating native project ${Ansi.italic(moduleName)}...")

        if (nativeModule.root.isEmpty) {
          log.error(
            s"Module ${Ansi.italic(moduleName)} does not specify root path, skipping..."
          )
          List()
        } else {
          createModule(
            root = nativeModule.root.get,
            name = moduleName,
            sources = nativeSources,
            tests = nativeTests,
            resolvedDeps = Coursier.localArtefacts(
              resolution,
              collectNativeDeps(build, false, nativeModule)
                .map(
                  dep =>
                    ArtefactResolution.javaDepFromScalaDep(
                      dep,
                      Native,
                      nativeModule.scalaNativeVersion.get,
                      nativeModule.scalaVersion.get
                    )
                )
                .toSet,
              optionalArtefacts = true
            ),
            resolvedTestDeps = module.test
              .flatMap(_.native)
              .toList
              .flatMap(
                test =>
                  Coursier.localArtefacts(
                    resolution,
                    collectNativeDeps(build, true, test)
                      .map(
                        dep =>
                          ArtefactResolution.javaDepFromScalaDep(
                            dep,
                            Native,
                            nativeModule.scalaNativeVersion.get,
                            nativeModule.scalaVersion.get
                          )
                      )
                      .toSet,
                    optionalArtefacts = true
                  )
              ),
            moduleDeps =
              (if (!isCrossBuild) List() else List(name)) ++
                collectNativeModuleDeps(build, nativeModule).flatMap(
                  name => BuildConfig.targetNames(build, name, Native)
                ),
            projectPath = projectPath,
            buildPath = buildPath,
            modulesPath = modulesPath,
            librariesPath = librariesPath,
            scalaOrganisation = nativeModule.scalaOrganisation.get,
            scalaVersion = nativeModule.scalaVersion.get
          )

          List(moduleName)
        }
      }

    val sharedSources = module.sources
    val sharedTests   = module.test.toList.flatMap(_.sources)
    val shared =
      if (sharedSources.isEmpty && sharedTests.isEmpty) List()
      else {
        log.info(s"Create shared project ${Ansi.italic(name)}...")

        if (module.root.isEmpty) {
          log.error(
            s"Module ${Ansi.italic(name)} does not specify root path, skipping..."
          )
          List()
        } else {
          createModule(
            root = module.root.get,
            name = name,
            sources = sharedSources,
            tests = sharedTests,
            resolvedDeps = Coursier.localArtefacts(
              resolution,
              collectJvmJavaDeps(build, false, module).toSet ++
                collectJvmScalaDeps(build, false, module)
                  .map(
                    dep =>
                      ArtefactResolution.javaDepFromScalaDep(
                        dep,
                        JVM,
                        module.scalaVersion.get,
                        module.scalaVersion.get
                      )
                  )
                  .toSet,
              optionalArtefacts = true
            ),
            resolvedTestDeps = module.test.toList
              .flatMap(
                test =>
                  Coursier.localArtefacts(
                    resolution,
                    collectJvmScalaDeps(build, true, test)
                      .map(
                        dep =>
                          ArtefactResolution.javaDepFromScalaDep(
                            dep,
                            JVM,
                            module.scalaVersion.get,
                            module.scalaVersion.get
                          )
                      )
                      .toSet,
                    optionalArtefacts = true
                  )
              ),
            moduleDeps =
              jvm ++ collectJvmModuleDeps(build, module)
                .flatMap(name => BuildConfig.targetNames(build, name, JVM)),
            projectPath = projectPath,
            buildPath = buildPath,
            modulesPath = modulesPath,
            librariesPath = librariesPath,
            scalaOrganisation = module.scalaOrganisation.get,
            scalaVersion = module.scalaVersion.get
          )

          List(name)
        }
      }

    val customTargets = module.target.toList.flatMap {
      case (targetName, target) =>
        log.info(
          s"Create project for custom target ${Ansi.italic(name)}:$targetName..."
        )

        if (target.root.isEmpty) {
          log.error(
            s"Module ${Ansi.italic(name)}:$targetName does not specify root path, skipping..."
          )
          List()
        } else {
          val moduleName = name + "-" + targetName

          createModule(
            root = target.root.get,
            name = moduleName,
            sources = List(),
            tests = List(),
            resolvedDeps = List(),
            resolvedTestDeps = List(),
            moduleDeps = List(),
            projectPath = projectPath,
            buildPath = buildPath,
            modulesPath = modulesPath,
            librariesPath = librariesPath,
            scalaOrganisation = module.scalaOrganisation.get,
            scalaVersion = module.scalaVersion.get
          )

          List(moduleName)
        }
    }

    val platforms = js ++ jvm ++ native
    shared ++ platforms ++ customTargets
  }

  def writeModules(
    projectPath: Path,
    ideaPath: Path,
    modulesPath: Path,
    modules: List[String]
  ): Unit = {
    // TODO Indent file properly
    val xml = IdeaFile.createProject(
      modules.sorted.map(
        module =>
          normalisePath(ProjectDir, projectPath)(
            modulesPath.resolve(module + ".iml")
          )
      )
    )
    FileUtils.write(ideaPath.resolve("modules.xml").toFile, xml, "UTF-8")
  }

  def build(
    projectPath: Path,
    outputPath: Path,
    modules: Build,
    resolution: Coursier.ResolutionResult,
    compilerResolution: List[Coursier.ResolutionResult],
    tmpfs: Boolean,
    log: Log
  ): Unit = {
    val buildPath     = PathUtil.buildPath(outputPath, tmpfs, log)
    val ideaBuildPath = buildPath.resolve("idea")

    log.info(s"Build path: ${Ansi.italic(ideaBuildPath.toString)}")

    val ideaPath      = outputPath.resolve(".idea")
    val modulesPath   = ideaPath.resolve("modules")
    val librariesPath = ideaPath.resolve("libraries")

    if (!Files.exists(ideaPath)) Files.createDirectory(ideaPath)
    if (!Files.exists(modulesPath)) Files.createDirectory(modulesPath)
    if (!Files.exists(librariesPath)) Files.createDirectory(librariesPath)

    // Remove all stale .iml and .xml files
    if (Files.exists(ideaPath.resolve("sbt.xml")))
      Files.delete(ideaPath.resolve("sbt.xml"))
    Files
      .newDirectoryStream(modulesPath, "*.iml")
      .iterator()
      .asScala
      .foreach(Files.delete)
    Files
      .newDirectoryStream(librariesPath, "*.xml")
      .iterator()
      .asScala
      .foreach(Files.delete)

    createCompilerLibraries(modules, compilerResolution, librariesPath)
    FileUtils.write(
      ideaPath.resolve("misc.xml").toFile,
      IdeaFile.createJdk(jdkVersion = "1.8"),
      "UTF-8"
    )

    val ideaModules = modules.toList.flatMap {
      case (name, module) =>
        buildModule(
          modules,
          projectPath,
          ideaBuildPath,
          ideaPath,
          modulesPath,
          librariesPath,
          compilerResolution,
          resolution,
          name,
          module.module,
          log
        )
    }

    createCompilerSettings(modules, compilerResolution, ideaPath, ideaModules)
    writeModules(projectPath, ideaPath, modulesPath, ideaModules)

    log.info("IDEA project has been created")
  }
}
