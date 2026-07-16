(ns packagingops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean production-record
  logging request through intake -> advise -> govern -> decide ->
  approval -> commit at phase 1 (assisted-logging, always approval),
  then re-runs the same op at phase 3 (supervised-auto, clean + high
  confidence -> auto-commit), then a packaging-line/staffing-operation-
  scheduling request and a low-cost supply-order coordination naming a
  verified supplier (both auto-commit clean at phase 3), then a
  high-cost supply-order (ALWAYS escalates regardless of phase), then a
  package-integrity/labeling-accuracy/contamination quality-concern
  flag (ALWAYS escalates, at any phase -- approve, then commit), then
  HARD-hold scenarios: an unregistered facility, a facility registered
  but not yet verified, a proposal referencing an unverified client
  contract, a supply-order naming an unverified supplier, a proposal
  whose own `:effect` is not `:propose`, and a proposal that has
  drifted into the permanently-excluded package-integrity-safety-
  clearance/food-pharma-labeling-compliance-finalization scope."
  (:require [langgraph.graph :as g]
            [packagingops.advisor :as advisor]
            [packagingops.store :as store]
            [packagingops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "packaging-qa-coordinator-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        coordinator-phase-1 {:actor-id "coord-1" :actor-role :packaging-operations-coordinator :phase 1}
        coordinator-phase-3 {:actor-id "coord-1" :actor-role :packaging-operations-coordinator :phase 3}
        actor (op/build db)]

    (println "== log-production-record facility-1/contract-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-production-record :facility-id "facility-1" :contract-id "contract-1"
                                  :patch {:batch-id "b-2001" :units-packaged 5000 :rejected-units 12}} coordinator-phase-1)]
      (println r)
      (println "-- human packaging QA coordinator approves --")
      (println (approve! actor "t1")))

    (println "\n== log-production-record facility-1/contract-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-production-record :facility-id "facility-1" :contract-id "contract-1"
                                  :patch {:batch-id "b-2002" :units-packaged 4800 :rejected-units 4}} coordinator-phase-3))

    (println "\n== schedule-production-operation facility-1/contract-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-production-operation :facility-id "facility-1" :contract-id "contract-1"
                                  :patch {:line "blister-line-2" :date "2026-07-20" :window "06:00-14:00"}} coordinator-phase-3))

    (println "\n== coordinate-supply-order facility-1/contract-1, low cost, verified supplier (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-supply-order :facility-id "facility-1" :contract-id "contract-1"
                                  :patch {:item "tamper-evident cap seals restock" :quantity 20000 :estimated-cost 650.0
                                          :supplier-id "supplier-1"}} coordinator-phase-3))

    (println "\n== coordinate-supply-order facility-1/contract-1, HIGH cost (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :coordinate-supply-order :facility-id "facility-1" :contract-id "contract-1"
                                 :patch {:item "blister-pack tooling changeover kit" :quantity 4 :estimated-cost 8500.0
                                         :supplier-id "supplier-1"}} coordinator-phase-3)]
      (println r)
      (println "-- human packaging operations coordinator reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== flag-quality-concern facility-1/contract-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t6" {:op :flag-quality-concern :facility-id "facility-1" :contract-id "contract-1"
                                 :patch {:concern "tamper-evident seal appears compromised on a sample from batch b-2002, cap torque reading below threshold" :confidence 0.91}} coordinator-phase-3)]
      (println r)
      (println "-- human packaging QA coordinator reviews & approves --")
      (println (approve! actor "t6")))

    (println "\n== log-production-record facility-99 (unregistered facility -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-production-record :facility-id "facility-99" :contract-id "contract-1"
                                  :patch {:units-packaged 0}} coordinator-phase-3))

    (println "\n== log-production-record facility-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t8" {:op :log-production-record :facility-id "facility-3" :contract-id "contract-1"
                                  :patch {:units-packaged 10}} coordinator-phase-3))

    (println "\n== log-production-record facility-1, contract-3 unverified client contract (-> HARD hold) ==")
    (println (exec-op actor "t8b" {:op :log-production-record :facility-id "facility-1" :contract-id "contract-3"
                                   :patch {:units-packaged 10}} coordinator-phase-3))

    (println "\n== coordinate-supply-order facility-1/contract-1, supplier-2 unverified (-> HARD hold) ==")
    (println (exec-op actor "t9" {:op :coordinate-supply-order :facility-id "facility-1" :contract-id "contract-1"
                                  :patch {:item "import carton stock" :quantity 5000 :estimated-cost 300.0
                                          :supplier-id "supplier-2"}} coordinator-phase-3))

    (println "\n== schedule-production-operation facility-1/contract-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t10" {:op :schedule-production-operation :facility-id "facility-1" :contract-id "contract-1"
                                           :patch {:line "gift-wrap-line-1" :date "2026-07-22"}} coordinator-phase-3)))

    (println "\n== log-production-record facility-1/contract-1, advisor drifts into package-integrity/labeling-compliance-finalization scope -> HARD hold, permanent ==")
    (println (exec-op actor "t11" {:op :log-production-record :facility-id "facility-1" :contract-id "contract-1"
                                   :out-of-scope? true
                                   :patch {}} coordinator-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
