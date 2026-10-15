package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.h3.{AnyEvHandlerRes, CommonEvHandler, H3Event1}
import dev.yuri4n.exts.h3.given
import kyo.{Abort, Async, Env, Fiber, Frame, Result, Scope, Sync, `<`}
import org.getshaka.nativeconverter.{NativeConverter, fromNative}
import dev.yuri4n.h34s.facades.h3.mod.{
  EventHandlerObject,
  EventHandlerRequest,
  defineEventHandler as h3DefineEventHandler,
  readBody as h3ReadBody,
  setResponseStatus as h3SetResponseStatus,
}

import java.io.{PrintWriter, StringWriter}
import scala.annotation.targetName
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.util.{Failure, Success}
import scala.util.control.{NoStackTrace, NonFatal}

type HandlerEffects = Async & Scope & Abort[Throwable] & Env[KH3Event]
type TaskHandler[A] = A < HandlerEffects

/** Request context supplied by KHandler; application services use ordinary Kyo Env. */
final case class KH3Event(private val event: H3Event1):
    export event.{context, handled, headers, method, node, path, toJSON, web}

    def rawEvent: H3Event1 = event

    def readBody[A: NativeConverter](using Frame): A < (Async & Abort[Throwable]) =
      given ExecutionContext = ExecutionContext.global
      Abort.catching[Throwable] {
        Sync.defer {
          h3ReadBody[A, H3Event1, js.Any](event).toFuture.transform(result => Success(result))
        }
      }.map { completed =>
        // Async.fromFuture treats rejected Futures as panics. Reify the native
        // I/O outcome first so callers can recover through Abort[Throwable].
        Async.fromFuture(completed).map {
          case Success(value) => value
          case Failure(error) => Abort.fail(error)
        }
      // Decoding exceptions are defects, including a converter that throws SafeError.
      // Only an explicit typed failure may approve a message for the HTTP response.
      }.map(_.fromNative[A])

    def setResponseStatus(status: Int)(using Frame): Unit < Sync =
      Sync.defer(h3SetResponseStatus(event, status))

    private[kyoh3] def developmentMode: Boolean =
      val config = event.context.asInstanceOf[js.Dynamic].h34s
      if config == null || js.isUndefined(config) then false
      else
        val value = config.development
        js.typeOf(value) == "boolean" && value.asInstanceOf[Boolean]

/** Only an explicitly aborted SafeError is approved for a public response. */
trait SafeError extends RuntimeException with NoStackTrace:
    def message: String
    def status: Int = 400
    override def getMessage: String = message

object KHandler:
    /** Supply application Env dependencies before defining a native h3 handler. */
    def define[A: NativeConverter](program: => TaskHandler[A])(using Frame): CommonEvHandler =
      defineNative(Sync.defer(program).map(_.toNative))

    /** A Unit handler returns native JavaScript undefined without requiring a converter. */
    @targetName("defineUnit")
    def define(program: => TaskHandler[Unit])(using Frame): CommonEvHandler =
      defineNative(Sync.defer(program).map(_ => js.undefined.asInstanceOf[js.Any]))

    def getEvent(using Frame): KH3Event < Env[KH3Event] = Env.get[KH3Event]

    def readBody[A: NativeConverter](using Frame): TaskHandler[A] =
      getEvent.map(_.readBody[A])

    def setResponseStatus(status: Int)(using Frame): TaskHandler[Unit] =
      getEvent.map(_.setResponseStatus(status))

    def failWith(status: Int, message: String)(using Frame): Nothing < Abort[Throwable] =
      Abort.fail(ApprovedFailure(status, message))

    private final case class ApprovedFailure(override val status: Int, message: String) extends SafeError

    private def defineNative(program: => TaskHandler[js.Any])(using Frame): CommonEvHandler =
      val native = js.Any.fromFunction1 { (event: H3Event1) =>
        runRequest(KH3Event(event), program)
      }.asInstanceOf[CommonEvHandler]
      h3DefineEventHandler[EventHandlerRequest, AnyEvHandlerRes](EventHandlerObject(native))

    private def runRequest(event: KH3Event, program: => TaskHandler[js.Any])(using Frame): js.Promise[js.Any] =
      import kyo.AllowUnsafe.embrace.danger
      given ExecutionContext = ExecutionContext.global

      val lifecycle = new RequestLifecycle(event.rawEvent)
      lifecycle.listen()

      // Never interrupt this supervisor. A scoped worker owns the request resources;
      // Fiber.init's release interrupts the worker and awaits its Scope finalizers.
      // Interrupting Scope.run itself would expose Kyo's detached abnormal-exit drain.
      val supervised: js.Any < (Async & Abort[Throwable]) = Scope.run {
        Sync.defer {
          if lifecycle.disconnected then js.undefined.asInstanceOf[js.Any]
          else
            val provided: js.Any < (Async & Scope & Abort[Throwable]) =
              Env.run(event)(Sync.defer(program))
            Fiber.init(provided).map { worker =>
              Sync.defer {
                lifecycle.setInterrupt(() => { worker.unsafe.interrupt(); () })
              }.andThen(worker.get)
            }
        }
      }

      val supervisor = Fiber.Unsafe.init(Abort.run[Throwable](supervised))
      supervisor.toFuture().transform { completed =>
        try
          lifecycle.unlisten()
          val response =
            if lifecycle.disconnected then lifecycle.cancelledResponse()
            else completed match
                case Success(result) => render(event, result)
                case Failure(error)  => unexpected(event, error)
          Success(response)
        catch
          case NonFatal(error) =>
            Success(if lifecycle.disconnected then lifecycle.cancelledResponse() else unexpected(event, error))
      }.toJSPromise

    private def render(event: KH3Event, result: Result[Throwable, js.Any]): js.Any =
      result.fold(
        identity,
        {
          case safe: SafeError =>
            h3SetResponseStatus(event.rawEvent, safe.status)
            js.Dynamic.literal(errors = js.Array(safe.message))
          case error => unexpected(event, error)
        },
        error => unexpected(event, error),
      )

    private def unexpected(event: KH3Event, error: Throwable): js.Any =
      val cause = if error == null then new IllegalStateException("Null handler failure") else error
      val writer = new StringWriter()
      cause.printStackTrace(new PrintWriter(writer))
      val details = writer.toString
      js.Dynamic.global.console.error("Unhandled Kyo handler failure", details)
      h3SetResponseStatus(event.rawEvent, 500)
      val response = js.Dynamic.literal(errors = js.Array("Internal server error"))
      if event.developmentMode then response.updateDynamic("debug")(js.Dynamic.literal(cause = details))
      response

    /** Node close notifications are transport events, so keep their callbacks outside the effect program. */
    private final class RequestLifecycle(event: H3Event1):
        private val response = event.node.res.asInstanceOf[js.Dynamic]
        private var interrupted = isClosed
        private var interrupt: () => Unit = () => ()
        private var listening = false

        private val close: js.Function0[Unit] = () =>
          interrupted = true
          interrupt()

        def disconnected: Boolean = interrupted || isClosed

        private def isClosed: Boolean =
          def flag(name: String): Boolean =
            val value = response.selectDynamic(name)
            js.typeOf(value) == "boolean" && value.asInstanceOf[Boolean]
          flag("destroyed") || flag("closed") || flag("writableEnded")

        def listen(): Unit =
          if !disconnected && js.typeOf(response.on) == "function" then
            listening = true
            response.on("close", close)
            if isClosed then close()

        def setInterrupt(callback: () => Unit): Unit =
          interrupt = callback
          if disconnected then callback()

        def unlisten(): Unit =
          if listening then
            listening = false
            if js.typeOf(response.removeListener) == "function" then response.removeListener("close", close)
            else if js.typeOf(response.off) == "function" then response.off("close", close)
          interrupt = () => ()

        def cancelledResponse(): js.Any =
          // h3 1.15's handled getter does not consider a destroyed response socket.
          // Claim this request so its dispatcher does not manufacture a fallback 404.
          event.asInstanceOf[js.Dynamic].updateDynamic("_handled")(true)
          js.undefined
