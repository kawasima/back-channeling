(ns back-channeling.resource.user-test
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [back-channeling.test-helper :as th]
            [back-channeling.resource.user :refer [users-resource user-resource]]))

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

(deftest users-resource-test
  (testing "GET users without auth returns 401"
    (let [handler (users-resource *system*)
          request {:request-method :get
                   :headers {"accept" "application/edn"}}
          response (handler request)]
      (is (= 401 (:status response)))))

  (testing "GET users with auth returns list"
    ;; MockSocketapp needs :channels and :path for find-users to work
    (let [system (assoc *system* :socketapp
                        {:channels (atom {"/ws" {:ch1 {:user {:user/name "alice"}}}})
                         :path "/ws"})
          handler (users-resource system)
          request (th/make-authenticated-request :get
                    :identity {:user/name "alice"})
          response (handler request)
          body (parse-body response)]
      (is (= 200 (:status response)))
      (is (= #{{:user/name "alice"}} (set body))))))

(deftest user-resource-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")]
    (testing "GET existing user returns user data"
      (let [handler (user-resource *system* "alice")
            request (th/make-authenticated-request :get
                      :identity {:user/name "bob"})
            response (handler request)
            body (parse-body response)]
        (is (= 200 (:status response)))
        (is (= "alice" (:user/name body)))))

    (testing "GET own user includes permissions"
      (let [handler (user-resource *system* "alice")
            request (th/make-authenticated-request :get
                      :identity {:user/name "alice"
                                 :user/permissions #{:read-thread}})
            response (handler request)
            body (parse-body response)]
        (is (= 200 (:status response)))
        (is (= #{:read-thread} (:user/permissions body)))))

    (testing "GET non-existent user returns 404"
      (let [handler (user-resource *system* "ghost")
            request (th/make-authenticated-request :get
                      :identity {:user/name "alice"})
            response (handler request)]
        (is (= 404 (:status response)))))))
