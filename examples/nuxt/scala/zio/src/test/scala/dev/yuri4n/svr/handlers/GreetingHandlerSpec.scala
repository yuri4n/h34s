package dev.yuri4n.svr.handlers

import dev.yuri4n.exts.zioh3.*
import dev.yuri4n.svr.greetings.{Greeting, GreetingConfig, GreetingRequest, Greetings}
import zio.ZIO
import zio.test.*

/** The greeting route's two steps run without h3, Nitro or a server: the model schema reads the body,
  * then `Greetings` writes the message. The exported route runs the same steps inside `AppHandler`;
  * `scripts/smoke.mjs` checks it over HTTP.
  */
object GreetingHandlerSpec extends ZIOSpecDefault:
    // claude 2026-10-20: created: the greeting service with a fixed salutation (issue #52)
    private val greetings = Greetings.live(GreetingConfig("Hello"))

    // claude 2026-10-20: created: the route's two steps, as `personalizedGreeting` runs them (issue #52)
    private val greet: Request => ZIO[HandlerEnv, Throwable, Greeting] = request =>
      (for
        input    <- request.body.json[GreetingRequest]
        greeting <- ZIO.serviceWithZIO[Greetings](_.greet(input.name))
      yield greeting).provideSomeEnvironment[HandlerEnv](_.add[Greetings](greetings))

    // claude 2026-10-20: created: a JSON POST request (issue #52)
    private def post(body: String) = Request.fake(method = "POST", headers = Map("content-type" -> "application/json"), body = Some(body))

    def spec = suite("Greeting handler")(
      // claude 2026-10-20: created: a valid name gives the greeting (issue #52)
      test("a valid name gives the greeting as the body") {
        for response <- ZHandler.test(greet)(post("{\"name\":\"  Scala \"}"))
        yield assertTrue(response.body == TestResponse.Body.Value(Greeting("Hello, Scala!")))
      },
      // claude 2026-10-20: created: a blank name is a 422 on the name path (issue #52)
      test("a blank name is invalid input on the name path, which h34s answers with 422") {
        for result <- ZHandler.test(greet)(post("{\"name\":\"   \"}")).either
        yield assertTrue(result == Left(InvalidInput(List(ErrorEntry("must have at least 1 character", Some("name"))))))
      },
    )
