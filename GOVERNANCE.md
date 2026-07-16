# Governance

`cloud-itonami-isic-8292` is an OSS open-business blueprint for
contract-packaging operations coordination (ISIC Rev.5 8292 --
packaging activities: bottling, blister-packing, gift-wrapping,
bulk-to-retail repackaging).

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a proposal for an unverified/unregistered facility, or one referencing
  an unverified/unregistered client packaging-service contract, or a
  supply order naming an unverified/unregistered supplier, can never
  commit.
- the ContractPackagingGovernor remains independent of the advisor.
- hard policy violations (non-`:propose` effect, package-integrity-
  safety-clearance-finalization content, food/pharma-labeling-
  compliance-sign-off content, an op outside the closed allowlist)
  cannot be overridden by human approval.
- every production-record log, line-scheduling proposal, supply-order
  coordination and quality-concern flag is auditable.
- customer, client, employee and supplier data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or
license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification
is a separate trust mark and should require security, audit and
data-flow review.

Certified operators can lose certification for:
- bypassing production-record, scheduling, supply-order or
  package-integrity/labeling-compliance policy checks
- mishandling customer, client, employee or supplier data
- misrepresenting certification status
- failing to respond to security or package-integrity/food-pharma-
  labeling-compliance incidents
