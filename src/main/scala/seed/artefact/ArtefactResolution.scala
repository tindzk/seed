package seed.artefact

import java.nio.file.Path

import seed.Cli.PackageConfig
import MavenCentral.{CompilerVersion, PlatformVersion}
import seed.cli.util.Ansi
import seed.model.Build.{Dep, JavaDep, Module, Resolvers, ScalaDep}
import seed.model.Platform.{JVM, JavaScript, Native}
import seed.model.{Artefact, Platform, Resolution}
import seed.Log
import seed.config.BuildConfig
import seed.config.BuildConfig.Build

object ArtefactResolution {
  def javaDepFromScalaDep(
    dep: ScalaDep,
    platform: Platform,
    platformVersion: PlatformVersion,
    compilerVersion: CompilerVersion
  ): JavaDep =
    JavaDep(
      dep.organisation,
      MavenCentral.formatArtefactName(
        dep.artefact,
        dep.versionTag,
        platform,
        platformVersion,
        compilerVersion
      ),
      dep.version
    )

  def javaDepFromArtefact(
    artefact: Artefact,
    version: String,
    platform: Platform,
    platformVersion: PlatformVersion,
    compilerVersion: CompilerVersion
  ): JavaDep =
    JavaDep(
      artefact.organisation,
      artefact.versionTag.fold(artefact.name)(
        vt =>
          MavenCentral.formatArtefactName(
            artefact.name,
            vt,
            platform,
            platformVersion,
            compilerVersion
          )
      ),
      version
    )

  def jsPlatformDeps(module: Module): Set[JavaDep] = {
    val scalaVersion   = module.scalaVersion.get
    val scalaJsVersion = module.scalaJsVersion.get

    Set(
      Artefact.ScalaJsLibrary
    ).map(
      artefact =>
        javaDepFromArtefact(
          artefact,
          scalaJsVersion,
          JavaScript,
          scalaJsVersion,
          scalaVersion
        )
    )
  }

  def nativePlatformDeps(module: Module): Set[JavaDep] = {
    val scalaVersion       = module.scalaVersion.get
    val scalaNativeVersion = module.scalaNativeVersion.get

    Set(
      Artefact.ScalaNativeJavalib,
      Artefact.ScalaNativeScalalib,
      Artefact.ScalaNativeNativelib,
      Artefact.ScalaNativeAuxlib
    ).map(
      artefact =>
        javaDepFromArtefact(
          artefact,
          scalaNativeVersion,
          Native,
          scalaNativeVersion,
          scalaVersion
        )
    )
  }

  def nativeLibraryDep(module: Module): JavaDep = {
    val scalaVersion       = module.scalaVersion.get
    val scalaNativeVersion = module.scalaNativeVersion.get

    javaDepFromArtefact(
      Artefact.ScalaNativeNativelib,
      scalaNativeVersion,
      Native,
      scalaNativeVersion,
      scalaVersion
    )
  }

  def jvmArtefacts(module: Module): Set[(Platform, Dep)] =
    (module.scalaDeps ++ module.javaDeps).map(dep => JVM -> dep).toSet

  def jvmDeps(module: Module): Set[JavaDep] =
    module.scalaDeps
      .map(
        dep =>
          javaDepFromScalaDep(
            dep,
            JVM,
            module.scalaVersion.get,
            module.scalaVersion.get
          )
      )
      .toSet ++
      module.javaDeps.toSet

  def jsArtefacts(module: Module): Set[(Platform, Dep)] =
    module.scalaDeps.map(dep => JavaScript -> dep).toSet

  def jsDeps(module: Module): Set[JavaDep] =
    module.scalaDeps
      .map(
        dep =>
          javaDepFromScalaDep(
            dep,
            JavaScript,
            module.scalaJsVersion.get,
            module.scalaVersion.get
          )
      )
      .toSet

  def nativeArtefacts(module: Module): Set[(Platform, Dep)] =
    module.scalaDeps.map(dep => Native -> dep).toSet

  def nativeDeps(module: Module): Set[JavaDep] =
    module.scalaDeps
      .map(
        dep =>
          javaDepFromScalaDep(
            dep,
            Native,
            module.scalaNativeVersion.get,
            module.scalaVersion.get
          )
      )
      .toSet

  def compilerDeps(module: Module): List[Set[JavaDep]] = {
    def f(module: Module, platform: Platform): Set[JavaDep] = {
      val platformModule = BuildConfig.platformModule(module, platform).get

      val platformVer  = BuildConfig.platformVersion(platformModule, platform)
      val compilerVer  = platformModule.scalaVersion.get
      val organisation = platformModule.scalaOrganisation.get

      val scalaDeps = Set(
        Artefact.scalaCompiler(organisation) -> compilerVer,
        Artefact.scalaLibrary(organisation)  -> compilerVer,
        Artefact.scalaReflect(organisation)  -> compilerVer
      ) ++ (
        if (platform == Platform.Native)
          Set(Artefact.ScalaNativePlugin -> platformVer)
        else if (platform == Platform.JavaScript)
          Set(Artefact.ScalaJsCompiler -> platformVer)
        else Set()
      )

      scalaDeps.map {
        case (artefact, version) =>
          javaDepFromArtefact(
            artefact,
            version,
            platform,
            platformVer,
            compilerVer
          )
      } ++ platformModule.compilerDeps.map(
        dep => javaDepFromScalaDep(dep, platform, platformVer, compilerVer)
      )
    }

    module.targets.map(target => f(module, target)).filter(_.nonEmpty)
  }

  def allCompilerDeps(build: Build): List[Set[JavaDep]] =
    build.values.toList.flatMap(m => compilerDeps(m.module)).distinct

  def platformDeps(build: Build, module: Module): Set[JavaDep] =
    module.targets.toSet[Platform].flatMap { target =>
      if (target == JavaScript) jsPlatformDeps(module.js.get)
      else if (target == Native) nativePlatformDeps(module.native.get)
      else Set[JavaDep]()
    }

  def libraryDeps(module: Module, platforms: Set[Platform]): Set[JavaDep] =
    (if (!platforms.contains(JVM)) Set()
     else module.jvm.toSet.flatMap(jvmDeps)) ++
      (if (!platforms.contains(JavaScript)) Set()
       else module.js.toSet.flatMap(jsDeps)) ++
      (if (!platforms.contains(Native)) Set()
       else module.native.toSet.flatMap(nativeDeps)) ++
      module.test.toSet.flatMap(libraryDeps(_, platforms))

  def libraryArtefacts(module: Module): Set[(Platform, Dep)] =
    module.jvm.toSet.flatMap(jvmArtefacts) ++
      module.js.toSet.flatMap(jsArtefacts) ++
      module.native.toSet.flatMap(nativeArtefacts) ++
      module.test.toSet.flatMap(libraryArtefacts)

  def allPlatformDeps(build: Build): Set[JavaDep] =
    build.values.toSet.flatMap(m => platformDeps(build, m.module))

  def allLibraryDeps(
    build: Build,
    platforms: Set[Platform] = Set(JVM, JavaScript, Native)
  ): Set[JavaDep] =
    build.values.toSet.flatMap(m => libraryDeps(m.module, platforms))

  def allLibraryArtefacts(build: Build): Map[Platform, Set[Dep]] =
    build.values.toSet
      .flatMap(m => libraryArtefacts(m.module))
      .groupBy(_._1)
      .mapValues(_.map(_._2))

  def isScalaLibrary(javaDep: JavaDep): Boolean =
    javaDep.artefact == "scala-library" ||
      javaDep.artefact == "scala-reflect"

  def resolveScalaCompiler(
    resolutionResult: List[Coursier.ResolutionResult],
    scalaOrganisation: String,
    scalaVersion: String,
    userLibraries: List[Resolution.Artefact],
    classPath: List[Path],
    optionalArtefacts: Boolean
  ): Resolution.ScalaCompiler = {
    require(classPath.length == classPath.distinct.length)

    val compilerDep = JavaDep(scalaOrganisation, "scala-compiler", scalaVersion)
    val libraryDep  = JavaDep(scalaOrganisation, "scala-library", scalaVersion)
    val reflectDep  = JavaDep(scalaOrganisation, "scala-reflect", scalaVersion)

    val resolution =
      resolutionResult
        .find(r => Coursier.hasDep(r, compilerDep))
        .getOrElse(
          throw new Exception(s"Could not find dependency $compilerDep")
        )

    val compiler =
      Coursier.localArtefacts(resolution, Set(compilerDep), false)
    val scalaLibraries =
      Coursier.localArtefacts(
        resolution,
        Set(libraryDep, reflectDep),
        optionalArtefacts
      )

    // Replace official scala-library and scala-reflect artefacts by
    // organisation-specific ones. This is needed for Typelevel Scala.
    val libraries =
      scalaLibraries ++ userLibraries.filter(a => !isScalaLibrary(a.javaDep))

    val compilerArtefacts =
      compiler.filter(a => !isScalaLibrary(a.javaDep)) ++ scalaLibraries

    Resolution.ScalaCompiler(
      scalaOrganisation,
      scalaVersion,
      libraries,
      classPath,
      compilerArtefacts.map(_.libraryJar)
    )
  }

  def resolution(
    seedConfig: seed.model.Config,
    resolvers: Resolvers,
    build: Build,
    packageConfig: PackageConfig,
    optionalArtefacts: Boolean,
    platformDeps: Set[JavaDep],
    compilerDeps: List[Set[JavaDep]],
    log: Log
  ) = {
    val silent = packageConfig.silent || seedConfig.resolution.silent

    import packageConfig._
    val resolvedIvyPath   = ivyPath.getOrElse(seedConfig.resolution.ivyPath)
    val resolvedCachePath = cachePath.getOrElse(seedConfig.resolution.cachePath)

    log.info("Configured resolvers:")
    log.info(
      "- " + Ansi.italic(resolvedIvyPath.toString) + " (Ivy)",
      detail = true
    )
    log.info(
      "- " + Ansi.italic(resolvedCachePath.toString) + " (Coursier)",
      detail = true
    )
    resolvers.ivy
      .foreach(
        ivy => log.info("- " + Ansi.italic(ivy.url) + " (Ivy)", detail = true)
      )
    resolvers.maven
      .foreach(
        maven => log.info("- " + Ansi.italic(maven) + " (Maven)", detail = true)
      )

    def resolve(deps: Set[JavaDep]) =
      Coursier.resolveAndDownload(
        deps,
        resolvers,
        resolvedIvyPath,
        resolvedCachePath,
        optionalArtefacts,
        silent,
        log
      )

    log.info("Resolving platform artefacts...")

    val platformResolution = resolve(platformDeps)

    log.info("Resolving compiler artefacts...")

    // Resolve Scala compilers separately because Coursier merges dependencies
    // with different versions
    val compilerResolution = compilerDeps.map(resolve)

    (resolvedCachePath, platformResolution, compilerResolution)
  }

  /** @return If there are two dependencies with the same organisation and
    *         artefact name, only retain the last one, regardless of its version.
    */
  def mergeDeps[T <: Dep](deps: List[T]): List[T] =
    deps.foldLeft(List[T]()) {
      case (acc, cur) =>
        acc.find(
          d => d.organisation == cur.organisation && d.artefact == cur.artefact
        ) match {
          case None           => acc :+ cur
          case Some(previous) => acc.diff(List(previous)) :+ cur
        }
    }
}
