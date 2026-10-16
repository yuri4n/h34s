package dev.yuri4n.exts.kyoh3

import scala.concurrent.{ExecutionContext, Future, Promise, TimeoutException}
import scala.concurrent.duration.*
import scala.scalajs.js.timers.{clearTimeout, setTimeout}

/** Fail before MUnit's timeout so test-owned gates and HTTP servers can close. */
private object TestDeadline:
    def apply[A](future: Future[A])(using ExecutionContext): Future[A] =
      val completed = Promise[A]()
      val timeout = setTimeout(45.seconds) {
        completed.tryFailure(new TimeoutException("Timed out waiting for handler lifecycle"))
      }
      future.onComplete { result =>
        clearTimeout(timeout)
        completed.tryComplete(result)
        ()
      }
      completed.future
