(ns back-channeling.resource.token-test
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [datomic.api :as d]
            [back-channeling.test-helper :as th]
            [back-channeling.resource.token :refer [token-resource]]))

(def ^:dynamic *system* nil)

(defn system-fixture [f]
  (let [sys (th/test-system)]
    (binding [*system* sys]
      (try
        (f)
        (finally
          (th/teardown-system sys))))))

(use-fixtures :each system-fixture)

(defn- parse-body [response]
  (when-let [body (:body response)]
    (cond
      (string? body) (edn/read-string body)
      :else body)))

(deftest token-resource-post-with-identity-test
  (testing "POST with authenticated identity returns access token"
    (let [handler (token-resource *system*)
          request (th/make-authenticated-request :post
                    :identity {:user/name "alice" :user/email "alice@test.com"})
          response (handler request)]
      (is (= 201 (:status response)))
      (let [body (parse-body response)]
        (is (= "alice" (:user/name body)))
        (is (= "bearer" (:token-type body)))
        (is (some? (:access-token body)))))))

(deftest token-resource-post-with-code-test
  (let [_ (th/create-test-user *system* "botuser" "bot@test.com")
        {:keys [connection]} *system*]
    ;; Create a token credential for the user
    @(d/transact connection
       [{:db/id (d/tempid :db.part/user)
         :token-credential/user [:user/name "botuser"]
         :token-credential/token "mycode123"}])

    (testing "POST with valid code returns access token"
      (let [handler (token-resource *system*)
            request {:request-method :post
                     :headers {"accept" "application/edn"}
                     :content-type "application/edn"
                     :params {:code "mycode123"}}
            response (handler request)]
        (is (= 201 (:status response)))
        (let [body (parse-body response)]
          (is (= "botuser" (:user/name body)))
          (is (some? (:access-token body))))))

    (testing "POST with invalid code returns 400"
      (let [handler (token-resource *system*)
            request {:request-method :post
                     :headers {"accept" "application/edn"}
                     :content-type "application/edn"
                     :params {:code "badcode"}}
            response (handler request)]
        (is (= 400 (:status response)))))

    (testing "POST without identity or code returns 400"
      (let [handler (token-resource *system*)
            request {:request-method :post
                     :headers {"accept" "application/edn"}
                     :content-type "application/edn"}
            response (handler request)]
        (is (= 400 (:status response)))))))
