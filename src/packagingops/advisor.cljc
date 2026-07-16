(ns packagingops.advisor
  "PackagingOpsAdvisor -- the *contained intelligence node* for the
  ISIC-8292 'Packaging activities' operations-coordination actor
  (contract/third-party packaging services: bottling, blister-packing,
  gift-wrapping, bulk-to-retail repackaging -- frequently for FOOD and
  PHARMACEUTICAL clients).

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: production batch/run/quantity data logging,
  packaging-line/staffing scheduling, packaging-material supply-order
  coordination, and package-integrity/labeling-accuracy/contamination
  quality-concern flagging. CRITICAL: it is a smart-but-untrusted
  advisor. It returns a *proposal* (with a rationale + the fields it
  cited), never a committed record and NEVER a direct actuation --
  every proposal's `:effect` is always `:propose`. Every output is
  censored downstream by `packagingops.governor` before anything
  touches the SSoT.

  This advisor NEVER drafts a direct package-integrity-safety-clearance
  finalization (certifying a package/batch as tamper-evident, clearing
  a batch for release, issuing a package-integrity safety clearance) or
  a food/pharma-labeling-compliance sign-off (approving or signing off
  on food/pharma label accuracy, declaring a label regulatory-
  compliant) -- those are permanently out of scope for this actor, not
  merely un-implemented. `packagingops.governor`'s
  `scope-exclusion-violations` independently re-scans every proposal
  for exactly this failure mode (a compromised or confused advisor
  drifting into scope it must never touch) and HARD-holds it,
  regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op          kw             ; echoes the request op
     :facility-id str
     :contract-id str
     :summary     str            ; human-facing draft / finding
     :rationale   str            ; why -- SCANNED by the scope-exclusion gate
     :cites       [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect      :propose       ; ALWAYS :propose -- never a direct actuation
     :value       map            ; the draft payload a human/system would review
     :confidence  0..1}")

(defprotocol Advisor
  (-advise [advisor db request] "db + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-production-record
  "Draft a production batch/run/quantity data log entry. Pure logging of
  observed production activity (units packaged, batch/run counts,
  rejected-unit counts) -- never a package-integrity-safety-clearance
  finalization and never a labeling-compliance sign-off."
  [_db {:keys [facility-id contract-id patch]}]
  {:op          :log-production-record
   :facility-id facility-id
   :contract-id contract-id
   :summary     (str facility-id " の生産バッチ/ラン/数量記録を記録: " (pr-str (keys patch)))
   :rationale   "パッケージング生産バッチ・ラン・数量・不良数の観察記録のみ。品質適合判定や表示適合の確定は含まない。"
   :cites       [facility-id contract-id]
   :effect      :propose
   :value       (merge {:facility-id facility-id :contract-id contract-id} patch)
   :confidence  0.92})

(defn- propose-production-operation
  "Draft a packaging-line/staffing scheduling proposal (a roster/line
  calendar entry, never a direct enforcement or clearance action)."
  [_db {:keys [facility-id contract-id patch]}]
  {:op          :schedule-production-operation
   :facility-id facility-id
   :contract-id contract-id
   :summary     (str facility-id " の梱包ライン/人員配置予定を提案: " (pr-str (keys patch)))
   :rationale   "梱包ライン稼働・人員シフトの調整提案のみ。最終的な配置確定は人間が行う。"
   :cites       [facility-id contract-id]
   :effect      :propose
   :value       (merge {:facility-id facility-id :contract-id contract-id} patch)
   :confidence  0.87})

(defn- propose-supply-order
  "Draft a packaging-material procurement coordination request (bottles,
  blister film, cartons, labels, tamper-evident seals) naming a
  registered supplier -- never a finalized purchase order; a human
  always confirms procurement."
  [_db {:keys [facility-id contract-id patch]}]
  {:op          :coordinate-supply-order
   :facility-id facility-id
   :contract-id contract-id
   :summary     (str facility-id " 向け包装資材の発注調整を提案: " (pr-str (keys patch)))
   :rationale   "ボトル・ブリスター・カートン・ラベル・改ざん防止シール等の資材発注調整提案のみ。確定発注は人間が行う。"
   :cites       [facility-id contract-id]
   :effect      :propose
   :value       (merge {:facility-id facility-id :contract-id contract-id} patch)
   :confidence  0.89})

(defn- propose-quality-concern
  "Surface an observed package-integrity/labeling-accuracy/contamination
  concern (seal defect, tamper-evidence failure, label mismatch,
  foreign-material contamination) for HUMAN triage. This op ALWAYS
  escalates in `packagingops.governor` -- never auto-committed at any
  phase -- regardless of how confident the advisor is that the concern
  is real. Deliberately reports the OBSERVATION only, never a
  finalization/clearance/sign-off action, so the default rationale
  never trips the governor's `scope-excluded-terms` (see that var's
  docstring)."
  [_db {:keys [facility-id contract-id patch]}]
  {:op          :flag-quality-concern
   :facility-id facility-id
   :contract-id contract-id
   :summary     (str facility-id " のパッケージ完全性/表示正確性/混入懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale   "シール不良・改ざん防止機構の異常・表示不一致・異物混入の懸念に関する観察事実の報告。常に人間の確認・対応が必要。"
   :cites       [facility-id contract-id]
   :effect      :propose
   :value       (merge {:facility-id facility-id :contract-id contract-id} patch)
   :confidence  (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-production-record (propose-production-record _db request)
                   :schedule-production-operation (propose-production-operation _db request)
                   :coordinate-supply-order (propose-supply-order _db request)
                   :flag-quality-concern (propose-quality-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared
    ;; before production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually certified the batch as tamper-evident and signed off on the food safety labeling compliance")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t           :advisor-proposal
   :op          (:op proposal)
   :facility-id (:facility-id proposal)
   :contract-id (:contract-id proposal)
   :summary     (:summary proposal)
   :confidence  (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ db request]
      (infer db request))))
