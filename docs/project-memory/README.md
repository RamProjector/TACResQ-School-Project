# TACResQ Project Memory

**Snapshot date:** 2026-11-16  
**Purpose:** Preserve durable product decisions, research evidence, verified implementation facts, and handoff context across contributors and future development sessions.

This directory is the repository's concise, version-controlled memory. It is not a transcript archive and must not replace source code, migrations, CI results, or live environment inspection.

## Memory index

- [Decisions and constraints](DECISIONS.md) — durable product requirements, user-approved constraints, and architectural choices.
- [Research evidence](RESEARCH_EVIDENCE.md) — credible 2020–2029 sources, the claims they support, and the boundaries they impose.\n- [Security and reliability control matrix](SECURITY_RELIABILITY_CONTROL_MATRIX.md) — standards mapped to current source, pending PRs, live Supabase drift, acceptance tests, and remaining limitations.
- [Implementation baseline](IMPLEMENTATION_STATUS.md) — implemented, partially verified, and unverified capabilities as of the snapshot date.
- [Next-session handoff](NEXT_SESSION_HANDOFF.md) — active PRs, exact heads, current gates, and the next safe actions.

## Evidence hierarchy

When facts disagree, use this order:

1. Current source code, migrations, and automated tests for repository behavior.
2. Exact-head CI results for what CI actually executed.
3. Read-only live environment inspection for deployed database/configuration facts, with the inspection date.
4. Official vendor documentation and peer-reviewed/recognized standards for external behavior and security guidance.
5. Existing project documentation and conversation notes as context, not proof.

Never turn a planned feature, passing static contract, successful build, or source-code presence into a claim that a feature works on a physical device or in production.

## Update policy for every substantial PR

A PR that changes architecture, security, product behavior, research assumptions, or release readiness must update the relevant memory page in the same PR. Keep changes focused: routine UI fixes do not need a full memory rewrite.

Every research-backed change should record:

- The problem and affected user/system flow.
- The source URL, publisher, source date/version when available, and access date.
- The exact claim supported by the source.
- How the finding applies to TACResQ and what it does **not** prove.
- The implementation, regression tests, and remaining manual/device checks.

Keep these categories distinct:

- **Requirement:** a durable product constraint or user-approved decision.
- **Repository fact:** something confirmed in the current source or migration history.
- **Live fact:** something read from a live service, with date and environment.
- **Research finding:** an external source's documented claim.
- **Recommendation:** a proposed improvement, not yet implemented.
- **Unverified:** evidence is incomplete.

Do not store credentials, tokens, real incident records, precise personal locations, private account identifiers, or raw chat transcripts here. Keep secrets in the approved secret manager and keep test data synthetic.

## Scope boundary

TACResQ is separate from LifeLink. Never import LifeLink's donor workflows, roles, requirements, or assumptions into this repository.
