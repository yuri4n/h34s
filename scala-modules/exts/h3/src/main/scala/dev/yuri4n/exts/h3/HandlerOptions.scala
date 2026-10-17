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
  *   when `true`, `request.body.json` answers 415 for a body without a `Content-Type`. Browsers send
  *   no content type for some cross-site requests that need no CORS preflight (a `fetch` with a
  *   byte body, `navigator.sendBeacon`), so set it for cookie-authenticated routes that change
  *   state. `$fetch` and `curl --json` send `application/json`.
  */
final case class HandlerOptions(
    success: SuccessEnvelope = SuccessEnvelope.Data,
    errors: ErrorEnvelope = ErrorEnvelope.List,
    defaults: ResponseHead = ResponseHead.empty,
    bodyLimit: BodyLimit = BodyLimit.default,
    requireJsonContentType: Boolean = false,
)

object HandlerOptions:
    /** `{ "data": ... }` bodies, `{ "errors": [...] }` errors, no defaults, 1 MiB body limit. */
    val default: HandlerOptions = HandlerOptions()
