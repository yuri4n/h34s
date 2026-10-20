# Architecture decision records

<!-- claude 2026-10-20: created: title and introduction -->

An architecture decision record (ADR) records one decision: its context, the decision itself, every option that was considered with the reason it was not chosen, the consequences and the follow-ups. The records start on 2026-10-20, when the maintainer accepted the open recommendations of issues #51, #52 and #54 ("just take the recommended decisions and record the the other options and the why").

## Index

<!-- claude 2026-10-20: changed: index, with the row of ADR-0014 -->

| Number                                                   | Title                                                                                  | Status   | Date       | Source issue |
| -------------------------------------------------------- | -------------------------------------------------------------------------------------- | -------- | ---------- | ------------ |
| [ADR-0001](0001-delivery-process.md)                     | Delivery process: the orchestrating session merges green pull requests                 | accepted | 2026-10-20 | #51          |
| [ADR-0002](0002-next-release-name.md)                    | The next release is 0.3.0-alpha.1                                                      | accepted | 2026-10-20 | #51          |
| [ADR-0003](0003-registries-stay-private.md)              | The package registries stay private this round                                         | accepted | 2026-10-20 | #54, #21     |
| [ADR-0004](0004-streams-are-sent.md)                     | Streams are sent with `Response.sendStream`, not returned                              | accepted | 2026-10-20 | #51          |
| [ADR-0005](0005-fetch-in-handlers.md)                    | `$fetch` in handlers: Nitro's `event.fetch`, in-process credentials only               | accepted | 2026-10-20 | #51          |
| [ADR-0006](0006-handler-environment-and-test-helpers.md) | One merged handler environment, and public test helpers                                | accepted | 2026-10-20 | #51          |
| [ADR-0007](0007-server-sent-events.md)                   | Server-sent events through an h34s helper over `sendStream`                            | accepted | 2026-10-20 | #51          |
| [ADR-0008](0008-streaming-support-limits.md)             | Streaming support limits: document, list tested presets only, check the h3 copy        | accepted | 2026-10-20 | #51, #36     |
| [ADR-0009](0009-scaffolding-adoption-paths.md)           | Scaffold the three adoption paths with `h34s-scala init`                               | accepted | 2026-10-20 | #54          |
| [ADR-0010](0010-homepage-mini-project-view.md)           | Homepage mini-project view: an in-place Kyo \| ZIO toggle and a project tour component | accepted | 2026-10-20 | #52          |
| [ADR-0011](0011-no-content-body-typing.md)               | A no-content body keeps the 204 rule and the `undefined` type                          | accepted | 2026-10-20 | #46          |
| [ADR-0012](0012-inner-scope-finalizer-semantics.md)      | Finalizer failures in a handler's own scopes follow each runtime's rules               | accepted | 2026-10-20 | #50, #40     |
| [ADR-0014](0014-named-model-types.md)                    | Named model types: `WireType.Named`, one models file per output, and `#h34s/models`    | accepted | 2026-10-20 | #66          |

## When to write an ADR

<!-- claude 2026-10-20: created: when to write an ADR -->

Write an ADR for a decision that changes a public API, a process, a build or a release rule. A bug fix or an internal refactor that keeps every promise does not need one.

1. Copy the structure of an existing record: a title line `# ADR-NNNN: <title>`, the status block (status, date, who decided, source), then the sections Context, Decision, Options considered, Consequences and Follow-ups.
2. Take the next free number. Numbers are never reused.
3. List every option that the source considered, with the source's reason for not choosing it. When the source gives no reason, say so. Do not invent options or reasons.
4. Add the record to the index above, in the same pull request as the decision's first change.
5. To change a decision, write a new record and set the old one's status to "superseded by ADR-NNNN". Do not rewrite an accepted record.

## How ADRs relate to the design record

<!-- claude 2026-10-20: created: relation to the design record -->

[`docs/design/kyo-core.md`](../kyo-core.md) keeps the narrative: why h34s is built the way it is, the numbered decision items, the deviations and the library facts. An ADR records one decision with its alternatives. From 2026-10-20 on, the design record links to the ADRs instead of repeating their alternatives. When an ADR changes the Kyo core, the ZIO adapter or a bridge, the implementing pull request also updates the design record, as `AGENTS.md` asks.
