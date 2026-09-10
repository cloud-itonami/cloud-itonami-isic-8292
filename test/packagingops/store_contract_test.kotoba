(ns packagingops.store-contract-test
  "Contract tests for `packagingops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [packagingops.store :as store]))

(deftest mem-store-facility-lookup
  (testing "MemStore can store and retrieve facilities by ID (string keys)"
    (let [facilities {"f1" {:facility-id "f1" :name "Alice's Co-Pack" :registered? true :verified? true}}
          s (store/mem-store facilities)]
      (is (some? (store/facility-record s "f1")))
      (is (nil? (store/facility-record s "f99"))))))

(deftest mem-store-all-facility-records
  (testing "MemStore returns all facilities in sorted order"
    (let [facilities {"f2" {:facility-id "f2" :name "Bob's Bottling Line"}
                      "f1" {:facility-id "f1" :name "Alice's Co-Pack"}
                      "f3" {:facility-id "f3" :name "Carol's Blister Line"}}
          s (store/mem-store facilities)
          all-f (store/all-facility-records s)]
      (is (= 3 (count all-f)))
      (is (= "f1" (:facility-id (first all-f))))
      (is (= "f3" (:facility-id (last all-f)))))))

(deftest mem-store-contract-lookup
  (testing "MemStore can store and retrieve client contracts by ID (string keys)"
    (let [contracts {"c1" {:contract-id "c1" :client "Acme Foods" :registered? true :verified? true}}
          s (store/mem-store {} contracts)]
      (is (some? (store/contract-record s "c1")))
      (is (nil? (store/contract-record s "c99"))))))

(deftest mem-store-all-contract-records
  (testing "MemStore returns all contracts in sorted order"
    (let [contracts {"c2" {:contract-id "c2" :client "Beta Pharma"}
                     "c1" {:contract-id "c1" :client "Acme Foods"}}
          s (store/mem-store {} contracts)
          all-c (store/all-contract-records s)]
      (is (= 2 (count all-c)))
      (is (= "c1" (:contract-id (first all-c)))))))

(deftest mem-store-supplier-lookup
  (testing "MemStore can store and retrieve suppliers by ID (string keys)"
    (let [suppliers {"v1" {:supplier-id "v1" :name "Acme Packaging Materials" :registered? true :verified? true}}
          s (store/mem-store {} {} suppliers)]
      (is (some? (store/supplier-record s "v1")))
      (is (nil? (store/supplier-record s "v99"))))))

(deftest mem-store-all-supplier-records
  (testing "MemStore returns all suppliers in sorted order"
    (let [suppliers {"v2" {:supplier-id "v2" :name "Beta Carton Supply"}
                     "v1" {:supplier-id "v1" :name "Acme Packaging Materials"}}
          s (store/mem-store {} {} suppliers)
          all-v (store/all-supplier-records s)]
      (is (= 2 (count all-v)))
      (is (= "v1" (:supplier-id (first all-v)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-production-record :facility-id "f1" :value {:units-packaged 42}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-facility-records
  (testing "MemStore with-facility-records replaces the facility directory"
    (let [s (store/mem-store {})
          new-facilities {"f1" {:facility-id "f1" :name "Alice's Co-Pack"}}]
      (is (= 0 (count (store/all-facility-records s))))
      (store/with-facility-records s new-facilities)
      (is (= 1 (count (store/all-facility-records s)))))))

(deftest mem-store-with-contract-records
  (testing "MemStore with-contract-records replaces the contract directory"
    (let [s (store/mem-store {})
          new-contracts {"c1" {:contract-id "c1" :client "Acme Foods"}}]
      (is (= 0 (count (store/all-contract-records s))))
      (store/with-contract-records s new-contracts)
      (is (= 1 (count (store/all-contract-records s)))))))

(deftest mem-store-with-supplier-records
  (testing "MemStore with-supplier-records replaces the supplier directory"
    (let [s (store/mem-store {})
          new-suppliers {"v1" {:supplier-id "v1" :name "Acme Packaging Materials"}}]
      (is (= 0 (count (store/all-supplier-records s))))
      (store/with-supplier-records s new-suppliers)
      (is (= 1 (count (store/all-supplier-records s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo facilities/contracts/suppliers"
    (let [s (store/seed-db)]
      (is (> (count (store/all-facility-records s)) 0))
      (is (some? (store/facility-record s "facility-1")))
      (is (some? (store/facility-record s "facility-2")))
      (is (some? (store/facility-record s "facility-3")))
      (is (> (count (store/all-contract-records s)) 0))
      (is (some? (store/contract-record s "contract-1")))
      (is (some? (store/contract-record s "contract-2")))
      (is (> (count (store/all-supplier-records s)) 0))
      (is (some? (store/supplier-record s "supplier-1")))
      (is (some? (store/supplier-record s "supplier-2"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for facility-id/contract-id/supplier-id"
    (let [demo (store/demo-data)
          facilities (:facilities demo)
          contracts (:contracts demo)
          suppliers (:suppliers demo)]
      (doseq [[k v] facilities]
        (is (string? k) "facility keys must be strings")
        (is (string? (:facility-id v)) "facility-id must be string")
        (is (= k (:facility-id v)) "key must match facility-id"))
      (doseq [[k v] contracts]
        (is (string? k) "contract keys must be strings")
        (is (string? (:contract-id v)) "contract-id must be string")
        (is (= k (:contract-id v)) "key must match contract-id"))
      (doseq [[k v] suppliers]
        (is (string? k) "supplier keys must be strings")
        (is (string? (:supplier-id v)) "supplier-id must be string")
        (is (= k (:supplier-id v)) "key must match supplier-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
