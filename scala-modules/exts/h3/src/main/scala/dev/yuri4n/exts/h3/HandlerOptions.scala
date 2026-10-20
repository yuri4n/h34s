package dev.yuri4n.exts.h3

/** How a successful value body is sent. Unit bodies (204) and streams are never wrapped. */
enum SuccessEnvelope:
    /** `{ "data": <value> }` (the default). */
    case Data

    /** The converted value as is. A value that converts to `null` or `undefined` has no body (204). */
    case Raw

/** How error entries are sent. */
enum ErrorEnvelope:
    /** Always `{ "errors": [ ... ] }` (the default). */
    case List

    /** `{ "error": { ... } }` for exactly one entry, `{ "errors": [ ... ] }` for several. */
    case SingleOrList

// claude 2026-10-19: changed: `requireJsonContentType` defaults to `true` (issue #41)
/** Options for every request of one handler definition.
  *
  * @param success
  *   the envelope of value bodies
  * @param errors
  *   the envelope of error entries
  * @param defaults
  *   response head entries applied on success and on failure (for example `ResponseHead.noStore`)
  * @param bodyLimit
  *   the limit of `request.body.json` and `request.body.text` reads that do not pass their own
  * @param requireJsonContentType
  *   when `true` (the default), `request.body.json` answers 415 for a request that has a body but
  *   no `Content-Type`. Browsers send no content type for some cross-site requests that need no CORS
  *   preflight (a `fetch` with a byte body, `navigator.sendBeacon`). Set it to `false` only for a
  *   handler whose clients cannot send a content type and that does not change state for a
  *   cookie-authenticated user. `$fetch` and `curl --json` send `application/json`.
  */
final case class HandlerOptions(
    success: SuccessEnvelope = SuccessEnvelope.Data,
    errors: ErrorEnvelope = ErrorEnvelope.List,
    defaults: ResponseHead = ResponseHead.empty,
    bodyLimit: BodyLimit = BodyLimit.default,
    requireJsonContentType: Boolean = true,
)

object HandlerOptions:
    // claude 2026-10-19: changed: the default also requires a JSON content type (issue #41)
    /** `{ "data": ... }` bodies, `{ "errors": [...] }` errors, no defaults, 1 MiB body limit, and a
      * JSON content type for `request.body.json`.
      */
    val default: HandlerOptions = HandlerOptions()
