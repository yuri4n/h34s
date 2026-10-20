<!-- claude 2026-10-19: created: placeholder notes for the next release. When you release, rename this file to <version>.md with `git mv`, replace every NEXT_VERSION, and fill in the other sections. See docs/releases.md, step 3. -->

h34s `NEXT_VERSION`. This placeholder collects notes for the next release while the work lands. The version is not decided yet.

## Breaking changes

<!-- claude 2026-10-19: created: the strict JSON content type default (issue #41); changed: requests without a body are not affected (review) -->
- **JSON reads require a content type.** `HandlerOptions.requireJsonContentType` now defaults to `true`, so `request.body.json[A]` answers 415 for a request that has a body but no `Content-Type`. A request without a body is not affected. Browsers send some cross-site requests without a content type and without a CORS preflight; the new default keeps them away from JSON reads without any setup. `$fetch` with an object body and `curl --json` already send `application/json`. To keep the old behavior for one handler, pass `HandlerOptions(requireJsonContentType = false)`. `Request.fake` follows the same default and takes the same parameter. See the [migration guide](https://github.com/yuri4n/h34s/blob/vNEXT_VERSION/docs/migration.md#json-reads-require-a-content-type).
