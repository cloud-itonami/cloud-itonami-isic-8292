(ns packagingops.governor-test
  "Pure unit tests of `packagingops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [packagingops.advisor :as adv]
            [packagingops.governor :as gov]
            [packagingops.store :as store]))

(def facility-1 {:facility-id "facility-1" :name "Riverside Contract Packaging & Co-Pack" :registered? true :verified? true})
(def facility-3 {:facility-id "facility-3" :name "Downtown Pop-Up Gift-Wrap Line" :registered? true :verified? false})
(def contract-1 {:contract-id "contract-1" :client "Northbridge Foods Co." :registered? true :verified? true})
(def contract-3 {:contract-id "contract-3" :client "Unverified Startup Beverage Brand" :registered? true :verified? false})
(def supplier-1 {:supplier-id "supplier-1" :name "Cascade Packaging Materials Supply" :registered? true :verified? true})
(def supplier-2 {:supplier-id "supplier-2" :name "Unverified Import Carton Broker Co." :registered? true :verified? false})

(defn- clean-proposal [op facility-id contract-id]
  {:op op :facility-id facility-id :contract-id contract-id :summary "s" :rationale "routine packaging operations coordination"
   :cites [facility-id contract-id] :effect :propose :value {} :confidence 0.85})

(defn- clean-supply-order [facility-id contract-id supplier-id cost]
  (assoc (clean-proposal :coordinate-supply-order facility-id contract-id)
         :value {:facility-id facility-id :contract-id contract-id :supplier-id supplier-id :estimated-cost cost}))

(deftest facility-unregistered-is-hard
  (testing "no facility record at all -> HARD hold"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-1" contract-1})
          verdict (gov/check {} nil (clean-proposal :log-production-record "unknown-facility" "contract-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:facility-unverified} (map :rule (:violations verdict)))))))

(deftest facility-unverified-is-hard
  (testing "facility registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"facility-3" facility-3} {"contract-1" contract-1})
          verdict (gov/check {} nil (clean-proposal :log-production-record "facility-3" "contract-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:facility-unverified} (map :rule (:violations verdict)))))))

(deftest contract-missing-is-hard
  (testing "no contract record at all -> HARD hold"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-1" contract-1})
          verdict (gov/check {} nil (clean-proposal :log-production-record "facility-1" "unknown-contract") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:contract-unverified} (map :rule (:violations verdict)))))))

(deftest contract-unverified-is-hard
  (testing "contract registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-3" contract-3})
          verdict (gov/check {} nil (clean-proposal :log-production-record "facility-1" "contract-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:contract-unverified} (map :rule (:violations verdict)))))))

(deftest contract-check-applies-to-every-op
  (testing "an unverified contract HARD-holds every op, not only production-record logging"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-3" contract-3} {"supplier-1" supplier-1})]
      (doseq [op [:log-production-record :schedule-production-operation :flag-quality-concern]]
        (let [verdict (gov/check {} nil (clean-proposal op "facility-1" "contract-3") s)]
          (is (true? (:hard? verdict)))
          (is (some #{:contract-unverified} (map :rule (:violations verdict)))
              (str "op " op " must HARD hold on an unverified contract")))))))

(deftest supplier-missing-on-supply-order-is-hard
  (testing "supply-order proposal with no :supplier-id at all -> HARD hold"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-1" contract-1} {"supplier-1" supplier-1})
          verdict (gov/check {} nil (clean-supply-order "facility-1" "contract-1" nil 100.0) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:supplier-unverified} (map :rule (:violations verdict)))))))

(deftest supplier-unregistered-on-supply-order-is-hard
  (testing "supply-order proposal naming an unknown supplier -> HARD hold"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-1" contract-1} {"supplier-1" supplier-1})
          verdict (gov/check {} nil (clean-supply-order "facility-1" "contract-1" "unknown-supplier" 100.0) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:supplier-unverified} (map :rule (:violations verdict)))))))

(deftest supplier-unverified-on-supply-order-is-hard
  (testing "supply-order proposal naming a registered-but-unverified supplier -> HARD hold"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-1" contract-1} {"supplier-1" supplier-1 "supplier-2" supplier-2})
          verdict (gov/check {} nil (clean-supply-order "facility-1" "contract-1" "supplier-2" 100.0) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:supplier-unverified} (map :rule (:violations verdict)))))))

(deftest supplier-verified-on-supply-order-is-not-hard-on-supplier-check
  (testing "supply-order proposal naming a verified supplier never trips :supplier-unverified"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-1" contract-1} {"supplier-1" supplier-1})
          verdict (gov/check {} nil (clean-supply-order "facility-1" "contract-1" "supplier-1" 100.0) s)]
      (is (empty? (filter #(= :supplier-unverified (:rule %)) (:violations verdict)))))))

(deftest supplier-check-is-scoped-to-supply-order-only
  (testing "non-supply-order ops never trip :supplier-unverified, even with no suppliers registered at all"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-1" contract-1})]
      (doseq [op [:log-production-record :schedule-production-operation :flag-quality-concern]]
        (let [verdict (gov/check {} nil (clean-proposal op "facility-1" "contract-1") s)]
          (is (empty? (filter #(= :supplier-unverified (:rule %)) (:violations verdict)))
              (str "op " op " must never trip :supplier-unverified")))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-1" contract-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-production-operation "facility-1" "contract-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-1" contract-1})
          verdict (gov/check {} nil (clean-proposal :finalize-package-integrity-clearance "facility-1" "contract-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest package-integrity-clearance-finalization-content-is-hard-and-permanent
  (testing "a proposal whose rationale touches directly certifying a batch as tamper-evident is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-1" contract-1})
          poisoned (assoc (clean-proposal :log-production-record "facility-1" "contract-1")
                          :rationale "certified the batch as tamper-evident after inspecting the seal line"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest batch-release-clearance-content-is-hard
  (testing "a proposal touching clearing a batch for release is HARD-blocked, same as tamper-evidence certification"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-1" contract-1})
          poisoned (assoc (clean-proposal :log-production-record "facility-1" "contract-1")
                          :rationale "cleared the batch for release before shipment"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest food-labeling-compliance-sign-off-content-is-hard
  (testing "a proposal touching signing off on the food safety labeling compliance is HARD-blocked"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-1" contract-1})
          poisoned (assoc (clean-proposal :schedule-production-operation "facility-1" "contract-1")
                          :summary "the coordinator signed off on the food safety labeling compliance at the line")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest pharma-labeling-compliance-sign-off-content-is-hard
  (testing "a proposal touching signing off on the pharma labeling compliance is HARD-blocked"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-1" contract-1} {"supplier-1" supplier-1})
          poisoned (assoc (clean-supply-order "facility-1" "contract-1" "supplier-1" 100.0)
                          :summary "signed off on the pharma labeling compliance at the back room")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-quality-concern-is-not-scope-excluded
  (testing "flagging observed seal/tamper-evidence/label/contamination concerns as a QUALITY CONCERN (not a clearance/sign-off finalization) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-1" contract-1})
          concern (assoc (clean-proposal :flag-quality-concern "facility-1" "contract-1")
                         :value {:concern "tamper-evident seal appears compromised on a sample, label placement also looks misaligned, possible contamination near the cap"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (tamper/seal/label/contamination) is exactly what this op exists to surface"))))

(deftest quality-concern-always-escalates-clean
  (testing ":flag-quality-concern is always high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-1" contract-1})
          verdict (gov/check {} nil (assoc (clean-proposal :flag-quality-concern "facility-1" "contract-1") :confidence 0.99) s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest high-cost-supply-order-always-escalates
  (testing "a :coordinate-supply-order above the cost threshold is high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-1" contract-1} {"supplier-1" supplier-1})
          expensive (assoc (clean-supply-order "facility-1" "contract-1" "supplier-1" 9000.0) :confidence 0.97)
          verdict (gov/check {} nil expensive s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest low-cost-supply-order-does-not-force-escalate
  (testing "a :coordinate-supply-order at or below the cost threshold does not trip the high-cost escalate gate"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-1" contract-1} {"supplier-1" supplier-1})
          cheap (assoc (clean-supply-order "facility-1" "contract-1" "supplier-1" 650.0) :confidence 0.9)
          verdict (gov/check {} nil cheap s)]
      (is (false? (:hard? verdict)))
      (is (false? (:high-stakes? verdict)))
      (is (false? (:escalate? verdict))))))

;; ----------------------------- self-trip regression -----------------------------
;;
;; A known bug class in this actor fleet: the governor's own
;; scope-exclusion term list is sometimes phrased as a bare noun (e.g.
;; "tamper" or "label"), which then accidentally matches inside the mock
;; advisor's own DEFAULT rationale/disclaimer text for a legitimate,
;; allowed proposal -- causing the actor to self-block its own happy
;; path. This is a dedicated regression test: every op the default mock
;; advisor can generate, with default (non-`out-of-scope?`) request
;; patches, must NEVER trip `:scope-excluded` or `:op-not-allowed`.
(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "the default mock advisor's own proposals for every allowed op never trip the governor's scope-exclusion check"
    (let [s (store/mem-store {"facility-1" facility-1} {"contract-1" contract-1} {"supplier-1" supplier-1})]
      (doseq [op [:log-production-record :schedule-production-operation :coordinate-supply-order
                  :flag-quality-concern]]
        (let [patch (if (= op :coordinate-supply-order)
                      {:item "tamper-evident cap seals restock" :estimated-cost 650.0 :supplier-id "supplier-1"}
                      {})
              proposal (adv/infer nil {:op op :facility-id "facility-1" :contract-id "contract-1" :patch patch})
              verdict (gov/check {:facility-id "facility-1" :contract-id "contract-1"} nil proposal s)]
          (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
              (str "default advisor proposal for " op " must never self-trip :scope-excluded -- rationale/summary: "
                   (pr-str (select-keys proposal [:summary :rationale]))))
          (is (empty? (filter #(= :op-not-allowed (:rule %)) (:violations verdict)))
              (str "default advisor proposal for " op " must always be inside the closed op allowlist")))))))
