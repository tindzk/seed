package seed.cli.util

import java.nio.file.Paths

import minitest.SimpleTestSuite
import seed.config.BuildConfig.ModuleConfig
import seed.model.{Build, Platform}

object TargetSpec extends SimpleTestSuite {
  import seed.model.Build.Module

  test("Parse module string") {
    assertEquals(
      Target.parseModuleString(Map())(""),
      Left("Module name cannot be empty")
    )

    assertEquals(
      Target.parseModuleString(Map())("test"),
      Left(s"Invalid module name: ${Ansi.italic("test")}. Valid names: ")
    )

    assertEquals(
      Target.parseModuleString(
        Map("test" -> ModuleConfig(Module(), Paths.get(".")))
      )("test:jvm"),
      Left(
        s"Invalid build target ${Ansi.italic("jvm")} provided on module ${Ansi.italic("test")}"
      )
    )

    assertEquals(
      Target
        .parseModuleString(
          Map(
            "test" -> ModuleConfig(
              Module(targets = List(Platform.JVM)),
              Paths.get(".")
            )
          )
        )("test:jvm")
        .isRight,
      true
    )

    assertEquals(
      Target.parseModuleString(
        Map("test" -> ModuleConfig(Module(), Paths.get(".")))
      )("test:custom"),
      Left(s"Invalid build target ${Ansi
        .italic("custom")} provided on module ${Ansi.italic("test")}")
    )

    assertEquals(
      Target
        .parseModuleString(
          Map(
            "test" -> ModuleConfig(
              Module(target = Map("custom" -> Build.Target())),
              Paths.get(".")
            )
          )
        )("test:custom")
        .isRight,
      true
    )
  }
}
