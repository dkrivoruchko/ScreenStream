# Screen Capture Engine Design-Gate Tracker

## Authority

This tracker defines the working rules, current gate status, and next handoff. It is not a sixth design
document. The complete design authority is:

1. `design/01_design.md` — product behavior and public API;
2. `design/02_architecture.md` — technical architecture and algorithms;
3. `design/03_verification.md` — acceptance scenarios, checks, and paper traces;
4. `design/04_gate_a.md` — Gate-A decision record;
5. `design/05_gate_b_inputs.md` — mandatory Gate-B inputs and completion criteria.

The complete Gate-B handoff is this tracker plus those five files.

## Operating rules

- The primary/root agent is the only facilitator, arbiter, integrator, tracker owner, and agent manager. It
  assigns bounded work, reconciles findings, obtains user decisions, and records gate status.
- Subagents are executors. They do not delegate, integrate another agent's result, edit this tracker, or
  announce a gate verdict.
- Exactly one designated writer edits an artifact at a time. Reviewers are read-only, and parallel writers
  have non-overlapping ownership.
- Writers perform semantic and editorial self-review. Material platform, concurrency, GL, memory, cleanup,
  failure, and public-contract work receives independent review.
- Design-preserving simplifications require independent review. Every public/product decision and every
  private numeric Gate-B constant requires explicit user approval; agent consensus cannot replace it.
- Design documents describe the positive current design rather than discussion history or rejected options.
- New Kotlin, native, build, or executable-test implementation work remains frozen until Gate B is complete
  and the user explicitly authorizes implementation.
- Work remains inside `screencaptureengine` unless the user expands scope. Preserve unrelated changes and user
  staging. Do not stage, commit, reset, checkout, revert, or otherwise mutate the index without explicit user
  instruction.

## Current status

- **Gate A: `READY_FOR_GATE_B`.**
- **Gate B: `NOT_STARTED`.**
- **New implementation: `FROZEN`.**
- `design/04_gate_a.md` records the passed Gate-A reviews and confirms that no product decision remains open.
- `READY_FOR_GATE_B` authorizes only pre-Kotlin Gate-B planning. It is not implementation authorization or a
  claim of implementation, release, performance, or device correctness.

## Next conversation: Gate B

1. Start Gate B in a new conversation using only this tracker and the five authoritative design files.
2. Reaffirm the operating rules and plan non-overlapping subagent work with independent review.
3. Produce every concrete pre-Kotlin binding required by `design/05_gate_b_inputs.md`:
   - the component/owner implementation manifest with exact production and test paths;
   - the complete private-constants table with units, types, guards, use sites, and tests;
   - controller, delivery, Android, metrics, GL/PBO, Framework/Native JPEG, memory, cleanup, diagnostics, build,
     packaging, and ownership bindings;
   - exact source-path and runner assignments for every required test slice, including small image vectors and
     practical lossy-JPEG tolerances.
4. Present every private numeric constant to the user for explicit agreement.
5. Close every placeholder and independently review the complete Gate-B package against Documents 01–03.
6. Record Gate B complete only when every component owner, private constant, material platform/native boundary,
   and required test slice has a concrete consistent binding.
7. Request separate explicit implementation authorization. Until it is granted, implementation remains frozen.
