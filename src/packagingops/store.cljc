(ns packagingops.store
  "SSoT for the ISIC-8292 'Packaging activities' operations-COORDINATION
  actor, behind a `Store` protocol so the backend is a swap, not a
  rewrite -- the same seam every `cloud-itonami-isic-*` actor in this
  fleet uses.

  This actor coordinates the back-office operations of a contract/
  third-party packaging operator (bottling, blister-packing, gift-
  wrapping, bulk-to-retail repackaging), frequently for FOOD and
  PHARMACEUTICAL clients where package-integrity/tamper-evidence and
  labeling accuracy are safety-critical: production-batch/run/quantity
  data logging, packaging-line/staffing scheduling coordination,
  packaging-material supply-order coordination with registered
  suppliers, and package-integrity/labeling-accuracy/contamination
  quality-concern flagging. It never directly finalizes a
  package-integrity-safety clearance or a food/pharma-labeling-
  compliance sign-off -- see `packagingops.governor`'s
  `scope-exclusion-violations`, a HARD, permanent, un-overridable
  block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/
  demo (no deps). A `facilities` directory keyed by `:facility-id`
  STRING, a `contracts` directory keyed by `:contract-id` STRING (the
  client packaging-service engagement a facility is running work
  under), and a `suppliers` directory keyed by `:supplier-id` STRING
  (never keywords -- consistent keying from the start, avoiding the
  silent-miss bug that has plagued earlier sibling actors).

  A registered/verified facility record (business registration +
  packaging-site operating license) AND a registered/verified client
  packaging-service contract record must BOTH exist before ANY
  proposal targeting that facility/contract may ever commit or
  escalate -- `packagingops.governor`'s `facility-unverified-violations`
  and `contract-unverified-violations` re-derive this from the
  facility's/contract's own `:registered?`/`:verified?` fields, never
  from proposal self-report. A `:coordinate-supply-order` proposal
  additionally names a registered packaging-material supplier via its
  own `:supplier-id`; the SAME 'ground truth, not self-report'
  discipline applies via `supplier-unverified-violations`.

  The ledger stays append-only: which facility/contract a proposal
  targeted, which operation, on what basis, committed/held/escalated
  and approved by whom is always a query over an immutable log.")

(defprotocol Store
  (facility-record [s facility-id] "Registered packaging-facility record, or nil.
    Facility map: {:facility-id .. :name .. :registered? bool :verified? bool}.")
  (all-facility-records [s])
  (contract-record [s contract-id] "Registered client packaging-service contract record, or nil.
    Contract map: {:contract-id .. :client .. :registered? bool :verified? bool}.")
  (all-contract-records [s])
  (supplier-record [s supplier-id] "Registered packaging-material supplier record, or nil.
    Supplier map: {:supplier-id .. :name .. :registered? bool :verified? bool}.")
  (all-supplier-records [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-facility-records [s facilities] "replace/seed the facility directory (map facility-id->facility)")
  (with-contract-records [s contracts] "replace/seed the contract directory (map contract-id->contract)")
  (with-supplier-records [s suppliers] "replace/seed the supplier directory (map supplier-id->supplier)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained facility/contract/supplier directory covering
  both the happy path and the governor's own hard checks, so the actor +
  tests run offline."
  []
  {:facilities
   {"facility-1" {:facility-id "facility-1" :name "Riverside Contract Packaging & Co-Pack"
                  :registered? true :verified? true}
    "facility-2" {:facility-id "facility-2" :name "Harborview Blister-Pack & Bottling"
                  :registered? true :verified? true}
    "facility-3" {:facility-id "facility-3" :name "Downtown Pop-Up Gift-Wrap Line (in intake)"
                  :registered? true :verified? false}}
   :contracts
   {"contract-1" {:contract-id "contract-1" :client "Northbridge Foods Co."
                  :registered? true :verified? true}
    "contract-2" {:contract-id "contract-2" :client "Meridian Pharma Distribution"
                  :registered? true :verified? true}
    "contract-3" {:contract-id "contract-3" :client "Unverified Startup Beverage Brand"
                  :registered? true :verified? false}}
   :suppliers
   {"supplier-1" {:supplier-id "supplier-1" :name "Cascade Packaging Materials Supply"
                  :registered? true :verified? true}
    "supplier-2" {:supplier-id "supplier-2" :name "Unverified Import Carton Broker Co."
                  :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (facility-record [_ facility-id] (get-in @a [:facilities facility-id]))
  (all-facility-records [_] (sort-by :facility-id (vals (:facilities @a))))
  (contract-record [_ contract-id] (get-in @a [:contracts contract-id]))
  (all-contract-records [_] (sort-by :contract-id (vals (:contracts @a))))
  (supplier-record [_ supplier-id] (get-in @a [:suppliers supplier-id]))
  (all-supplier-records [_] (sort-by :supplier-id (vals (:suppliers @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-facility-records [s facilities] (when (seq facilities) (swap! a assoc :facilities facilities)) s)
  (with-contract-records [s contracts] (when (seq contracts) (swap! a assoc :contracts contracts)) s)
  (with-supplier-records [s suppliers] (when (seq suppliers) (swap! a assoc :suppliers suppliers)) s))

(defn seed-db
  "A MemStore seeded with the demo facility/contract/supplier directory.
  The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with explicit `facilities`/`contracts`/`suppliers`
  maps (id string -> record map) -- the primary test/dev entry point.
  Any may be empty (an unregistered-everywhere facility/contract/
  supplier)."
  ([facilities] (mem-store facilities {} {}))
  ([facilities contracts] (mem-store facilities contracts {}))
  ([facilities contracts suppliers]
   (->MemStore (atom {:facilities (or facilities {}) :contracts (or contracts {})
                       :suppliers (or suppliers {})
                       :ledger [] :coordination-log []}))))
