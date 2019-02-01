package seed.cli.util

import scala.util.{Success, Try}
import com.joefkelley.argyle._

object ArgyleHelpers {
  case class OptionalFreeFlagParser
  (flag: String, collected: Option[String]) extends Arg[Option[String]] {
    override def visit(xs: NonEmptyList[String], mode: ArgMode): Seq[VisitResult[Option[String]]] = mode match {
      case EqualsSeparated =>
        xs match {
          case NonEmptyList(first, rest) =>
            if (flag == first) Seq(VisitConsume(copy(collected = Some("")), rest))
            else if (first.startsWith(flag + '='))
              Seq(VisitConsume(copy(collected = Some(first.drop(flag.length + 1))), rest))
            else Seq(VisitNoop)
        }

      case _ => ???
    }

    override def complete: Try[Option[String]] = Success(collected)
  }

  /**
    * Parser for flags that take an optional value
    *
    * Examples:
    *  --name maps to Some("")
    *  --name=value maps to Some("value")
    */
  def optionalFreeFlag(flag: String): Arg[Option[String]] = OptionalFreeFlagParser(flag, None)
}
