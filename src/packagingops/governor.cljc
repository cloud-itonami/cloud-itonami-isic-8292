(ns packagingops.governor
  "ContractPackagingGovernor -- the independent compliance layer that
  earns the PackagingOpsAdvisor the right to commit. The advisor has no
  notion of whether a packaging facility is actually registered and
  license-verified, whether the client packaging-service contract it is
  working under is itself registered/verified, whether a named
  supply-order supplier is itself a registered/verified counterparty,
  whether its own proposed `:effect` secretly claims a direct actuation
  instead of a mere proposal, or whether it has silently drifted into a
  permanently out-of-scope decision area, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- COORDINATION ONLY
  (production batch/run/quantity data logging, packaging-line/staffing
  scheduling, packaging-material supply-order coordination,
  package-integrity/labeling-accuracy/contamination quality-concern
  flagging). It NEVER performs or authorizes:
    - directly finalizing a package-integrity-safety clearance
      (certifying a package/batch as tamper-evident, clearing a batch
      for release, issuing a package-integrity safety clearance)
    - a food/pharma-labeling-compliance sign-off (approving or signing
      off on food/pharma label accuracy, declaring a label
      regulatory-compliant)
    - package-integrity/labeling-compliance authority enforcement
      (declaring a seal-integrity inspection passed, authorizing a
      contaminated batch's disposition)

  FIVE HARD checks, ALL permanent, un-overridable by any human approval:

    1. Facility unverified         -- the target facility record must
                                       exist AND be independently
                                       confirmed `:registered?`/
                                       `:verified?` in the store before
                                       ANY proposal for it may commit or
                                       even escalate. Never trusts a
                                       proposal's own claim about the
                                       facility -- re-derived from the
                                       facility's own record, the same
                                       'ground truth, not self-report'
                                       discipline every sibling actor's
                                       governor uses. Checked
                                       UNCONDITIONALLY on every op.
    2. Contract unverified         -- the client packaging-service
                                       contract record the proposal is
                                       working under must ALSO exist AND
                                       be independently
                                       `:registered?`/`:verified?` --
                                       contract packaging is always
                                       performed under a specific client
                                       engagement, never as a standalone
                                       facility action. Checked
                                       UNCONDITIONALLY on every op, the
                                       same discipline as the facility
                                       check.
    3. Supplier unverified         -- for `:coordinate-supply-order`
                                       ONLY, the proposal's own drafted
                                       `:value` must name a
                                       `:supplier-id` that resolves to
                                       an independently
                                       `:registered?`/`:verified?`
                                       supplier record. A missing
                                       supplier-id, or one that resolves
                                       to an unregistered or unverified
                                       supplier, is a HARD block.
    4. Effect not :propose         -- every proposal's `:effect` MUST
                                       be `:propose`. Any other effect
                                       value is, by construction, a
                                       claim to directly actuate/commit
                                       outside governance -- HARD block,
                                       not merely low-confidence.
    5. Scope exclusion             -- ANY proposal (regardless of op)
                                       whose op, summary, rationale,
                                       cites or draft value touches
                                       directly finalizing a
                                       package-integrity-safety
                                       clearance (certifying a
                                       package/batch as tamper-evident,
                                       clearing a batch for release,
                                       issuing a package-integrity
                                       safety clearance) OR a
                                       food/pharma-labeling-compliance
                                       sign-off (approving/signing off
                                       on food/pharma label accuracy,
                                       declaring a label
                                       regulatory-compliant) is a HARD,
                                       PERMANENT block -- this actor's
                                       charter excludes that territory
                                       structurally, not as a rollout
                                       milestone. Evaluated
                                       UNCONDITIONALLY on every
                                       proposal. An op outside the
                                       closed four-op allowlist is the
                                       SAME failure mode (an advisor
                                       proposing something it was never
                                       authorized to propose) and is
                                       folded into this same check.
                                       `:flag-quality-concern` itself is
                                       never excluded by this check --
                                       surfacing a package-integrity/
                                       labeling-accuracy/contamination
                                       concern for a human is exactly
                                       this actor's job; only
                                       FINALIZING/certifying/signing off
                                       on that concern is excluded (see
                                       `scope-excluded-terms` below --
                                       phrased as the finalization/
                                       execution ACTION, never a bare
                                       noun like 'tamper', 'seal',
                                       'label' or 'contamination', so
                                       the default mock advisor's own
                                       `:flag-quality-concern` rationale
                                       never self-trips this check).

  Two ESCALATE (SOFT) gates, either forces human sign-off:
    - LLM confidence below the floor.
    - The op is `:flag-quality-concern` -- ALWAYS escalates to a human,
      regardless of confidence, regardless of how clean the proposal
      otherwise is. `packagingops.phase` independently agrees:
      `:flag-quality-concern` is never a member of any phase's `:auto`
      set either -- two layers, not one. This actor NEVER directly
      finalizes a package-integrity-safety clearance or a
      food/pharma-labeling-compliance sign-off itself -- flagging a
      concern always routes to a human, never to auto-commit.
    - A `:coordinate-supply-order` whose drafted `:value` names an
      `:estimated-cost` above `supply-cost-threshold` -- a large-value
      packaging-material procurement proposal always needs a human
      sign-off, even when the governor and phase would otherwise allow
      auto-commit."
  (:require [clojure.string :as str]
            [packagingops.store :as store]))

(def confidence-floor 0.6)

(def supply-cost-threshold
  "Example single-facility packaging-material procurement threshold
  (USD-equivalent units, domain-illustrative -- not a universal
  cross-domain constant). A `:coordinate-supply-order` proposal citing
  an `:estimated-cost` above this value ALWAYS escalates to human
  sign-off, regardless of confidence or rollout phase."
  2000.0)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a scope
  violation by construction (see `scope-exclusion-violations`). NOTE: no
  op in this allowlist finalizes a package-integrity-safety clearance or
  a food/pharma-labeling-compliance sign-off -- both actions are
  structurally excluded from the actor's vocabulary, not merely gated."
  #{:log-production-record :schedule-production-operation
    :coordinate-supply-order :flag-quality-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-quality-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- directly finalizing a
  package-integrity-safety clearance (certifying a package/batch as
  tamper-evident, clearing a batch for release, issuing a
  package-integrity safety clearance) OR a food/pharma-labeling-
  compliance sign-off (approving/signing off on food/pharma label
  accuracy, declaring a label regulatory-compliant), or otherwise
  finalizing/certifying either matter rather than merely flagging it for
  a human. Scanned across the proposal's
  op/summary/rationale/cites/value, never trusting the advisor's own
  framing of its intent.

  CRITICAL: every term here is phrased as the finalization/execution
  ACTION (e.g. 'certified the batch as tamper-evident', 'signed off on
  the food safety labeling compliance'), never a bare noun like
  'tamper', 'seal', 'label', 'contamination' or 'integrity' -- a bare
  noun would accidentally match inside this actor's own legitimate
  `:flag-quality-concern` default proposal text (whose whole job is to
  talk about seal/tamper-evidence/label/contamination concerns, and
  whose own printed `:op` keyword literally contains the substring
  'quality') and self-block the happy path. See
  `packagingops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`
  for the regression test."
  ["finalize the package integrity clearance" "finalized the package integrity clearance" "finalizing the package integrity clearance"
   "finalize the package integrity safety clearance" "finalized the package integrity safety clearance" "finalizing the package integrity safety clearance"
   "issue the package integrity safety clearance" "issued the package integrity safety clearance" "issuing the package integrity safety clearance"
   "grant the package integrity safety clearance" "granted the package integrity safety clearance" "granting the package integrity safety clearance"
   "certify the package as tamper-evident" "certified the package as tamper-evident" "certifying the package as tamper-evident"
   "certify the batch as tamper-evident" "certified the batch as tamper-evident" "certifying the batch as tamper-evident"
   "clear the batch for release" "cleared the batch for release" "clearing the batch for release"
   "declare the batch tamper-evident" "declared the batch tamper-evident" "declaring the batch tamper-evident"
   "authorize the batch for release as tamper-evident" "authorized the batch for release as tamper-evident" "authorizing the batch for release as tamper-evident"
   "sign off on the food safety labeling compliance" "signed off on the food safety labeling compliance" "signing off on the food safety labeling compliance"
   "sign off on the pharma labeling compliance" "signed off on the pharma labeling compliance" "signing off on the pharma labeling compliance"
   "approve the food labeling compliance sign-off" "approved the food labeling compliance sign-off" "approving the food labeling compliance sign-off"
   "approve the pharma labeling compliance sign-off" "approved the pharma labeling compliance sign-off" "approving the pharma labeling compliance sign-off"
   "certify the labeling as compliant" "certified the labeling as compliant" "certifying the labeling as compliant"
   "confirm the labeling as regulatory-compliant" "confirmed the labeling as regulatory-compliant" "confirming the labeling as regulatory-compliant"
   "declare the label regulatory-compliant" "declared the label regulatory-compliant" "declaring the label regulatory-compliant"
   "包装完全性の安全確認を確定" "包装完全性の安全確認を確定した" "包装完全性の安全確認を確定する"
   "改ざん防止確認を完了" "改ざん防止確認を完了した" "改ざん防止確認を完了する"
   "食品表示の適合を確定" "食品表示の適合を確定した" "食品表示の適合を確定する"
   "医薬品表示の適合を確定" "医薬品表示の適合を確定した" "医薬品表示の適合を確定する"]
  )

;; ----------------------------- checks -----------------------------

(defn- facility-unverified-violations
  "The target facility must exist AND be independently
  `:registered?`/`:verified?` in the store -- never trust the
  proposal's own `:facility-id` claim without a facility lookup."
  [{:keys [facility-id]} st]
  (let [f (store/facility-record st facility-id)]
    (when-not (and f (:registered? f) (:verified? f))
      [{:rule :facility-unverified
        :detail (str facility-id " は未登録または未検証の施設 -- いかなる提案も進められない")}])))

(defn- contract-unverified-violations
  "The client packaging-service contract the proposal is working under
  must ALSO exist AND be independently `:registered?`/`:verified?` --
  never trust the proposal's own `:contract-id` claim without a
  contract lookup, the SAME 'ground truth, not self-report' discipline
  as `facility-unverified-violations`, reapplied to the client
  engagement. Checked unconditionally on every op, since contract
  packaging is always performed under a specific client contract."
  [{:keys [contract-id]} st]
  (let [c (store/contract-record st contract-id)]
    (when-not (and c (:registered? c) (:verified? c))
      [{:rule :contract-unverified
        :detail (str contract-id " は未登録または未検証の顧客契約 -- いかなる提案も進められない")}])))

(defn- supplier-unverified-violations
  "For `:coordinate-supply-order` ONLY, the proposal's own drafted
  `:value` must name a `:supplier-id` that resolves to an independently
  `:registered?`/`:verified?` supplier record. A missing supplier-id, or
  one that resolves to an unregistered/unverified supplier, is a HARD
  block -- never trust the proposal's own supplier claim without a
  store lookup, the SAME 'ground truth, not self-report' discipline as
  `facility-unverified-violations`/`contract-unverified-violations`,
  reapplied to the supply-chain counterparty."
  [proposal st]
  (when (= :coordinate-supply-order (:op proposal))
    (let [supplier-id (get-in proposal [:value :supplier-id])
          s (and supplier-id (store/supplier-record st supplier-id))]
      (when-not (and s (:registered? s) (:verified? s))
        [{:rule :supplier-unverified
          :detail (str (or supplier-id "(supplier-id missing)")
                        " は未登録または未検証の資材仕入先 -- 発注調整提案を進められない")}]))))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim to
  directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist, or
  one whose content touches directly finalizing a
  package-integrity-safety clearance or a food/pharma-labeling-
  compliance sign-off, regardless of confidence or how clean every
  other check is. Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "パッケージ完全性安全確認または食品/医薬品表示適合確定行為(package-integrity-safety-clearance/food-pharma-labeling-compliance finalization)に触れる提案は永久に禁止"}])))

(defn- high-cost-supply-order?
  "A `:coordinate-supply-order` proposal citing an `:estimated-cost`
  above `supply-cost-threshold` -- always needs human sign-off (SOFT
  escalate, not a hard block: the order itself is in scope, only its
  size requires a human)."
  [proposal]
  (and (= :coordinate-supply-order (:op proposal))
       (some-> proposal :value :estimated-cost (> supply-cost-threshold))))

(defn check
  "Censors a PackagingOpsAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [facility-id (or (:facility-id proposal) (:facility-id request))
        contract-id (or (:contract-id proposal) (:contract-id request))
        hard (into []
                   (concat (facility-unverified-violations {:facility-id facility-id} store)
                           (contract-unverified-violations {:contract-id contract-id} store)
                           (supplier-unverified-violations proposal store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                              (high-cost-supply-order? proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t           :governor-hold
   :op          (:op request)
   :actor       (:actor-id context)
   :facility-id (:facility-id request)
   :contract-id (:contract-id request)
   :disposition :hold
   :basis       (mapv :rule (:violations verdict))
   :violations  (:violations verdict)
   :confidence  (:confidence verdict)})
