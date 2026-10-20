import { file, region } from './regions.mjs'

// claude 2026-10-20: created: one hello handler that declares its route, in Kyo (the default) or ZIO (ADR-0009)
/** Renders `Hello.scala` in the Scala package `scalaPackage`. */
export function render({ api, scalaPackage }) {
  const handler = api === 'zio'
    ? [
        'import dev.yuri4n.exts.zioh3.*',
        'import org.getshaka.nativeconverter.NativeConverter',
        'import zio.ZIO',
        '',
        'import scala.scalajs.js.annotation.JSExportTopLevel',
        '',
        'final case class Greeting(message: String) derives NativeConverter',
        '',
        '@JSExportTopLevel("default", "hello-get")',
        'val hello: EventHandler = ZHandler.define(_ => ZIO.succeed(Greeting("Hello from Scala"))).at(Route.get("/api/hello"))',
      ]
    : [
        'import dev.yuri4n.exts.kyoh3.*',
        'import org.getshaka.nativeconverter.NativeConverter',
        '',
        'import scala.scalajs.js.annotation.JSExportTopLevel',
        '',
        'final case class Greeting(message: String) derives NativeConverter',
        '',
        '@JSExportTopLevel("default", "hello-get")',
        'val hello: EventHandler = KHandler.define(_ => Greeting("Hello from Scala")).at(Route.get("/api/hello"))',
      ]
  return file([`package ${scalaPackage}`, '', ...region('tour', handler)])
}
