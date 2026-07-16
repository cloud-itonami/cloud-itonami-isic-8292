(ns packagingops.advisor-test
  "Unit tests of `packagingops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [packagingops.advisor :as adv]
            [packagingops.store :as store]))

(def db (store/seed-db))

(deftest propose-production-record-shape
  (testing "production-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-production-record
                           :facility-id "facility-1" :contract-id "contract-1"
                           :patch {:batch-id "b-1" :units-packaged 5000 :rejected-units 12}})]
      (is (= :log-production-record (:op p)))
      (is (= "facility-1" (:facility-id p)))
      (is (= "contract-1" (:contract-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :facility-id))
      (is (contains? (:value p) :contract-id)))))

(deftest propose-production-operation-shape
  (testing "production-operation scheduling proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-production-operation
                           :facility-id "facility-2" :contract-id "contract-2"
                           :patch {:line "blister-line-1" :date "2026-07-20"}})]
      (is (= :schedule-production-operation (:op p)))
      (is (= "facility-2" (:facility-id p)))
      (is (= "contract-2" (:contract-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-supply-order-shape
  (testing "supply-order proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-supply-order
                           :facility-id "facility-1" :contract-id "contract-1"
                           :patch {:item "tamper-evident cap seals restock" :quantity 20000 :estimated-cost 650.0
                                   :supplier-id "supplier-1"}})]
      (is (= :coordinate-supply-order (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p)))
      (is (= "supplier-1" (get-in p [:value :supplier-id]))))))

(deftest propose-quality-concern-shape
  (testing "quality-concern proposal always escalates"
    (let [p (adv/infer db {:op :flag-quality-concern
                           :facility-id "facility-1" :contract-id "contract-1"
                           :patch {:concern "tamper-evident seal appears compromised on sample"}})]
      (is (= :flag-quality-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-production-record :schedule-production-operation :coordinate-supply-order
                :flag-quality-concern]]
      (let [p (adv/infer db {:op op :facility-id "facility-1" :contract-id "contract-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-production-record :schedule-production-operation :coordinate-supply-order
                :flag-quality-concern]]
      (let [p (adv/infer db {:op op :facility-id "facility-1" :contract-id "contract-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))
