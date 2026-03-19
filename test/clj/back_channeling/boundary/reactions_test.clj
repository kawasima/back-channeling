(ns back-channeling.boundary.reactions-test
  (:require [clojure.test :refer :all]
            [back-channeling.test-helper :as th]
            [back-channeling.boundary.reactions :as reactions]))

(def ^:dynamic *system* nil)

(defn system-fixture [f]
  (let [sys (th/test-system)]
    (binding [*system* sys]
      (try
        (f)
        (finally
          (th/teardown-system sys))))))

(use-fixtures :each system-fixture)

(deftest find-all-test
  (testing "returns seeded reactions"
    (let [result (reactions/find-all (:datomic *system*))]
      (is (seq result))
      ;; Migration seeds 12 reactions
      (is (= 12 (count result)))
      (let [names (set (map :reaction/name result))]
        (is (contains? names "GJ"))
        (is (contains? names "GM"))
        (is (contains? names "LOL"))))))

(deftest find-by-name-test
  (testing "returns entity id for existing reaction"
    (let [result (reactions/find-by-name (:datomic *system*) "GJ")]
      (is (number? result))))

  (testing "returns nil for non-existent reaction"
    (is (nil? (reactions/find-by-name (:datomic *system*) "NONEXISTENT")))))
