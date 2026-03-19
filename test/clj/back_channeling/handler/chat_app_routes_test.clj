(ns back-channeling.handler.chat-app-routes-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [datomic.api :as d]
            [buddy.hashers :as hashers]
            [back-channeling.test-helper :as th]
            [back-channeling.handler.chat-app :as chat-app]))

(def ^:dynamic *system* nil)

(defn system-fixture [f]
  (let [sys (th/test-system)]
    (binding [*system* sys]
      (try
        (f)
        (finally
          (th/teardown-system sys))))))

(use-fixtures :each system-fixture)

(defn- create-user-with-password
  [{:keys [connection]} user-name email password]
  (let [user-id (d/tempid :db.part/user -1)
        pw-id (d/tempid :db.part/user -2)
        hashed (hashers/derive password)]
    @(d/transact connection
       [{:db/id user-id
         :user/name user-name
         :user/email email}
        {:db/id pw-id
         :password-credential/user user-id
         :password-credential/password hashed}])))

(deftest init-handler-test
  (testing "creates handler with login enabled"
    (let [handler (ig/init-key :back-channeling.handler/chat-app
                    {:datomic (:datomic *system*)
                     :login-enabled? true
                     :prefix ""
                     :env :development})]
      (is (fn? handler))))

  (testing "creates handler with login disabled"
    (let [handler (ig/init-key :back-channeling.handler/chat-app
                    {:datomic (:datomic *system*)
                     :login-enabled? false
                     :prefix ""
                     :env :development})]
      (is (fn? handler)))))

(deftest index-view-test
  (let [handler (ig/init-key :back-channeling.handler/chat-app
                  {:datomic (:datomic *system*)
                   :login-enabled? false
                   :prefix ""
                   :env :development})]
    (testing "GET / returns HTML"
      (let [response (handler {:request-method :get
                               :uri "/"
                               :headers {}})]
        (is (= 200 (:status response)))
        (is (string? (:body response)))
        (is (.contains (:body response) "back-channeling"))))))

(deftest css-route-test
  (let [handler (ig/init-key :back-channeling.handler/chat-app
                  {:datomic (:datomic *system*)
                   :login-enabled? false
                   :prefix ""
                   :env :development})]
    (testing "GET /css/back-channeling.css returns CSS"
      (let [response (handler {:request-method :get
                               :uri "/css/back-channeling.css"
                               :headers {}})]
        (is (= 200 (:status response)))
        (is (string? (:body response)))))))

(deftest login-view-test
  (let [handler (ig/init-key :back-channeling.handler/chat-app
                  {:datomic (:datomic *system*)
                   :login-enabled? true
                   :prefix ""
                   :env :development})]
    (testing "GET /login returns login form"
      (let [response (handler {:request-method :get
                               :uri "/login"
                               :headers {}
                               :session {}})]
        (is (= 200 (:status response)))
        (is (.contains (:body response) "Login"))))))

(deftest logout-route-test
  (let [handler (ig/init-key :back-channeling.handler/chat-app
                  {:datomic (:datomic *system*)
                   :login-enabled? true
                   :prefix ""
                   :env :development})]
    (testing "POST /logout clears session and redirects"
      (let [response (handler {:request-method :post
                               :uri "/logout"
                               :headers {}
                               :session {:identity {:user/name "alice"}}})]
        (is (= 302 (:status response)))
        (is (= {} (:session response)))))))

(deftest voice-route-filename-validation-test
  (let [handler (ig/init-key :back-channeling.handler/chat-app
                  {:datomic (:datomic *system*)
                   :login-enabled? false
                   :prefix ""
                   :env :development})]
    (testing "rejects voice request with invalid filename"
      (let [response (handler {:request-method :get
                               :uri "/voice/123/../../etc/passwd"
                               :headers {}})]
        ;; Should be nil (no route match) or 404, not a file disclosure
        (is (or (nil? response) (#{400 404} (:status response))))))

    (testing "rejects voice request with non-matching thread-id"
      (let [response (handler {:request-method :get
                               :uri "/voice/abc/file.ogg"
                               :headers {}})]
        ;; "abc" doesn't match #"\d+" so route won't match
        (is (nil? response))))))
