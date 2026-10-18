package dev.yuri4n.svr.handlers

import cats.effect.Resource
import dev.yuri4n.exts.zioh3.*
import dev.yuri4n.svr.notes.{Note, NoteView, Notes, NotesLive}
import dev.yuri4n.svr.resources.SessionPool
import skunk.Session
import zio.*
import zio.test.*

/** The handler functions run without h3, Nitro or a database: a fake request and a fake `Notes`. */
object NotesHandlerSpec extends ZIOSpecDefault:
    /** An in-memory interpreter that records created texts. */
    private final class InMemoryNotes(created: Ref[List[String]]) extends Notes:
        def create(text: String): Task[Note] = created.updateAndGet(_ :+ text).map(all => Note(all.size, text))
        def list: Task[List[Note]]           = created.get.map(_.zipWithIndex.map((text, index) => Note(index + 1, text)))

    private def run[A](program: ZIO[HandlerEnv & Notes, Throwable, A]): Task[(ResponseHead, A, List[String])] =
      for
        created <- Ref.make(List.empty[String])
        result <- ZIO.scoped(ZIO.stateful[Scope](ResponseHead.empty)(
          program.zip(Response.head).provideSomeEnvironment[ZState[ResponseHead] & Scope](_.add[Notes](InMemoryNotes(created))),
        ))
        texts <- created.get
      yield (result._2, result._1, texts)

    private def post(body: String) = Request.fake(method = "POST", headers = Map("content-type" -> "application/json"), body = Some(body))

    def spec = suite("Notes handlers")(
      test("create trims the text through the model schema and answers 201") {
        for (head, note, texts) <- run(NotesHandler.create(post("{\"text\":\"  hello  \"}")))
        yield assertTrue(head.status.contains(Status.Created), note == NoteView(1, "hello"), texts == List("hello"))
      },
      test("create rejects invalid input before the service runs") {
        for result <- run(NotesHandler.create(post("{\"text\":\"   \"}"))).either
        yield assertTrue(result == Left(InvalidInput(List(ErrorEntry("must have at least 1 character", Some("text"))))))
      },
      test("with NotesLive, invalid input never takes a database session; valid input takes one") {
        // A pool whose database is down: every session attempt is counted and fails.
        def downPool(attempts: Ref[Int]) = SessionPool(
          Resource.eval[Task, Session[Task]](attempts.update(_ + 1) *> ZIO.fail(new Exception("connection refused"))),
        )
        def withLive[A](attempts: Ref[Int])(program: ZIO[HandlerEnv & Notes, Throwable, A]) =
          ZIO.scoped(ZIO.stateful[Scope](ResponseHead.empty)(
            program.provideSomeLayer[HandlerEnv](ZLayer.succeed(downPool(attempts)) >>> NotesLive.layer),
          ))
        for
          attempts <- Ref.make(0)
          invalid <- withLive(attempts)(NotesHandler.create(post("{\"text\":\"   \"}"))).either
          beforeValid <- attempts.get
          valid <- withLive(attempts)(NotesHandler.create(post("{\"text\":\"hello\"}"))).either
          afterValid <- attempts.get
        yield assertTrue(
          invalid.left.toOption.exists(_.isInstanceOf[InvalidInput]), beforeValid == 0,
          valid.left.toOption.exists(_.getMessage == "connection refused"), afterValid == 1,
        )
      },
    )
