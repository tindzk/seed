package seed.util

import java.util.concurrent.{
  CompletableFuture,
  Executor,
  RejectedExecutionException
}
import scala.concurrent.CancellationException
import zio._

object ZioHelpers {
  def fromCompletableFuture[T](future: => CompletableFuture[T]): Task[T] =
    Task.descriptorWith(
      d =>
        ZIO
          .effect(future)
          .flatMap(
            f =>
              Task
                .effectAsync { (cb: Task[T] => Unit) =>
                  f.whenCompleteAsync(
                    (v: T, e: Throwable) =>
                      if (e == null) cb(Task.succeed(v))
                      else if (!e.isInstanceOf[CancellationException])
                        cb(Task.fail(e)),
                    (
                      r =>
                        if (!d.executor.submit(r))
                          throw new RejectedExecutionException(
                            "Rejected: " + r.toString
                          )
                      ): Executor
                  )
                }
                .onTermination(_ => UIO(if (!f.isDone) f.cancel(true)))
          )
    )
}
