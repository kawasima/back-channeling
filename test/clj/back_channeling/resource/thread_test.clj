(ns back-channeling.resource.thread-test
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [datomic.api :as d]
            [back-channeling.test-helper :as th]
            [back-channeling.boundary.threads :as threads]
            [back-channeling.resource.thread :refer [threads-resource thread-resource
                                                      thread-readonly-resource]]))

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

(defn- create-thread-via-resource [board-name title content user-name]
  (let [handler (threads-resource *system* board-name)
        request (th/make-authenticated-request :post
                  :identity {:user/name user-name}
                  :body {:thread/title title
                         :comment/content content})
        response (handler request)]
    (:db/id (parse-body response))))

(deftest thread-resource-put-watcher-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-user *system* "bob" "bob@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        thread-id (create-thread-via-resource "default" "Watch Test" "Content" "alice")]

    (testing "PUT add-watcher adds current user as watcher"
      (let [handler (thread-resource *system* "default" thread-id)
            request (th/make-authenticated-request :put
                      :identity {:user/name "bob"}
                      :body {:add-watcher true})
            response (handler request)]
        (is (= 201 (:status response)))
        ;; Verify bob is now a watcher
        (let [watchers (:thread/watchers (threads/find-watchers (:datomic *system*) thread-id))]
          (is (some #(= "bob" (:user/name %)) watchers)))))

    (testing "PUT remove-watcher removes current user from watchers"
      (let [handler (thread-resource *system* "default" thread-id)
            request (th/make-authenticated-request :put
                      :identity {:user/name "bob"}
                      :body {:remove-watcher true})
            response (handler request)]
        (is (= 201 (:status response)))
        (let [watchers (:thread/watchers (threads/find-watchers (:datomic *system*) thread-id))]
          (is (not (some #(= "bob" (:user/name %)) watchers))))))))

(deftest thread-resource-get-meta-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        thread-id (create-thread-via-resource "default" "Meta Test" "Content" "alice")]

    (testing "GET thread returns metadata without comments"
      (let [handler (thread-resource *system* "default" thread-id)
            request (th/make-authenticated-request :get
                      :identity {:user/name "alice"})
            response (handler request)
            body (parse-body response)]
        (is (= 200 (:status response)))
        (is (= "Meta Test" (:thread/title body)))
        (is (nil? (:thread/comments body)))))))

(deftest thread-readonly-resource-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        thread-id (create-thread-via-resource "default" "Readonly Test" "First post" "alice")]

    (testing "GET readonly thread returns full thread with comments"
      (let [handler (thread-readonly-resource *system* thread-id)
            request (th/make-authenticated-request :get
                      :identity {:user/name "alice"})
            response (handler request)
            body (parse-body response)]
        (is (= 200 (:status response)))
        (is (= "Readonly Test" (:thread/title body)))
        (is (seq (:thread/comments body)))
        (is (= "First post" (:comment/content (first (:thread/comments body)))))))

    (testing "GET readonly without auth returns 401"
      (let [handler (thread-readonly-resource *system* thread-id)
            request {:request-method :get
                     :headers {"accept" "application/edn"}}
            response (handler request)]
        (is (= 401 (:status response)))))))
