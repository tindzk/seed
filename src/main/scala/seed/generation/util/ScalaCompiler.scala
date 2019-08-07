package seed.generation.util

import java.nio.file.Path

import seed.artefact.Coursier
import seed.config.BuildConfig
import seed.artefact.ArtefactResolution
import seed.config.BuildConfig.Build
import seed.model.Build.Module
import seed.model.Platform.{JavaScript, Native}
import seed.model.{Artefact, Build, Platform}

object ScalaCompiler {
  def resolveCompiler(
    compilerResolution: List[Coursier.ResolutionResult],
    artefact: Artefact,
    artefactVersion: String,
    platform: Platform,
    platformVer: String,
    compilerVer: String
  ): Path =
    compilerResolution.iterator
      .flatMap(
        resolution =>
          Coursier.artefactPath(
            resolution,
            artefact,
            platform,
            platformVer,
            compilerVer,
            artefactVersion
          )
      )
      .toList
      .headOption
      .getOrElse(throw new Exception(s"Artefact '$artefact' missing"))

  def compilerPlugIns(
    build: Build,
    module: Module,
    compilerResolution: List[Coursier.ResolutionResult],
    platform: Platform,
    compilerVer: String
  ): List[String] = {
    import ArtefactResolution.mergeDeps

    val platformVer = BuildConfig.platformVersion(module, platform)
    val moduleDeps  = BuildConfig.collectModuleDeps(build, module, platform)
    val modules     = moduleDeps.map(build(_).module) :+ module
    val artefacts =
      (if (platform == JavaScript) List(Artefact.ScalaJsCompiler -> platformVer)
       else if (platform == Native)
         List(Artefact.ScalaNativePlugin -> platformVer)
       else List()) ++ mergeDeps(modules.flatMap { m =>
        val platformModule = BuildConfig.platformModule(m, platform)
        val dependencies =
          m.compilerDeps ++ platformModule.toList.flatMap(_.compilerDeps)
        mergeDeps(dependencies)
      }).map(d => Artefact.fromDep(d) -> d.version)

    artefacts
      .map {
        case (artefact, version) =>
          resolveCompiler(
            compilerResolution,
            artefact,
            version,
            platform,
            platformVer,
            compilerVer
          )
      }
      .map(p => "-Xplugin:" + p)
  }
}
