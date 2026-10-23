# ADR 0015: Distribute The Template Through Copier, With Updates As Merge Requests

- Status: Accepted
- Date: 2026-10-23

## Context

An adopter starts a service today by copying this repository and renaming it by hand: 251 of its 354 tracked files contain the `swirl` name, and no script renames the group ID, packages, services or images ([Before You Adopt](../code-quality.md#before-you-adopt)).
Nor can a capability be left out: the Kafka, Redis and Keycloak code ships in every copy, and removing one means finding each file and block that uses it.
After that, the copy drifts: a fix to the template reaches the service only if someone ports it.
[ADR 0014](0014-template-scope-and-audience.md) makes "a service generated with only the capabilities it needs, which keeps receiving template improvements" the template's aim, and the roadmap asks for capability selection, versioned template releases and template update merge requests ([TODO.md › Generator And Upgrade Stream](../../TODO.md#generator-and-upgrade-stream)).

Any distribution therefore has to do two things:

1. Generate a project with its own names and only the selected capabilities (Kafka with outbox, Redis cache, Keycloak, generated API clients, web UI), with unselected ones absent from the code.
2. Bring each later template release into an existing, customised project as a reviewable GitLab merge request, with the project's own changes kept, conflicts marked and migration notes attached.

Three families were compared: a templating generator that stores the answers and regenerates with a three-way merge, a Maven archetype with an Angular schematic, and published starters only.
The facts below were checked against each tool's documentation and source in October 2026.

## Decision

Distribute the template through [Copier](https://copier.readthedocs.io/), and deliver its updates as merge requests opened by Renovate.

**Generation.**
`copier copy` asks for the project's identity and capabilities.
The identity covers the README identity sentence, group ID, Java package, service names, image registry, domains, namespaces and identity-provider settings, so the questionnaire replaces a separate initialization script.
Capability flags select files through templated file and directory names (a path part that renders empty is not created) and select blocks inside shared files through Jinja conditions.
Copier writes the answers and the template version (`_commit`, the release tag) to `.copier-answers.yml`, which the generated project commits and never edits by hand.

**Updates.**
`copier update` renders the template version the project was generated from with the stored answers, takes the project's own changes as the difference from that render, renders the new version and reapplies those changes.
Where a change cannot be reapplied, Copier runs a three-way `git merge-file` and leaves the file with conflict markers, marked unmerged in Git.
Each generated project runs Renovate (self-hosted Renovate Runner in GitLab CI), whose built-in `copier` manager watches `.copier-answers.yml`, finds newer template tags, runs `copier update` and opens a merge request.
A conflicted update arrives as a merge request with the markers committed; since late August 2026, Renovate also fails its `renovate/artifacts` check, lists the conflicted files in an "Artifact update problem" comment and does not automerge it.
Migration notes live in the template's GitLab release notes, which Renovate embeds in the merge request; Copier's own `_message_after_update` goes to the console only and never reaches it.
Turning a capability on or off later is the same reviewable operation, `copier update --vcs-ref=:current: --data <flag>=<value>`, once the capability's database migrations follow the rule below.

**Source of truth.**
The reference application in this repository stays a plain, buildable Java and Angular project: it is what contributors run, what CI verifies and what deploys to `int`.
A build step derives the Copier template from it, replacing the reference identifiers with questions and turning capability markers into conditions.
A round-trip check in CI renders the derived template with the reference answers and fails unless the result equals the reference application, so the template cannot drift from the code that is tested and deployed.
The release job publishes the derived template to its own GitLab repository under the same `vX.Y.Z` tag as the release; Copier compares tags as PEP 440 versions, which these are.

**Starters for runtime code.**
Reusable runtime code (request IDs, Problem Details, rate limiting, auditing, Kafka reliability, security defaults, test fixtures) moves into versioned internal starters over time, as the roadmap's starter item plans.
A starter updates as a dependency bump, which merges more reliably than text, and it shrinks the files Copier has to merge.
Copier carries the files a project owns and edits: its POMs, `.gitlab-ci.yml`, Dockerfiles, Kustomize overlays, Flyway migrations, the Angular application, documentation and the example domain.
Shared CI and Kustomize content stays in those files rather than in GitLab CI components or remote Kustomize bases, so one mechanism updates it.

**Rules for the template.**

- New questions need safe defaults, because Renovate runs `copier update --defaults --skip-answered` and a question added in a later release takes its default without a prompt.
- No `_tasks` or `_migrations`. Both run template code on the adopter's runner and need `--trust`, which Renovate passes only when its runner sets `allowScripts: true` and the project sets `ignoreScripts: false`; without both, every update that reaches such a step fails. A one-time move is a plain file change, described in the release notes. Adopting migrations later is a separate decision, because it lets each template release run code in adopters' CI.
- A capability's Flyway migrations live in their own location with their own history table, so turning the capability on later applies them without colliding with, or falling out of order behind, the project's own migrations. Turning a capability off removes its code but never drops its tables.
- A file a template release deletes is deleted from the project even if the project changed it, without a conflict. Release notes list deleted files, including the source files that move into a starter.
- Lockfiles stay template files that change together with their `package.json` or POM. A conflict in a lockfile is resolved by regenerating it, for example with `npm install --package-lock-only`, never by editing markers.
- Binary files the project owns, such as visual-regression baselines, are listed in `_skip_if_exists`, so the template never touches them after generation. Copier builds its update diff without `--binary`, so a project's change to any other binary file is silently replaced by the template's version.

## Rationale

Only the generator family meets the second requirement.
Copier records the answers and the template version in the project and has a documented three-way update that marks conflicts; Renovate already knows how to drive it and how to report a conflicted update on GitLab.
Copier is MIT-licensed, current (9.19.1, October 2026), and needs Python 3.10 and Git, which CI already uses for the repository's checks.

**Cookiecutter with Cruft** also stores answers (`.cruft.json`) and regenerates both versions, but its `git apply -3` produces conflict markers only when the old render's blobs are in the project's history, and otherwise leaves `.rej` files.
It exits successfully and advances `.cruft.json` even when hunks conflicted, opens no merge requests, has no Renovate manager, and its last release was 2.16.0 in December 2024.

**A JHipster blueprint** would inherit `jhipster upgrade`, which regenerates on a separate branch and merges it.
It would also tie the template to JHipster's generated application, which has no transactional outbox, and the upgrade's conflict path is untested upstream.

**A Maven archetype with an Angular schematic** generates once.
The archetype plugin has no update goal: a partial archetype run stops at the first file that already exists and merges only `pom.xml`.
`ng update` migrations run only inside the Angular workspace (`swirlapp-web/`), so they cannot touch `pom.xml`, CI or `infra/`, and they edit code imperatively without conflict markers.
Two generators would also have to agree on one set of answers, and the archetype passes its properties to Velocity as strings, so `#if($kafka)` is true even when the answer is `false`.

**Published starters only** (a parent POM, Spring Boot starters, an npm library, GitLab CI components and remote Kustomize bases) would update through ordinary dependency bumps.
They cannot carry the application code, migrations, Dockerfiles or documentation a project owns, and a version bump never changes code already generated.
Moving code between versions would need OpenRewrite recipes, which change only code that still matches their patterns, mark no conflicts, and are now distributed through an authenticated repository.
Starters stay part of the decision for runtime code, not the whole of it.

**Keeping the template as the source of truth**, with the reference application rendered in CI, was rejected because contributors would edit Jinja instead of Java and TypeScript, and every existing check would run one step removed from the files they change.
The round-trip check gives the same guarantee in the other direction.

## Consequences

- Capability selection needs the reference code arranged so each capability sits in its own files or marked blocks; the starter and module items in the roadmap reduce the markers needed.
- The repository gains the template derivation script, the round-trip check and a release step that publishes the template repository; the template's own CI renders capability combinations and builds each one.
- Generated projects need Renovate Runner with a GitLab token (Developer role, `api` scope), which installs Python and Copier itself; this is a new requirement on adopters' CI.
- A template release that changes many files produces large merge requests; release notes have to say which changes need the adopter's attention.
- Copier becomes a dependency of the template workflow and of every generated project's Renovate, which uses the latest release unless `constraints.copier` pins one; its security releases have to be followed, and several in 2026 closed bypasses of its trust checks.
- Published template tags are immutable: every update renders the version a project was generated from, so a tag that is moved or deleted breaks updates for the projects on it.
- Until capability selection and update merge requests ship, adopters still copy and rename by hand, and [Before You Adopt](../code-quality.md#before-you-adopt) keeps saying so.
