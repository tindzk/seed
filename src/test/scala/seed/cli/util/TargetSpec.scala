package seed.cli.util

import minitest.SimpleTestSuite
import seed.model.{Build, Platform}
import seed.model.Build.Module

object TargetSpec extends SimpleTestSuite {
  test("Parse module string") {
    assertEquals(
      Target.parseModuleString(
        Build(project = Build.Project(""), module = Map())
      )(""),
      Left("Module name cannot be empty")
    )

    assertEquals(
      Target.parseModuleString(
        Build(project = Build.Project(""), module = Map())
      )("test"),
      Left(s"Invalid module name: ${Ansi.italic("test")}. Valid names: ")
    )

    assertEquals(
      Target.parseModuleString(
        Build(project = Build.Project(""), module = Map("test" -> Module()))
      )("test:jvm"),
      Left(s"Invalid build target ${Ansi.italic("jvm")} provided")
    )

    assertEquals(
      Target
        .parseModuleString(
          Build(
            project = Build.Project(""),
            module = Map("test" -> Module(targets = List(Platform.JVM)))
          )
        )("test:jvm")
        .isRight,
      true
    )

    assertEquals(
      Target.parseModuleString(
        Build(project = Build.Project(""), module = Map("test" -> Module()))
      )("test:custom"),
      Left(s"Invalid build target ${Ansi.italic("custom")} provided")
    )

    assertEquals(
      Target
        .parseModuleString(
          Build(
            project = Build.Project(""),
            module =
              Map("test" -> Module(target = Map("custom" -> Build.Target())))
          )
        )("test:custom")
        .isRight,
      true
    )
  }
}
