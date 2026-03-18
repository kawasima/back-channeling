(ns back-channeling.endpoint.api-test
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [back-channeling.test-helper :as th]
            [back-channeling.resource.board :refer [boards-resource board-resource]]
            [back-channeling.resource.thread :refer [threads-resource]]
            [back-channeling.resource.comment :refer [comments-resource]]
            [back-channeling.resource.reaction :refer [reactions-resource]]
            [back-channeling.resource.article :refer [articles-resource]]
            [liberator.dev :refer [wrap-trace]]))

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

;; -- Board tests ----------------------------------------------------------

(deftest boards-resource-test
  (testing "GET boards returns empty list"
    (let [handler (boards-resource *system*)
          request (th/make-authenticated-request :get
                    :identity {:user/name "testuser"})
          response (handler request)]
      (is (= 200 (:status response)))))

  (testing "POST board without auth returns 401"
    (let [handler (boards-resource *system*)
          request {:request-method :post
                   :headers {"accept" "application/edn"}
                   :content-type "application/edn"
                   :body (pr-str {:board/name "test-board"})}
          response (handler request)]
      (is (= 401 (:status response)))))

  (testing "POST board with auth creates board"
    (let [handler (boards-resource *system*)
          request (th/make-authenticated-request :post
                    :identity {:user/name "testuser"}
                    :body {:board/name "test-board"
                           :board/description "A test board"})
          response (handler request)]
      (is (= 201 (:status response))))))

;; -- Thread tests ---------------------------------------------------------

(deftest threads-resource-test
  (let [_ (th/create-test-user *system* "poster" "poster@test.com")
        _ (th/create-test-board *system* "default" "Default board")]
    (testing "POST thread creates a new thread"
      (let [handler (threads-resource *system* "default")
            request (th/make-authenticated-request :post
                      :identity {:user/name "poster"}
                      :body {:thread/title "Test thread"
                             :comment/content "First comment"})
            response (handler request)]
        (is (= 201 (:status response)))
        ;; Verify socketapp received broadcast
        (is (pos? (count (th/sent-messages (:socketapp *system*)))))))

    (testing "POST thread without auth returns 401"
      (let [handler (threads-resource *system* "default")
            request {:request-method :post
                     :headers {"accept" "application/edn"}
                     :content-type "application/edn"
                     :body (pr-str {:thread/title "Anon thread"
                                    :comment/content "Should fail"})}
            response (handler request)]
        (is (= 401 (:status response)))))))

;; -- Article tests --------------------------------------------------------

(deftest articles-resource-auth-test
  (testing "GET articles requires authentication"
    (let [handler (articles-resource *system*)
          request {:request-method :get
                   :headers {"accept" "application/edn"}}
          response (handler request)]
      (is (= 401 (:status response)))))

  (testing "GET articles with auth returns 200"
    (let [handler (articles-resource *system*)
          request (th/make-authenticated-request :get
                    :identity {:user/name "testuser"})
          response (handler request)]
      (is (= 200 (:status response))))))

;; -- Reactions tests ------------------------------------------------------

(deftest reactions-resource-test
  (testing "GET reactions requires auth"
    (let [handler (reactions-resource *system*)
          request {:request-method :get
                   :headers {"accept" "application/edn"}}
          response (handler request)]
      (is (= 401 (:status response)))))

  (testing "GET reactions with auth returns 200"
    (let [handler (reactions-resource *system*)
          request (th/make-authenticated-request :get
                    :identity {:user/name "testuser"})
          response (handler request)]
      (is (= 200 (:status response))))))
