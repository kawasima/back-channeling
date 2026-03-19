(ns back-channeling.boundary.tokens-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [back-channeling.boundary.tokens :as tokens])
  (:import [java.util UUID]))

(defn- make-cache []
  (ig/init-key :back-channeling.database/cache {}))

(deftest new-token-test
  (let [cache (make-cache)]
    (testing "creates a UUID token"
      (let [token (tokens/new-token cache {:user/name "alice"})]
        (is (instance? UUID token))))

    (testing "different calls produce different tokens"
      (let [t1 (tokens/new-token cache {:user/name "alice"})
            t2 (tokens/new-token cache {:user/name "bob"})]
        (is (not= t1 t2))))))

(deftest auth-by-test
  (let [cache (make-cache)
        user {:user/name "alice" :user/email "alice@test.com"}
        token (tokens/new-token cache user)]

    (testing "authenticates with UUID token"
      (is (= user (tokens/auth-by cache token))))

    (testing "authenticates with string token"
      (is (= user (tokens/auth-by cache (str token)))))

    (testing "returns nil for unknown token"
      (is (nil? (tokens/auth-by cache (UUID/randomUUID)))))))
