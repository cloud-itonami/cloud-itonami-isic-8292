# cloud-itonami-isic-8292

Open Business Blueprint for **ISIC Rev.5 8292**: packaging activities --
contract/third-party packaging services (bottling, blister-packing,
gift-wrapping, bulk-to-retail repackaging), frequently performed for
FOOD and PHARMACEUTICAL clients where package-integrity/tamper-evidence
and labeling accuracy are safety-critical.

This repository publishes a contract-packaging-operations-COORDINATION
actor -- production batch/run/quantity data logging, packaging-line/
staffing scheduling, packaging-material supply-order coordination with
registered suppliers, and package-integrity/labeling-accuracy/
contamination quality-concern flagging -- as an OSS business that any
qualified operator can fork, deploy, run, improve and sell, so an
independent contract-packaging operator never surrenders its operations
data to a closed back-office SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, in-mem/Datomic checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is **PackagingOpsAdvisor
⊣ ContractPackagingGovernor**. This blueprint's own
`:itonami.blueprint/governor` keyword, `:contract-packaging-governor`,
is a distinct, independent build (checked for fleet-wide uniqueness via
`gh api search/code` against `org:cloud-itonami` before landing -- 0
hits).

> **Why an actor layer at all?** An LLM is great at drafting a
> production-record summary, a line-scheduling proposal, or a
> supply-order request -- but it has no license to actually finalize a
> package-integrity-safety clearance or a food/pharma-labeling-
> compliance sign-off, no way to independently confirm a packaging
> facility or the client contract it is working under is actually a
> registered/verified engagement, and no notion of when a "flag this
> concern" op quietly turns into a claim to have already certified a
> batch as tamper-evident. Letting it act directly invites an
> unverified facility's data entering the ledger, an unregistered
> client engagement being logged as legitimate, an unverified supplier
> receiving a materials order, or -- worst of all -- a fabricated claim
> to have already cleared a batch for release or signed off on food/
> pharma label compliance, exposing the operator, its clients and
> end consumers to real liability. This project seals the
> PackagingOpsAdvisor into a single node and wraps it with an
> independent **ContractPackagingGovernor**, a human **approval
> workflow**, and an immutable **audit ledger**.

## Scope: coordination only, never a package-integrity/labeling-compliance authority

This actor is **operations coordination only**. It never performs or
authorizes:

- directly finalizing a package-integrity-safety clearance (certifying
  a package/batch as tamper-evident, clearing a batch for release,
  issuing a package-integrity safety clearance)
- a food/pharma-labeling-compliance sign-off (approving or signing off
  on food/pharma label accuracy, declaring a label regulatory-compliant)
- package-integrity/labeling-compliance authority enforcement
  (declaring a seal-integrity inspection passed, authorizing a
  contaminated batch's disposition)

The governor's `scope-exclusion-violations` check re-scans every
proposal for this failure mode independently of the advisor's own
framing, and treats it as a HARD, permanent block regardless of
confidence or how clean everything else is. Flagging a package-
integrity/labeling-accuracy/contamination concern for a human to triage
is exactly this actor's job -- `:flag-quality-concern` is never excluded
by this check, only FINALIZING/certifying/signing off on that concern
is. **The closed proposal-op allowlist structurally never includes any
op that directly finalizes a package-integrity-safety clearance or a
food/pharma-labeling-compliance sign-off -- there is no such op to gate,
only one to permanently exclude.**

### Actuation

**Every proposal this actor generates is `:effect :propose`, never a
direct actuation.** Two independent layers enforce this
(`packagingops.governor`'s `effect-not-propose-violations` HARD check
and `packagingops.phase`'s phase table, which never puts
`:flag-quality-concern` in any phase's `:auto` set). A human packaging-
operations coordinator/quality-assurance reviewer is always the one who
actually acts on a flagged concern or confirms a high-cost supply order.

## The core contract

```
facility/client-contract registration + operations-coordination request
        |
        v
   ┌───────────────────────┐   proposal      ┌────────────────────────────┐
   │ PackagingOpsAdvisor    │ ─────────────▶ │ ContractPackagingGovernor    │  (independent system)
   │ (sealed)               │  + citations    │ facility-unverified ·       │
   └───────────────────────┘                 │ contract-unverified ·       │
          │                 commit ◀┼ supplier-unverified ·               │
          │                         │ effect-not-propose ·                │
    record + ledger        escalate ┼ scope-excluded (package-integrity-   │
          │              (ALWAYS for│ safety-clearance / food-pharma-      │
          │       :flag-quality-    │ labeling-compliance finalization) ·  │
          │       concern/high-cost │ op-not-allowed                       │
          │       supply-order)     └────────────────────────────┘
          ▼
      human approval
```

**The PackagingOpsAdvisor never commits a proposal the
ContractPackagingGovernor would reject, and a package-integrity/
labeling-accuracy/contamination quality-concern flag or a high-cost
supply order never commits without a human sign-off.** Hard violations
(an unregistered/unverified facility; an unregistered/unverified client
contract; an unregistered/unverified supply-order supplier; a
non-`:propose` effect; content touching package-integrity-safety-
clearance or food/pharma-labeling-compliance finalization; an op outside
the closed allowlist) force **hold** and *cannot* be approved past.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
may perform physical domain work** (here: bottle/blister-pack loading,
label application, cartoning, palletizing) under human/robot floor
operations gated by facility policy. This actor itself does not
dispatch robot/hardware actions -- it is strictly the
operations-coordination layer (production-record logging, line
scheduling, supply-order coordination, quality-concern flagging) any
physical-dispatch layer could eventually feed proposals into, always
gated the same way by the independent ContractPackagingGovernor.

## Features

- **Closed proposal-op allowlist**: `log-production-record`,
  `schedule-production-operation`, `coordinate-supply-order`,
  `flag-quality-concern` (all `:effect :propose`). No op in this
  allowlist finalizes a package-integrity-safety clearance or a
  food/pharma-labeling-compliance sign-off.
- **Five HARD governor checks** (permanent, un-overridable):
  1. **Facility unverified** -- the target facility's business
     registration must exist AND be independently registered/verified
     in the store.
  2. **Contract unverified** -- the client packaging-service contract
     the proposal is working under must ALSO exist AND be independently
     registered/verified -- checked unconditionally, on every op.
  3. **Supplier unverified** -- for `:coordinate-supply-order` only, the
     named supplier must exist AND be independently registered/verified
     -- a supply-chain counterparty-verification gate.
  4. **Effect is :propose** -- any other `:effect` value is rejected.
  5. **Scope exclusion** -- directly finalizing a package-integrity-
     safety clearance (certifying a package/batch as tamper-evident,
     clearing a batch for release, issuing a package-integrity safety
     clearance), a food/pharma-labeling-compliance sign-off, and an op
     outside the closed allowlist are all permanently blocked.
- **Two ESCALATE (SOFT) gates**, either forces human sign-off:
  - `:flag-quality-concern` -- ALWAYS escalates, regardless of
    confidence or phase. A "flag a concern" op is never auto-commit
    eligible and never finalizes a package-integrity-safety-clearance
    or a food/pharma-labeling-compliance decision itself -- it only
    surfaces the concern for a human.
  - `:coordinate-supply-order` above a cost threshold -- a large-value
    procurement proposal always needs a human sign-off.
  - (LLM confidence below the floor also escalates, as with every
    sibling actor.)
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: production-record logging only (approval-gated)
  - Phase 2: + line-scheduling, supply-order proposals (approval-gated)
  - Phase 3: auto-commits clean, high-confidence, low-cost proposals
    (quality concerns and high-cost supply orders always escalate)
- **Append-only audit ledger** -- every decision is an immutable log
  entry.
- **langgraph-clj StateGraph** -- one request = one supervised run;
  human-in-the-loop via `interrupt-before`.

### Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
kbb -M:dev -P

# Run tests
kbb -M:test

# Run linter
kbb -M:lint

# Run demo
kbb -M:run
```

### Test suite

- `test/packagingops/governor_test.kotoba` -- unit tests of governor hard
  checks, scope exclusion, and the self-trip regression test
- `test/packagingops/advisor_test.kotoba` -- advisor proposal shape and
  consistency
- `test/packagingops/phase_test.kotoba` -- rollout phase logic
- `test/packagingops/governor_contract_test.kotoba` -- full graph
  integration, audit trail
- `test/packagingops/store_contract_test.kotoba` -- Store protocol and
  MemStore implementation

### Modules

- `packagingops.store` -- SSoT (MemStore, String-keyed facility/
  contract/supplier directories, append-only ledger)
- `packagingops.advisor` -- contained intelligence node (mock +
  real-LLM seam)
- `packagingops.governor` -- independent compliance layer
- `packagingops.phase` -- staged rollout (0→3)
- `packagingops.operation` -- langgraph-clj StateGraph
- `packagingops.sim` -- demo driver

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`8292`).

## Business-process coverage (honest)

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Production batch/run/quantity data logging (`:log-production-record`) | Real MES/production-line-system integration |
| Packaging-line/staffing scheduling coordination (`:schedule-production-operation`) | Direct staff time-clock/payroll integration |
| Packaging-material supply-order coordination with a registered, verified supplier, HARD-gated on supplier verification and a double-actuation-free single-proposal shape (`:coordinate-supply-order`) | Real supplier-ordering-system integration |
| Package-integrity/labeling-accuracy/contamination quality-concern flagging, ALWAYS human-gated (`:flag-quality-concern`) | Directly finalizing any package-integrity-safety clearance or food/pharma-labeling-compliance sign-off -- permanently out of scope, not a gap |
| Immutable audit ledger for every log/schedule/order/flag decision | Daily reconciliation/yield-accounting -- a follow-up slice, not in this R0 |

Extending coverage is additive: add the next op (e.g. a
returned-goods-disposition-coordination check) as its own governed op
with its own HARD checks and tests, following the SAME "an independent
governor re-verifies against the actor's own records before any
real-world act" pattern this repo's flagship checks already establish.

## Maturity

`:implemented` -- `PackagingOpsAdvisor` +
`ContractPackagingGovernor` run as real, tested code (see `Development`
above), following the SAME governed-actor architecture as every prior
actor across this fleet, with its own distinct, independently-named
governor and its own package-integrity-safety-clearance/food-pharma-
labeling-compliance scope-exclusion check.

## License

Code and implementation templates are AGPL-3.0-or-later.
