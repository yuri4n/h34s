<!-- claude 2026-10-19: created: placeholder notes for the next release. When you release, rename this file to <version>.md with `git mv`, replace every NEXT_VERSION, and fill in the other sections. See docs/releases.md, step 3. -->

h34s `NEXT_VERSION`. This placeholder collects notes for the next release while the work lands. The version is not decided yet.

## Breaking changes

<!-- claude 2026-10-19: created: ZIO follows Kyo on finalizer failures (issue #40); changed: streaming-before-return and disconnect logging (review) -->
- **A dying ZIO finalizer keeps the response.** The ZIO adapter now behaves like the Kyo core: a request-scope finalizer that dies is logged on the server as `h34s: a ZIO finalizer failed`, with `Cause.prettyPrint`, and the client gets the value, the `Unit` response or the safe error that the program produced. Before, it turned that response into an opaque 500. This also applies to a streaming handler that fails before it returns its stream. Streamed bodies already behaved this way. A finalizer that dies during a client disconnect is now logged too. Move cleanup that must succeed for the response to be correct into the program. See the [migration guide](https://github.com/yuri4n/h34s/blob/vNEXT_VERSION/docs/migration.md#a-dying-zio-finalizer-keeps-the-response).
