(ns back-channeling.auth.backend.token-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [duct.logger]
            [buddy.auth.protocols :as proto]
            [back-channeling.boundary.tokens :as tokens]
            [back-channeling.auth.backend.token]))

(def noop-logger
  (reify duct.logger/Logger
    (-log [_ level ns-str file line id event data] nil)))

(deftest token-backend-test
  (let [cache (ig/init-key :back-channeling.database/cache {})
        backend (ig/init-key :back-channeling.auth.backend/token
                  {:cache cache :logger noop-logger})
        user {:user/name "alice" :user/email "alice@test.com"}
        token (tokens/new-token cache user)]

    (testing "creates a backend"
      (is (some? backend))
      (is (satisfies? proto/IAuthentication backend)))

    (testing "parses token from Authorization header"
      (let [request {:headers {"authorization" (str "Token " token)}}
            data (proto/-parse backend request)]
        (is (= (str token) data))))

    (testing "authenticates valid token"
      (let [result (proto/-authenticate backend {} (str token))]
        (is (= user result))))

    (testing "returns nil for invalid token"
      (let [result (proto/-authenticate backend {} "invalid-token")]
        (is (nil? result))))))
