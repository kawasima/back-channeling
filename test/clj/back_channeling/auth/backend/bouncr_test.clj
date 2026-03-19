(ns back-channeling.auth.backend.bouncr-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [datomic.api :as d]
            [buddy.auth.protocols :as proto]
            [buddy.sign.jwt :as jwt]
            [back-channeling.test-helper :as th]
            [back-channeling.auth.backend.bouncr :refer [register-user authfn-default]]))

(def ^:dynamic *system* nil)

(defn system-fixture [f]
  (let [sys (th/test-system)]
    (binding [*system* sys]
      (try
        (f)
        (finally
          (th/teardown-system sys))))))

(use-fixtures :each system-fixture)

(deftest register-user-test
  (testing "creates a new user in the database"
    (register-user (:datomic *system*) "newuser" "new@test.com")
    (let [user (d/q '{:find [(pull ?u [:user/name :user/email]) .]
                      :in [$ ?name]
                      :where [[?u :user/name ?name]]}
                    (d/db (:connection *system*)) "newuser")]
      (is (= "newuser" (:user/name user)))
      (is (= "new@test.com" (:user/email user))))))

(deftest authfn-default-test
  (testing "returns existing user"
    (th/create-test-user *system* "alice" "alice@test.com")
    (let [result (authfn-default (:datomic *system*)
                                {:user/name "alice" :user/email "alice@test.com"})]
      (is (some? result))
      (is (number? (:db/id result)))
      (is (= "alice" (:user/name result)))))

  (testing "creates and returns new user if not found"
    (let [result (authfn-default (:datomic *system*)
                                {:user/name "newbie" :user/email "newbie@test.com"})]
      (is (some? result))
      ;; Should return identity-compatible map with user info
      (is (= "newbie" (:user/name result)))
      (is (number? (:db/id result)))
      ;; Verify user was persisted
      (let [user (d/q '{:find [?u .]
                        :in [$ ?name]
                        :where [[?u :user/name ?name]]}
                      (d/db (:connection *system*)) "newbie")]
        (is (some? user))))))

(deftest bouncr-backend-test
  (let [pkey "test-secret-key"
        backend (ig/init-key :back-channeling.auth.backend/bouncr
                  {:datomic (:datomic *system*) :pkey pkey})]

    (testing "creates a backend when pkey is provided"
      (is (some? backend))
      (is (satisfies? proto/IAuthentication backend)))

    (testing "parses valid JWT from x-bouncr-credential header"
      (let [token (jwt/sign {:sub "alice" :email "alice@test.com"
                             :permissions ["READ_THREAD" "WRITE_THREAD"]}
                            pkey {:alg :hs256})
            request {:headers {"x-bouncr-credential" token}}
            data (proto/-parse backend request)]
        (is (= "alice" (:user/name data)))
        (is (= "alice@test.com" (:user/email data)))
        (is (contains? (:user/permissions data) :read-thread))
        (is (contains? (:user/permissions data) :write-thread))))

    (testing "parse returns nil for invalid JWT"
      (let [request {:headers {"x-bouncr-credential" "invalid-token"}}
            data (proto/-parse backend request)]
        (is (nil? (:user/name data)))))

    (testing "parse returns nil when header is missing"
      (let [data (proto/-parse backend {:headers {}})]
        (is (nil? (:user/name data)))))

    (testing "unauthorized handler returns 401 for API request"
      (let [request {:headers {"accept" "application/json"} :uri "/api/boards"}
            response (proto/-handle-unauthorized backend request {})]
        (is (= 401 (:status response)))))

    (testing "unauthorized handler redirects for browser request"
      (let [request {:headers {"accept" "text/html"} :uri "/boards"}
            response (proto/-handle-unauthorized backend request {})]
        (is (= 302 (:status response)))))))

(deftest bouncr-backend-nil-when-no-pkey-test
  (testing "returns nil when pkey is not configured"
    (let [backend (ig/init-key :back-channeling.auth.backend/bouncr
                    {:datomic (:datomic *system*)})]
      (is (nil? backend)))))
