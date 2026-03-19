(ns back-channeling.database.cache-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [clojure.core.cache :as cache]))

(deftest cache-init-test
  (testing "creates a cache boundary with default TTL"
    (let [boundary (ig/init-key :back-channeling.database/cache {})]
      (is (some? boundary))
      (is (some? (:cache boundary)))
      (is (some? (:scheduler boundary)))
      (ig/halt-key! :back-channeling.database/cache boundary)))

  (testing "creates a cache boundary with custom TTL"
    (let [boundary (ig/init-key :back-channeling.database/cache {:ttl 5000})]
      (is (some? boundary))
      (ig/halt-key! :back-channeling.database/cache boundary))))

(deftest cache-operations-test
  (let [boundary (ig/init-key :back-channeling.database/cache {:ttl 60000})]
    (try
      (testing "stores and retrieves values"
        (swap! (:cache boundary) assoc :key1 "value1")
        (is (= "value1" (cache/lookup @(:cache boundary) :key1))))

      (testing "returns nil for non-existent key"
        (is (nil? (cache/lookup @(:cache boundary) :nonexistent))))

      (testing "supports multiple entries"
        (swap! (:cache boundary) assoc :key2 "value2")
        (swap! (:cache boundary) assoc :key3 "value3")
        (is (= "value1" (cache/lookup @(:cache boundary) :key1)))
        (is (= "value2" (cache/lookup @(:cache boundary) :key2)))
        (is (= "value3" (cache/lookup @(:cache boundary) :key3))))
      (finally
        (ig/halt-key! :back-channeling.database/cache boundary)))))

(deftest cache-halt-test
  (testing "halt shuts down scheduler"
    (let [boundary (ig/init-key :back-channeling.database/cache {})]
      (ig/halt-key! :back-channeling.database/cache boundary)
      (is (.isShutdown (:scheduler boundary))))))

(deftest cache-ttl-expiry-test
  (testing "entries expire after TTL"
    (let [boundary (ig/init-key :back-channeling.database/cache {:ttl 50})]
      (try
        (swap! (:cache boundary) assoc :expiring "soon")
        (is (= "soon" (cache/lookup @(:cache boundary) :expiring)))
        (Thread/sleep 100)
        ;; After TTL, has? returns false
        (is (not (cache/has? @(:cache boundary) :expiring)))
        (finally
          (ig/halt-key! :back-channeling.database/cache boundary))))))
