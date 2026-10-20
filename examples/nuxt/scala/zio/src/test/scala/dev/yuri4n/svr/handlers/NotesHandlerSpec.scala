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

    // claude 2026-10-20: changed: ZHandler.test provides the handler environment (ADR-0006, Q10)
    /** Runs a handler function with an in-memory `Notes`; gives the response and the created texts. */
    private def run[A](handler: Request => ZIO[HandlerEnv & Notes, Throwable, A], request: Request)
        : Task[(TestResponse[A], List[String])] =
      for
        created <- Ref.make(List.empty[String])
        response <- ZHandler.test(request =>
          handler(request).provideSomeEnvironment[HandlerEnv](_.add[Notes](InMemoryNotes(created))),
        )(request)
        texts <- created.get
      yield (response, texts)

    private def post(body: String) = Request.fake(method = "POST", headers = Map("content-type" -> "application/json"), body = Some(body))

    def spec = suite("Notes handlers")(
      // claude 2026-10-20: changed: the response of ZHandler.test (ADR-0006, Q10)
      test("create trims the text through the model schema and answers 201") {
        for (response, texts) <- run(NotesHandler.create, post("{\"text\":\"  hello  \"}"))
        yield assertTrue(
          response.status.contains(Status.Created),
          response.body == TestResponse.Body.Value(NoteView(1, "hello")),
          texts == List("hello"),
        )
      },
      // claude 2026-10-20: changed: the response of ZHandler.test (ADR-0006, Q10)
      test("create rejects invalid input before the service runs") {
        for result <- run(NotesHandler.create, post("{\"text\":\"   \"}")).either
        yield assertTrue(result == Left(InvalidInput(List(ErrorEntry("must have at least 1 character", Some("text"))))))
      },
      test("with NotesLive, invalid input never takes a database session; valid input takes one") {
        // A pool whose database is down: every session attempt is counted and fails.
        def downPool(attempts: Ref[Int]) = SessionPool(
          Resource.eval[Task, Session[Task]](attempts.update(_ + 1) *> ZIO.fail(new Exception("connection refused"))),
        )
        // claude 2026-10-20: changed: ZHandler.test provides the handler environment (ADR-0006, Q10)
        def withLive(attempts: Ref[Int])(request: Request) =
          ZHandler.test(request =>
            NotesHandler.create(request).provideSomeLayer[HandlerEnv](ZLayer.succeed(downPool(attempts)) >>> NotesLive.layer),
          )(request)
        for
          attempts <- Ref.make(0)
          invalid <- withLive(attempts)(post("{\"text\":\"   \"}")).either
          beforeValid <- attempts.get
          valid <- withLive(attempts)(post("{\"text\":\"hello\"}")).either
          afterValid <- attempts.get
        yield assertTrue(
          invalid.left.toOption.exists(_.isInstanceOf[InvalidInput]), beforeValid == 0,
          valid.left.toOption.exists(_.getMessage == "connection refused"), afterValid == 1,
        )
      },
      // claude 2026-10-20: created: a route that never uses Notes never builds the pool (issue #52)
      test("with NotesLive.lazily, the pool is built only when a note operation runs") {
        def countingPool(builds: Ref[Int]) = builds.update(_ + 1) *> ZIO.fail(new Exception("connection refused"))
        for
          builds <- Ref.make(0)
          unused <- ZHandler.test(_ => ZIO.serviceWith[Notes](_ => "no note").provideSomeLayer[HandlerEnv](NotesLive.lazily(countingPool(builds))))(Request.fake())
          beforeUse <- builds.get
          used <- ZHandler.test(_ => ZIO.serviceWithZIO[Notes](_.list).provideSomeLayer[HandlerEnv](NotesLive.lazily(countingPool(builds))))(Request.fake()).either
          afterUse <- builds.get
        yield assertTrue(
          unused.body == TestResponse.Body.Value("no note"), beforeUse == 0,
          used.left.toOption.exists(_.getMessage == "connection refused"), afterUse == 1,
        )
      },
    )
