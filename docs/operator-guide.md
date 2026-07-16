# Operator Guide

## First Deployment
1. Register operator, facilities, client contracts and suppliers;
   independently confirm each facility's business registration/
   operating license, each client packaging-service contract, and each
   supplier's registration before seeding `packagingops.store`.
2. Import existing production batch/run/quantity, scheduling and
   supply-order history.
3. Run read-only production-record-logging and line-scheduling dry-runs
   (Phase 0-1).
4. Configure the rollout phase and the `coordinate-supply-order`
   cost-escalation threshold for human sign-off paths.
5. Publish a dry-run quality-concern flag and audit export.

## Minimum Production Controls
- facility-registration/verification check before ANY proposal for that
  facility
- client-contract-registration/verification check before ANY proposal
  referencing that contract
- supplier-registration/verification check before ANY `:coordinate-
  supply-order` proposal
- governor gate on every proposal before commit
- human sign-off for `:flag-quality-concern` (always) and high-cost
  `:coordinate-supply-order` proposals
- audit export for every commit, hold and approval
- backup manual back-office process

## Certification
Certified operators must prove facility/contract/supplier-verification
discipline, governor-bypass resistance, evidence-backed package-
integrity/labeling-accuracy/contamination quality-concern reporting and
human review for every escalation-gated action. Certification never
covers directly finalizing a package-integrity-safety clearance or a
food/pharma-labeling-compliance sign-off -- those decisions always stay
with a qualified human/regulatory authority outside this actor.
