# ADR 0013: Keep The Template And Generated Code Under GPL 3.0

- Status: Accepted
- Date: 2026-10-14

## Context

The repository has carried the GPL 3.0 text in `LICENSE` since 2026-10-05, but the build metadata declared no license, and the roadmap asked whether a permissive license or a generator exception should replace it. Code copied from a GPL template into a service carries the GPL with it: a service distributed to a client must then be offered under the GPL, with its source.

The options were Apache 2.0, MIT, GPL 3.0 with a generator exception, or GPL 3.0 unchanged.

## Decision

The owner chose to keep GPL 3.0 unchanged for the template and the code it generates. `LICENSE` holds the full text, the README states it, and the Maven parent POM declares it so that published artifacts and SBOMs carry it.

## Consequences

- Services built from the template and distributed to others must be released under the GPL 3.0, with their source. Organisations that cannot accept that cannot adopt the template as is.
- Running a generated service as a network service without distributing it does not trigger the GPL's source obligation; only distribution does (the AGPL, not chosen here, would close that gap).
- Changing the license later needs the agreement of every contributor whose code is still present, so outside contributions should come with that agreement in mind.
