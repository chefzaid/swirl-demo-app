# ADR 0014: Treat The Template As The Product, For Teams Facing Regulated Reviews

- Status: Accepted
- Date: 2026-10-23

## Context

ADR 0002 keeps the user and order domain minimal, and the roadmap's contribution
rules keep new work technical. Neither says who the template is for or what it
is ultimately meant to become. That direction was set on 2026-10-05 in
[TODO.md › Product Direction](../../TODO.md#product-direction-added-2026-10-05),
and it lives only there: the README presents a "deliberately small,
production-shaped full-stack template", the running SPA calls itself a "cluster
lab", and no ADR names the audience or the non-goals.

Without a recorded scope, each change re-decides what belongs in the template,
and the documents drift into describing the aim as if it had already shipped.

## Decision

The product is the template itself. The user and order flow is only the smallest
example that proves each pattern end to end; it is not a product domain.

The template is for Java and Angular teams in banking, insurance, energy and the
public sector, and similar organisations, whose services must pass security,
architecture and supplier reviews (for example under DORA, NIS2 or ISO/IEC 27001)
before they reach production. Its aim is to become the audited service starter
described in Product Direction: a service generated with only the capabilities
it needs, which keeps receiving template improvements, and which ships the
evidence those reviews ask for.

New work follows the roadmap's
[contribution rules](../../TODO.md#contribution-rules-for-roadmap-work): a reusable pattern,
demonstrated by the failure mode it handles, with tests, configuration notes,
operational notes and a removal path.

The template does not aim to:

- grow the user and order example into a business domain
- be a one-shot generated copy that stops receiving template changes
- certify a service or an organisation as compliant; it supplies evidence for
  their own reviews
- add a service mesh, a saga or an API gateway before an example demonstrates
  the failure mode it handles

The README, the guides, the running SPA and the API documents describe a
capability as audited, evidenced or compliant only once its evidence ships with
the template. Until then they describe it as the aim, next to what exists today.

## Rationale

The audience decides which patterns are worth their cost. A team facing a
supplier review needs failure modes, tests, runbooks and evidence more than
breadth, and it needs to keep receiving fixes after it generates a service.
Generators that produce a project once and leave it cannot offer that, and that
gap is where the template can be most useful.

Recording the non-goals keeps the example domain small. It also stops the
template from claiming compliance it cannot prove, which in a regulated review
would cost more trust than the missing feature.

## Consequences

Contributions and roadmap items are judged against this audience. A business
feature, or a pattern without a demonstrated failure mode, needs a strong reason
to be added.

Every surface that describes the template has to say the same thing and keep the
aim apart from what ships today. The identity items in
[TODO.md › Identity And Positioning](../../TODO.md#identity-and-positioning)
bring the README, the guides, the API documents and the SPA in line with this
decision.

Teams outside regulated sectors can still use the template, but its trade-offs,
such as more verification and more delivery machinery than a small service
needs, are chosen for the audience above.
