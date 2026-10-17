package dev.yuri4n.exts.h3

import dev.yuri4n.exts.h3.H3Fixtures.{FakeRequest, FakeResponse}
import dev.yuri4n.exts.h3.internal.H3Event
import munit.FunSuite

import scala.scalajs.js

class RequestSuite extends FunSuite:
    private def snapshot(
        method: String = "get",
        url: String = "/test",
        headers: Seq[(String, String)] = Nil,
        context: Option[js.Object] = None,
        params: Map[String, String] = Map.empty,
    ): Request =
      val event: H3Event = H3Fixtures.event(FakeRequest(method, url, headers), FakeResponse(), context)
      if params.nonEmpty then event.context("params") = js.Dictionary(params.toSeq*)
      Request.fromEvent(event)

    test("the snapshot reads method, path, headers, query, router params and cookies") {
      val request = snapshot(
        url = "/notes/a%20b?tag=x&tag=y&q=hello%20world&flag",
        headers = Seq(
          "Accept" -> "application/json",
          "X-Trace" -> "one",
          "x-trace" -> "two",
          "Cookie" -> "session=abc; theme=dark%20blue",
        ),
        params = Map("slug" -> "a%20b"),
      )
      assertEquals(request.method, "GET")
      assertEquals(request.path, "/notes/a%20b?tag=x&tag=y&q=hello%20world&flag")
      assertEquals(request.header("accept"), Some("application/json"))
      assertEquals(request.header("ACCEPT"), Some("application/json"))
      assertEquals(request.header("x-trace"), Some("one, two"))
      assertEquals(request.headerValues("X-Trace"), List("one", "two"))
      assertEquals(request.headers.get("x-trace"), Some("one, two"))
      assertEquals(request.header("missing"), None)
      assertEquals(request.query.get("q"), Some("hello world"))
      assertEquals(request.query.get("tag"), Some("x"))
      assertEquals(request.queryAll.get("tag"), Some(List("x", "y")))
      assertEquals(request.query.get("flag"), Some(""))
      assertEquals(request.routerParam("slug"), Some("a b"))
      assertEquals(request.routerParams, Map("slug" -> "a b"))
      assertEquals(request.cookie("session"), Some("abc"))
      assertEquals(request.cookie("theme"), Some("dark blue"))
      assertEquals(request.cookies.size, 2)
    }

    test("a request without query, params or cookies has empty maps") {
      val request = snapshot()
      assertEquals(request.query, Map.empty[String, String])
      assertEquals(request.routerParams, Map.empty[String, String])
      assertEquals(request.cookies, Map.empty[String, String])
    }

    test("bearerToken accepts exactly one well-formed Bearer header") {
      def token(values: String*): Option[String] = snapshot(headers = values.map("Authorization" -> _)).bearerToken
      assertEquals(token("Bearer abc.def"), Some("abc.def"))
      assertEquals(token("bearer abc"), Some("abc"))
      assertEquals(token(), None)
      assertEquals(token("Bearer one", "Bearer two"), None)
      assertEquals(token("Basic dXNlcjpwYXNz"), None)
      assertEquals(token("Bearer a b"), None)
      assertEquals(token("Bearer "), None)
      assertEquals(token("Bearer  abc"), None)
    }

    test("development is enabled only by boolean true in the Nitro request record") {
      assert(snapshot(context = Some(H3Fixtures.development(true))).development)
      List[js.Any](false, "true", 1, js.undefined, null).foreach { value =>
        assert(!snapshot(context = Some(H3Fixtures.development(value))).development, s"$value")
      }
      assert(!snapshot(headers = Seq("x-h34s-development" -> "true")).development)
    }

    test("Request.fake builds the same view without h3") {
      val request = Request.fake(
        method = "post",
        path = "/notes",
        headers = Map("Content-Type" -> "application/json", "Authorization" -> "Bearer t0k3n"),
        query = Map("page" -> List("2", "3")),
        routerParams = Map("id" -> "7"),
        cookies = Map("session" -> "abc"),
        body = Some("{}"),
        development = true,
      )
      assertEquals(request.method, "POST")
      assertEquals(request.header("content-type"), Some("application/json"))
      assertEquals(request.bearerToken, Some("t0k3n"))
      assertEquals(request.query.get("page"), Some("2"))
      assertEquals(request.queryAll.get("page"), Some(List("2", "3")))
      assertEquals(request.routerParam("id"), Some("7"))
      assertEquals(request.cookie("session"), Some("abc"))
      assert(request.development)
    }

    test("toString never shows headers or credentials") {
      val request = Request.fake(path = "/admin", headers = Map("Authorization" -> "Bearer secret-token"))
      assertEquals(request.toString, "Request(GET /admin)")
      assert(!request.toString.contains("secret-token"))
    }
end RequestSuite
