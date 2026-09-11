# Contributing

`cloud-itonami-isic-8292` accepts contributions to the OSS blueprint,
capability bindings, policy tests, documentation and operator model.

## Development

```bash
kbb -M:test
kbb -M:lint
```

## Rules
- Do not commit real client, employee, supplier or package-integrity/
  food-pharma-labeling-compliance-incident data.
- Keep production-record logging, line-scheduling, supply-order
  coordination and quality-concern flagging behind the
  ContractPackagingGovernor.
- Treat contract-packaging-operations workflows as high-risk (many jobs
  are food/pharma clients): add tests for facility/contract/supplier
  verification, effect discipline, scope exclusion, escalation and
  audit logging.
- Never phrase a governor scope-exclusion term as a bare noun (e.g.
  "tamper", "seal", "label", "contamination") -- phrase it as the
  finalization/execution ACTION (e.g. "certified the batch as
  tamper-evident"), and add/extend the
  `default-mock-advisor-proposals-never-self-trip-scope-exclusion`
  regression test for any new term. A bare-noun term will self-trip
  this actor's own legitimate `:flag-quality-concern` happy path -- see
  `packagingops.governor/scope-excluded-terms`'s docstring.
- Never add an op that directly finalizes a package-integrity-safety
  clearance or a food/pharma-labeling-compliance sign-off to the closed
  proposal-op allowlist -- both actions are structurally excluded from
  this actor's vocabulary, not merely gated.
- A "flag a concern" op must always escalate to a human and must never
  be added to any phase's `:auto` set.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
