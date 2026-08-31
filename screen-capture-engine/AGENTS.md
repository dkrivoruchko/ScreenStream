# ScreenStream Capture Engine module

These instructions apply to work under `screen-capture-engine/`.

## Sources of truth

- Caller-visible contracts belong in `README.md` and `docs/`. Maintainer-facing architecture, component boundaries, and invariants belong in `internal/`; use `internal/README.md` as its index.
- Source and build files own current declarations, descriptors, native symbols, and packaging. Tests provide evidence; they do not redefine documented behavior.
- Verify external-platform decisions against current official documentation; use other primary sources only for explicit gaps. Use the live repository for repository facts.
- Update the owning public or internal document when its behavior or invariant changes.

## Coordination

- Root is the sole user-facing orchestrator; the developer owns product and scope decisions. Root assigns work, reconciles independent evidence, controls file ownership, verifies completion, and reports the result without taking over an active delegated assignment.
- Give each subagent one bounded, self-contained assignment. Subagents do not spawn subagents, consult other models, intentionally read or edit `WORK_STATE.md`, or claim overall completion.
- If a subagent accidentally sees isolated `WORK_STATE.md` content, it discloses and ignores it. Replace the agent only when that exposure materially compromised independence.
- For nontrivial technical research or solution search, Root starts two independent `gpt-5.6-sol` subagents with `high` reasoning. Purely mechanical work skips the research panel.
- Reuse a focused agent whose prior work was read-only, who has not seen conclusions for the current decision, and who has no role or file conflict. Use a fresh agent when context is stale, contaminated, overloaded, or mismatched; a new turn alone does not invalidate an agent.
- Independent researchers receive the same neutral assignment with `fork_turns: "none"`. A failed, timed-out, or unavailable result is not a handoff: retry or replace it, or report the exact blocker. Resolve disagreement from repository evidence and authoritative sources, not by voting.
- Each subagent performs two self-review passes before handoff. Root does the same before its final report and checks that synthesis preserves every material finding, objection, option, and limitation. Use a third pass only after material revision or when risk warrants it.

## Work state

- Root is the exclusive agent reader and writer of `WORK_STATE.md` and rereads it before substantial planning or after context loss.
- Keep only unresolved state that must survive restarts: active work, decisions, material handoffs needed for continuation, pending authorization, blockers, validation gaps, deferred work, and continuation points.
- Record material changes promptly. Remove completed, integrated, superseded, or obsolete detail; stable contracts belong in public or internal documentation.

## Implementation and verification

- Research and proposals remain read-only until the developer authorizes implementation. Do not change production source to enable testing without approval of the exact production change.
- Git is read-only for every agent; authorized edits use non-Git tools and ignore staging state.
- Give concurrent writers disjoint files. Writers reread live files before editing and after relevant concurrent changes.
- Every test follows the canonical [contract-test rules](internal/testing.md#contract-test-rules). Before designing or editing a coroutine test, read the primary guidance linked there and explain why scheduler controls arrange execution without becoming the oracle.
- A fresh nonparticipant reviews implementation read-only, including top-level Kotlin ownership decisions. The writer fixes findings, the same reviewer rechecks, and Root runs the smallest relevant verification.
- Close when authorized work is integrated and reviewed, unresolved issues are deferred explicitly, and `WORK_STATE.md` contains only current unresolved state.
