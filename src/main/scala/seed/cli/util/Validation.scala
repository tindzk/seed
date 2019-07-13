package seed.cli.util

object Validation {
  def unpack[T](list: List[Either[String, T]]): Either[List[String], List[T]] = {
    val (errors, values) = (
      list.collect { case Left(error) => error },
      list.collect { case Right(v) => v }
    )

    if (errors.nonEmpty) Left(errors) else Right(values)
  }
}
