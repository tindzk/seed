package seed.publish

import io.circe.parser._
import io.circe.generic.auto._
import seed.Log
import seed.publish.util.Http
import zio._

object Bintray {
  val Host = "https://bintray.com"

  // TODO Publish upload progress
  def uploadMavenFile(
    http: Http,
    log: Log,
    organisation: String,
    repository: String,
    `package`: String,
    packageGroup: String,
    packageArtefact: String,
    packageClassifier: String,
    packageVersion: String,
    bytes: Array[Byte],
    extension: String
  ): UIO[Unit] = {
    val packageParts = packageGroup.split('.').mkString("/")

    val url =
      s"$Host/api/v1/maven/$organisation/$repository/${`package`}/$packageParts/$packageArtefact/$packageVersion/$packageArtefact-$packageVersion$packageClassifier.$extension"

    http
      .put(url, bytes)
      .option
      .flatMap {
        case None => Task.interrupt
        case Some(response) =>
          decode[FileUploadResponse](response) match {
            case Left(_) =>
              log.error(s"Server response could not be parsed: $response")
              Task.interrupt
            case Right(m) =>
              if (m.message == "success") {
                log.info("Request successful")
                Task.succeed(())
              } else {
                log.error(s"Server reported error:")
                log.error(m.message)
                Task.interrupt
              }
          }
      }
  }

  def publishContent(
    http: Http,
    log: Log,
    organisation: String,
    repository: String,
    `package`: String,
    packageVersion: String
  ): UIO[Unit] = {
    val url =
      s"$Host/api/v1/content/$organisation/$repository/${`package`}/$packageVersion/publish"

    http
      .post(url, Array())
      .option
      .flatMap {
        case None => Task.interrupt
        case Some(response) =>
          decode[ContentPublishResponse](response) match {
            case Left(_) =>
              log.error(s"Server response could not be parsed: $response")
              Task.interrupt
            case Right(m) =>
              if (m.files > 0) {
                log.info(s"${m.files} files were published")
                Task.succeed(())
              } else {
                log.error("No files were published")
                Task.interrupt
              }
          }
      }
  }

  case class FileUploadResponse(message: String)
  case class ContentPublishResponse(files: Int)
}
