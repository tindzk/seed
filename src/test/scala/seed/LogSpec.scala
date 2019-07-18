package seed

import minitest.SimpleTestSuite

import scala.collection.mutable.ListBuffer

object LogSpec extends SimpleTestSuite {
  test("Check ordering of log levels") {
    assert(LogLevel.Debug == LogLevel.Debug)
    assert(LogLevel.Debug < LogLevel.Error)
    assert(LogLevel.Error > LogLevel.Info)
  }

  test("Set log level to lowest") {
    val result = ListBuffer[String]()
    val log = new Log(result += _, identity, LogLevel.Debug, unicode = false)
    log.debug("Hello World 1")
    log.info("Hello World 2")
    log.error("Hello World 3")
    assertEquals(result.length, 3)
    assert(result.exists(_.contains("Hello World 1")))
    assert(result.exists(_.contains("Hello World 2")))
    assert(result.exists(_.contains("Hello World 3")))
  }

  test("Set log level to highest") {
    val result = ListBuffer[String]()
    val log = new Log(result += _, identity, LogLevel.Error, unicode = false)
    log.debug("Hello World 1")
    log.info("Hello World 2")
    log.error("Hello World 3")
    assert(result.exists(_.contains("Hello World 3")))
  }
}
