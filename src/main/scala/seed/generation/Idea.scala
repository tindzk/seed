package seed.generation

import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import org.apache.commons.io.FileUtils
import seed.config.BuildConfig.{collectJsDeps, collectJsModuleDeps, collectJvmJavaDeps, collectJvmModuleDeps, collectJvmScalaDeps, collectNativeDeps, collectNativeModuleDeps}
import seed.artefact.{ArtefactResolution, Coursier}
import seed.cli.util.Ansi
import seed.generation.util.{IdeaFile, PathUtil}
import seed.model.{Build, Resolution}
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

  def createLibrary(librariesPath: Path,
                    libraryJar: Path,
                    javaDocJar: Option[Path],
                    sourcesJar: Option[Path]
                   ): Unit = {
    val xml = IdeaFile.createLibrary(IdeaFile.Library(
      libraryJar.getFileName.toString,
      isScalaCompiler = false,
      compilerClasses = List(),
      classes = List(libraryJar.toAbsolutePath.toString),
      javaDoc = javaDocJar.toList.map(_.toAbsolutePath.toString),
      sources = sourcesJar.toList.map(_.toAbsolutePath.toString)))

    FileUtils.write(
      librariesPath.resolve(ideaName(libraryJar.getFileName.toString) + ".xml")
        .toFile, xml, "UTF-8")
  }

  def createModule(build      : Build,
                   root       : Path,
                   name       : String,
                   sources    : List[Path],
                   tests      : List[Path],
                   resolvedDeps: List[Resolution.Artefact],
                   resolvedTestDeps: List[Resolution.Artefact],
                   moduleDeps  : List[String],
                   projectPath: Path,
                   buildPath: Path,
                   modulesPath: Path,
                   librariesPath: Path,
                   scalaVersion: String): Unit = {
    val filteredResolvedDeps = resolvedDeps.filter(l =>
      !ArtefactResolution.isCompilerLibrary(l.libraryJar))
    val filteredResolvedTestDeps = resolvedTestDeps.filter(l =>
      !ArtefactResolution.isCompilerLibrary(l.libraryJar))

    (filteredResolvedDeps ++ filteredResolvedTestDeps).foreach(dep =>
      createLibrary(
        librariesPath, dep.libraryJar, dep.javaDocJar, dep.sourcesJar))

    val scalaDep = build.project.scalaOrganisation + "-" + scalaVersion

    val classPathOut     = buildPath.resolve(name).resolve("main")
    val testClassPathOut = buildPath.resolve(name).resolve("test")

    if (!Files.exists(classPathOut)) Files.createDirectories(classPathOut)
    if (!Files.exists(testClassPathOut))
      Files.createDirectories(testClassPathOut)

    val xml = IdeaFile.createModule(IdeaFile.Module(
      projectId = name,
      rootPath = normalisePath(ModuleDir, modulesPath)(root),
      sourcePaths = sources.map(normalisePath(ModuleDir, modulesPath)),
      testPaths = tests.map(normalisePath(ModuleDir, modulesPath)),
      libraries = List(scalaDep) ++
                  filteredResolvedDeps.map(_.libraryJar.getFileName.toString),
      testLibraries =
        filteredResolvedTestDeps.map(_.libraryJar.getFileName.toString),
      moduleDeps = moduleDeps,
      output = Some(IdeaFile.Output(
        classPath = normalisePath(ModuleDir, modulesPath)(classPathOut),
        testClassPath = normalisePath(ModuleDir, modulesPath)(testClassPathOut)))))

    FileUtils.write(modulesPath.resolve(name + ".iml").toFile, xml, "UTF-8")
  }

  def createCompilerLibraries(build: Build,
                              resolution: List[Coursier.ResolutionResult],
                              librariesPath: Path): Unit = {
    val scalaVersions = (build.module.values.toList.flatMap(module =>
      module.jvm.flatMap(_.scalaVersion).toList ++
      module.js.flatMap(_.scalaVersion).toList ++
      module.native.flatMap(_.scalaVersion).toList ++
      module.scalaVersion.toList
    ) ++ List(build.project.scalaVersion)).distinct

    scalaVersions.foreach { scalaVersion =>
      val scalaCompiler = ArtefactResolution.resolveScalaCompiler(resolution,
        build.project.scalaOrganisation, scalaVersion, List())

      val xml = IdeaFile.createLibrary(IdeaFile.Library(
        name = build.project.scalaOrganisation + "-" + scalaVersion,
        isScalaCompiler = true,
        compilerClasses = scalaCompiler.compilerJars.map(_.toString),
        classes = scalaCompiler.fullClassPath.map(_.toString),
        javaDoc = List(),
        sources = List()))

      val fileName = ideaName(build.project.scalaOrganisation) + "_" +
                     ideaName(scalaVersion) + ".xml"
      FileUtils.write(librariesPath.resolve(fileName).toFile, xml, "UTF-8")
    }
  }

  def createCompilerSettings(build: Build,
                             compilerResolution: List[Coursier.ResolutionResult],
                             ideaPath: Path,
                             modules: List[String]): Unit = {
    // Group all modules by additional settings; create compiler configuration
    // for each unique set of parameters
    val modulePlugIns = modules.filter(build.module.contains).map { m =>
      val module = build.module(m)
      val scalaVersion = BuildConfig.scalaVersion(build.project,
        module.jvm.toList ++ List(module))
      m -> util.ScalaCompiler.compilerPlugIns(
        build, build.module(m), compilerResolution, JVM, scalaVersion)
    }
    val compilerSettings =
      modulePlugIns.groupBy(_._2).mapValues(_.map(_._1)).toList.map {
        case (settings, modules) =>
          val allModules = modules.flatMap { module =>
            val targets = BuildConfig.moduleTargets(build.module(module), List())
            val all = module +: targets.map(target =>
              BuildConfig.targetName(build, module, target))
            all.distinct
          }

          (build.project.scalaOptions ++ settings, allModules)
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
  def buildModule(projectPath: Path,
                  buildPath: Path,
                  ideaPath: Path,
                  modulesPath: Path,
                  librariesPath: Path,
                  build: Build,
                  compilerResolution: List[Coursier.ResolutionResult],
                  resolution: Coursier.ResolutionResult,
                  name: String,
                  module: Module
                 ): List[String] = {
    val isCrossBuild = module.targets.toSet.size > 1

    val js =
      if (!module.js.exists(_.sources.nonEmpty)) List()
      else {
        val moduleName = if (!isCrossBuild) name else name + "-js"
        Log.info(s"Creating JavaScript project ${Ansi.italic(moduleName)}...")

        if (module.js.get.root.isEmpty) {
          Log.error(s"Module ${Ansi.italic(moduleName)} does not specify root path, skipping...")
          List()
        } else {
          val scalaVersion = BuildConfig.scalaVersion(build.project,
            List(module.js.get, module))

          createModule(
            build = build,
            root = module.js.get.root.get,
            name = moduleName,
            sources = module.js.get.sources,
            tests = module.test.toList.flatMap(
              _.js.toList.flatMap(_.sources)),
            resolvedDeps = Coursier.localArtefacts(resolution,
              collectJsDeps(build, module).map(dep =>
                ArtefactResolution.javaDepFromScalaDep(
                  dep, JavaScript,
                  build.project.scalaJsVersion.get,
                  scalaVersion)
              ).toSet,
              optionalArtefacts = true),
            resolvedTestDeps = module.test.toList.flatMap(test =>
              Coursier.localArtefacts(resolution,
                collectJsDeps(build, test).map(dep =>
                  ArtefactResolution.javaDepFromScalaDep(
                    dep, JavaScript,
                    build.project.scalaJsVersion.get,
                    scalaVersion)
                ).toSet,
                optionalArtefacts = true)),
            moduleDeps =
              (if (!isCrossBuild) List() else List(name)) ++
              collectJsModuleDeps(build, module).flatMap(name =>
                BuildConfig.targetNames(build, name, JavaScript)),
            projectPath = projectPath,
            buildPath = buildPath,
            modulesPath = modulesPath,
            librariesPath = librariesPath,
            scalaVersion = scalaVersion)

          List(moduleName)
        }
      }

    val jvm =
      if (!module.jvm.exists(_.sources.nonEmpty)) List()
      else {
        val moduleName = if (!isCrossBuild) name else name + "-jvm"
        Log.info(s"Creating JVM project ${Ansi.italic(moduleName)}...")

        if (module.jvm.get.root.isEmpty) {
          Log.error(s"Module ${Ansi.italic(moduleName)} does not specify root path, skipping...")
          List()
        } else {
          val scalaVersion = BuildConfig.scalaVersion(build.project,
            List(module.jvm.get, module))

          createModule(
            build = build,
            root = module.jvm.get.root.get,
            name = moduleName,
            sources = module.jvm.get.sources,
            tests = module.test.toList.flatMap(
              _.jvm.toList.flatMap(_.sources)),
            resolvedDeps = Coursier.localArtefacts(resolution,
              collectJvmJavaDeps(build, module).toSet ++
              collectJvmScalaDeps(build, module).map(dep =>
                ArtefactResolution.javaDepFromScalaDep(
                  dep, JVM, scalaVersion, scalaVersion)
              ).toSet,
              optionalArtefacts = true),
            resolvedTestDeps = module.test.toSet.flatMap(test =>
              Coursier.localArtefacts(resolution,
                collectJvmScalaDeps(build, test).map(dep =>
                  ArtefactResolution.javaDepFromScalaDep(
                    dep, JVM, scalaVersion, scalaVersion)
                ).toSet,
                optionalArtefacts = true)
              ).toList,
            moduleDeps =
              (if (!isCrossBuild) List() else List(name)) ++
              collectJvmModuleDeps(build, module).flatMap(name =>
                BuildConfig.targetNames(build, name, JVM)),
            projectPath = projectPath,
            buildPath = buildPath,
            modulesPath = modulesPath,
            librariesPath = librariesPath,
            scalaVersion = scalaVersion)

          List(moduleName)
        }
      }

    val native =
      if (!module.native.exists(_.sources.nonEmpty)) List()
      else {
        val moduleName = if (!isCrossBuild) name else name + "-native"
        Log.info(s"Creating native project ${Ansi.italic(moduleName)}...")

        if (module.native.get.root.isEmpty) {
          Log.error(s"Module ${Ansi.italic(moduleName)} does not specify root path, skipping...")
          List()
        } else {
          val scalaVersion = BuildConfig.scalaVersion(build.project,
            List(module.native.get, module))

          createModule(
            build = build,
            root = module.native.get.root.get,
            name = moduleName,
            sources = module.native.get.sources,
            tests = module.test.toList.flatMap(
              _.native.toList.flatMap(_.sources)),
            resolvedDeps = Coursier.localArtefacts(
              resolution,
              collectNativeDeps(build, module).map(dep =>
                ArtefactResolution.javaDepFromScalaDep(
                  dep, Native,
                  build.project.scalaNativeVersion.get, scalaVersion)
              ).toSet,
              optionalArtefacts = true),
            resolvedTestDeps = module.test.toList.flatMap(test =>
              Coursier.localArtefacts(resolution,
                collectNativeDeps(build, test).map(dep =>
                  ArtefactResolution.javaDepFromScalaDep(
                    dep, Native,
                    build.project.scalaNativeVersion.get, scalaVersion)
                ).toSet,
                optionalArtefacts = true)),
            moduleDeps =
              (if (!isCrossBuild) List() else List(name)) ++
              collectNativeModuleDeps(build, module).flatMap(name =>
                BuildConfig.targetNames(build, name, Native)),
            projectPath = projectPath,
            buildPath = buildPath,
            modulesPath = modulesPath,
            librariesPath = librariesPath,
            scalaVersion = scalaVersion)

          List(moduleName)
        }
      }

    val shared =
      if (module.sources.isEmpty) List()
      else {
        Log.info(s"Create shared project ${Ansi.italic(name)}...")

        if (module.root.isEmpty) {
          Log.error(s"Module ${Ansi.italic(name)} does not specify root path, skipping...")
          List()
        } else {
          val scalaVersion = BuildConfig.scalaVersion(build.project,
            List(module))

          createModule(
            build = build,
            root = module.root.get,
            name = name,
            sources = module.sources,
            tests = module.test.toList.flatMap(_.sources),
            resolvedDeps = Coursier.localArtefacts(resolution,
              collectJvmJavaDeps(build, module).toSet ++
              collectJvmScalaDeps(build, module).map(dep =>
                ArtefactResolution.javaDepFromScalaDep(
                  dep, JVM, scalaVersion, scalaVersion)
              ).toSet,
              optionalArtefacts = true),
            resolvedTestDeps = module.test.toSet.flatMap(test =>
              Coursier.localArtefacts(resolution,
                collectJvmScalaDeps(build, test).map(dep =>
                  ArtefactResolution.javaDepFromScalaDep(
                    dep, JVM, scalaVersion, scalaVersion)
                ).toSet,
                optionalArtefacts = true)
            ).toList,
            moduleDeps =
              jvm ++ collectJvmModuleDeps(build, module).flatMap(name =>
                BuildConfig.targetNames(build, name, JVM)),
            projectPath = projectPath,
            buildPath = buildPath,
            modulesPath = modulesPath,
            librariesPath = librariesPath,
            scalaVersion = scalaVersion)

          List(name)
        }
      }

    val customTargets = module.target.toList.flatMap { case (targetName, target) =>
      Log.info(s"Create project for custom target ${Ansi.italic(name)}:$targetName...")

      if (target.root.isEmpty) {
        Log.error(s"Module ${Ansi.italic(name)}:$targetName does not specify root path, skipping...")
        List()
      } else {
        val scalaVersion = BuildConfig.scalaVersion(build.project, List(module))
        val moduleName = name + "-" + targetName

        createModule(
          build = build,
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
          scalaVersion = scalaVersion)

        List(moduleName)
      }
    }

    val platforms = js ++ jvm ++ native
    shared ++ platforms ++ customTargets
  }

  def writeModules(projectPath: Path,
                   ideaPath: Path,
                   modulesPath: Path,
                   modules: List[String]): Unit = {
    // TODO Indent file properly
    val xml = IdeaFile.createProject(modules.sorted.map(module =>
      normalisePath(ProjectDir, projectPath)(
        modulesPath.resolve(module + ".iml"))))
    FileUtils.write(ideaPath.resolve("modules.xml").toFile, xml, "UTF-8")
  }

  def build(projectPath: Path,
            outputPath: Path,
            build: Build,
            resolution: Coursier.ResolutionResult,
            compilerResolution: List[Coursier.ResolutionResult],
            tmpfs: Boolean): Unit = {
    val buildPath = PathUtil.buildPath(projectPath, tmpfs)
    val ideaBuildPath = buildPath.resolve("idea")

    Log.info(s"Build path: ${Ansi.italic(ideaBuildPath.toString)}")

    val ideaPath      = outputPath.resolve(".idea")
    val modulesPath   = ideaPath.resolve("modules")
    val librariesPath = ideaPath.resolve("libraries")

    if (!Files.exists(ideaPath)) Files.createDirectory(ideaPath)
    if (!Files.exists(modulesPath)) Files.createDirectory(modulesPath)
    if (!Files.exists(librariesPath)) Files.createDirectory(librariesPath)

    // Remove all stale .iml and .xml files
    if (Files.exists(ideaPath.resolve("sbt.xml")))
      Files.delete(ideaPath.resolve("sbt.xml"))
    Files.newDirectoryStream(modulesPath, "*.iml").iterator().asScala
      .foreach(Files.delete)
    Files.newDirectoryStream(librariesPath, "*.xml").iterator().asScala
      .foreach(Files.delete)

    createCompilerLibraries(build, compilerResolution, librariesPath)
    FileUtils.write(ideaPath.resolve("misc.xml").toFile,
      IdeaFile.createJdk(jdkVersion = "1.8"), "UTF-8")

    val modules = build.module.toList.flatMap { case (name, module) =>
      buildModule(
        projectPath, ideaBuildPath, ideaPath, modulesPath, librariesPath, build,
        compilerResolution, resolution, name, module)
    }

    createCompilerSettings(build, compilerResolution, ideaPath, modules)
    writeModules(projectPath, ideaPath, modulesPath, modules)

    Log.info("IDEA project has been created")
  }
}
