package seed.cli.util

object Exit {
  var TestCases = false

  def error(): Throwable = {
    if (!TestCases) System.exit(1)
    new Throwable
  }
}
