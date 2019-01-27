package seed.generation

import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._
import org.apache.commons.io.FileUtils
import seed.config.BuildConfig.{collectJsDeps, collectJsModuleDeps, collectJvmJavaDeps, collectJvmModuleDeps, collectJvmScalaDeps, collectNativeDeps, collectNativeModuleDeps}
import seed.artefact.{Coursier, ArtefactResolution}
import seed.cli.util.Ansi
import seed.generation.util.IdeaFile
import seed.model.Artefact.PlatformSuffix
import seed.model.{Build, Resolution}
import seed.model.Build.Module
import seed.model.Platform.{JVM, JavaScript, Native}
import seed.Log
import seed.config.BuildConfig

object Idea {
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
                   librariesPath: Path): Unit = {
    (resolvedDeps ++ resolvedTestDeps)
      .foreach(dep => createLibrary(
        librariesPath, dep.libraryJar, dep.javaDocJar, dep.sourcesJar))

    val scalaDep = build.project.scalaOrganisation + "-" +
                   build.project.scalaVersion

    val classPathOut     = buildPath.resolve(name).resolve("main")
    val testClassPathOut = buildPath.resolve(name).resolve("test")

    if (!Files.exists(classPathOut)) Files.createDirectories(classPathOut)
    if (!Files.exists(testClassPathOut))
      Files.createDirectories(testClassPathOut)

    val moduleDir = "$MODULE_DIR$/"

    val xml = IdeaFile.createModule(IdeaFile.Module(
      projectId = name,
      rootPath = moduleDir + modulesPath.relativize(root).toString,
      sourcePaths = sources.map(p => moduleDir + modulesPath.relativize(p).toString),
      testPaths = tests.map(p => moduleDir + modulesPath.relativize(p).toString),
      libraries = List(scalaDep) ++ resolvedDeps.map(_.libraryJar.getFileName.toString),
      testLibraries = resolvedTestDeps.map(_.libraryJar.getFileName.toString),
      moduleDeps = moduleDeps,
      output = Some(IdeaFile.Output(
        classPath =
          if (classPathOut.isAbsolute) classPathOut.toString
          else moduleDir + "../../" + classPathOut.toString,
        testClassPath =
          if (testClassPathOut.isAbsolute) testClassPathOut.toString
          else moduleDir + "../../" + testClassPathOut.toString))))

    FileUtils.write(modulesPath.resolve(name + ".iml").toFile, xml, "UTF-8")
  }

  def createCompilerLibrary(build: Build,
                            resolution: List[Coursier.ResolutionResult],
                            librariesPath: Path): Unit = {
    val scalaCompiler = ArtefactResolution.resolveScalaCompiler(resolution,
      build.project.scalaOrganisation, build.project.scalaVersion, List())

    val xml = IdeaFile.createLibrary(IdeaFile.Library(
      name = build.project.scalaOrganisation + "-" + build.project.scalaVersion,
      isScalaCompiler = true,
      compilerClasses = scalaCompiler.compilerJars.map(_.toString),
      classes = scalaCompiler.fullClassPath.map(_.toString),
      javaDoc = List(),
      sources = List()))

    val fileName = ideaName(build.project.scalaOrganisation) + "_" +
                   ideaName(build.project.scalaVersion) + ".xml"
    FileUtils.write(librariesPath.resolve(fileName).toFile, xml, "UTF-8")
  }

  def createCompilerSettings(build: Build,
                             ideaPath: Path,
                             modules: List[String]): Unit = {
    val xml = IdeaFile.createScalaCompiler(build.project.scalaOptions, modules)
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
          createModule(
            build = build,
            root = module.js.get.root.get,
            name = moduleName,
            sources = module.js.get.sources,
            tests = module.test.toList.flatMap(
              _.js.toList.flatMap(_.sources)),
            resolvedDeps = Coursier.localArtefacts(resolution,
              collectJsDeps(build, module).map(dep =>
                ArtefactResolution.dependencyFromDep(
                  dep, JavaScript,
                  build.project.scalaJsVersion.get,
                  build.project.scalaVersion)
              ).toSet,
              optionalArtefacts = true),
            resolvedTestDeps = module.test.toList.flatMap(test =>
              Coursier.localArtefacts(resolution,
                collectJsDeps(build, test).map(dep =>
                  ArtefactResolution.dependencyFromDep(
                    dep, JavaScript,
                    build.project.scalaJsVersion.get,
                    build.project.scalaVersion)
                ).toSet,
                optionalArtefacts = true)),
            moduleDeps =
              (if (!isCrossBuild) List() else List(name)) ++
              collectJsModuleDeps(build, module).flatMap(name =>
                BuildConfig.targetNames(build, name, JavaScript)),
            projectPath = projectPath,
            buildPath = buildPath,
            modulesPath = modulesPath,
            librariesPath = librariesPath)

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
          createModule(
            build = build,
            root = module.jvm.get.root.get,
            name = moduleName,
            sources = module.jvm.get.sources,
            tests = module.test.toList.flatMap(
              _.jvm.toList.flatMap(_.sources)),
            resolvedDeps = Coursier.localArtefacts(resolution,
              collectJvmJavaDeps(build, module).map(dep =>
                ArtefactResolution.dependencyFromDep(
                  dep, JVM,
                  build.project.scalaVersion,
                  build.project.scalaVersion,
                  PlatformSuffix.Regular
                )
              ).toSet ++
              collectJvmScalaDeps(build, module).map(dep =>
                ArtefactResolution.dependencyFromDep(
                  dep, JVM,
                  build.project.scalaVersion,
                  build.project.scalaVersion)
              ).toSet,
              optionalArtefacts = true),
            resolvedTestDeps = module.test.toSet.flatMap(test =>
              Coursier.localArtefacts(resolution,
                collectJvmScalaDeps(build, test).map(dep =>
                  ArtefactResolution.dependencyFromDep(
                    dep, JVM,
                    build.project.scalaVersion,
                    build.project.scalaVersion)
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
            librariesPath = librariesPath)

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
                ArtefactResolution.dependencyFromDep(
                  dep, Native,
                  build.project.scalaNativeVersion.get,
                  build.project.scalaVersion)
              ).toSet,
              optionalArtefacts = true),
            resolvedTestDeps = module.test.toList.flatMap(test =>
              Coursier.localArtefacts(resolution,
                collectNativeDeps(build, test).map(dep =>
                  ArtefactResolution.dependencyFromDep(
                    dep, Native,
                    build.project.scalaNativeVersion.get,
                    build.project.scalaVersion)
                ).toSet,
                optionalArtefacts = true)),
            moduleDeps =
              (if (!isCrossBuild) List() else List(name)) ++
              collectNativeModuleDeps(build, module).flatMap(name =>
                BuildConfig.targetNames(build, name, Native)),
            projectPath = projectPath,
            buildPath = buildPath,
            modulesPath = modulesPath,
            librariesPath = librariesPath)

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
          createModule(
            build = build,
            root = module.root.get,
            name = name,
            sources = module.sources,
            tests = module.test.toList.flatMap(_.sources),
            resolvedDeps = Coursier.localArtefacts(resolution,
              collectJvmJavaDeps(build, module).map(dep =>
                ArtefactResolution.dependencyFromDep(
                  dep, JVM,
                  build.project.scalaVersion,
                  build.project.scalaVersion,
                  PlatformSuffix.Regular
                )
              ).toSet ++
              collectJvmScalaDeps(build, module).map(dep =>
                ArtefactResolution.dependencyFromDep(
                  dep, JVM,
                  build.project.scalaVersion,
                  build.project.scalaVersion)
              ).toSet,
              optionalArtefacts = true),
            resolvedTestDeps = module.test.toSet.flatMap(test =>
              Coursier.localArtefacts(resolution,
                collectJvmScalaDeps(build, test).map(dep =>
                  ArtefactResolution.dependencyFromDep(
                    dep, JVM,
                    build.project.scalaVersion,
                    build.project.scalaVersion)
                ).toSet,
                optionalArtefacts = true)
            ).toList,
            moduleDeps =
              jvm ++ collectJvmModuleDeps(build, module).flatMap(name =>
                BuildConfig.targetNames(build, name, JVM)),
            projectPath = projectPath,
            buildPath = buildPath,
            modulesPath = modulesPath,
            librariesPath = librariesPath)

          List(name)
        }
      }

    val platforms = js ++ jvm ++ native
    shared ++ platforms
  }

  def writeModules(projectPath: Path,
                   ideaPath: Path,
                   modulesPath: Path,
                   modules: List[String]): Unit = {
    // TODO Indent file properly
    val xml = IdeaFile.createProject(modules.sorted.map(module =>
      "$PROJECT_DIR$/" + projectPath
        .relativize(modulesPath)
        .resolve(module + ".iml")
        .toString))

    FileUtils.write(ideaPath.resolve("modules.xml").toFile, xml, "UTF-8")
  }

  def build(projectPath: Path,
            build: Build,
            resolution: Coursier.ResolutionResult,
            compilerResolution: List[Coursier.ResolutionResult],
            tmpfs: Boolean): Unit = {
    val baseBuildPath =
      if (tmpfs) BuildConfig.tmpfsPath(projectPath)
      else Paths.get("build")

    val buildPath = baseBuildPath.resolve("idea")

    Log.info(s"Build path: ${Ansi.italic(buildPath.toString)}")

    val ideaPath      = projectPath.resolve(".idea")
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

    createCompilerLibrary(build, compilerResolution, librariesPath)
    FileUtils.write(ideaPath.resolve("misc.xml").toFile,
      IdeaFile.createJdk(jdkVersion = "1.8"), "UTF-8")

    val modules = build.module.toList.flatMap { case (name, module) =>
      buildModule(
        projectPath, buildPath, ideaPath, modulesPath, librariesPath, build,
        resolution, name, module)
    }

    createCompilerSettings(build, ideaPath, modules)
    writeModules(projectPath, ideaPath, modulesPath, modules)

    Log.info("IDEA project has been created")
  }
}
