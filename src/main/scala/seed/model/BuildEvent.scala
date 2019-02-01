package seed.model

import io.circe.{Encoder, Json}

sealed abstract class BuildEvent(val id: String)
object BuildEvent {
  case class Compiled(module: String, platform: Platform) extends BuildEvent("compiled")
  case class Compiling(module: String, platform: Platform) extends BuildEvent("compiling")
  case class Failed(module: String) extends BuildEvent("failed")
  case class Linked(path: String) extends BuildEvent("linked")

  implicit val encodeBuildEvent: Encoder[BuildEvent] = be => Json.fromFields(
    List("event" -> Json.fromString(be.id)) ++
    (be match {
      case Compiled(module, platform) =>
        List(
          "module" -> Json.fromString(module),
          "platform" -> Json.fromString(platform.id))
      case Compiling(module, platform) =>
        List(
          "module" -> Json.fromString(module),
          "platform" -> Json.fromString(platform.id))
      case Failed(module) => List("module" -> Json.fromString(module))
      case Linked(path) =>
        List("path" -> Json.fromString(path))
    })
  )
}
