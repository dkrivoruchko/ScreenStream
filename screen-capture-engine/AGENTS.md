# ScreenStream Capture Engine module

These instructions apply to work under `screen-capture-engine/`.

## Sources of truth

- Caller-visible contracts belong in `README.md` and `docs/`. Maintainer-facing architecture, component boundaries, and invariants belong in `internal/`; use `internal/README.md` as its index.
- Source and build files own current declarations, descriptors, native symbols, and packaging. Tests provide evidence; they do not redefine documented behavior.
- Treat the current working code as authoritative. Leave unrelated user changes intact and do not investigate Git or staging state unless the user asks.
- Verify external platform decisions against current official documentation. Update the owning public or internal document when behavior or an invariant changes.

## Changes and ownership

- Keep changes within the user's requested scope and prefer the simplest correct solution.
- Give concurrent writers disjoint files. Writers reread live files before editing and after relevant concurrent changes.
- Do not modify public API contracts without user agreement.

## Subagents and responsibilities

- Root stays user-facing, delegates bounded independent tasks, integrates results, and owns final verification. Root does not take over code assigned to an active writer.
- Fresh Sol-High writers own assigned code, tests, and product documentation in disjoint files.
- Substantive technical decisions use two independent Astra-High opinions before a direct cross-check. Two fresh independent Astra-High reviewers who did not write or prepare the implementation review it; the same coherent pair rechecks corrections.
- Use self-contained assignments with `fork_turns: "none"`, no nested agents, and at most eight useful concurrent children. Follow the user's model choice and repository naming convention when provided.
- Root controls shared build resources and resolves overlapping work. Do not fill slots or convene panels for mechanical work.

## Implementation and verification

- Every test follows the canonical [contract-test rules](internal/testing.md#contract-test-rules). Before designing or editing a coroutine test, read the primary guidance linked there and explain why scheduler controls arrange execution without becoming the oracle.
- Use Android CLI for Android-specific work. Prefer a connected physical device for app runs; ask before launching an emulator when no device is connected.
- Run the smallest verification that establishes the requested behavior, then broaden only when risk or a failure warrants it.
- For instruction-only changes, check wording, links, and consistency. Builds or device tests are needed only when a changed claim requires them.
