(ns back-channeling.endpoint.api-test
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [back-channeling.test-helper :as th]
            [back-channeling.resource.board :refer [boards-resource board-resource]]
            [back-channeling.resource.thread :refer [threads-resource thread-resource
                                                      thread-readonly-resource]]
            [back-channeling.resource.comment :refer [comments-resource]]
            [back-channeling.resource.reaction :refer [reactions-resource]]
            [back-channeling.resource.article :refer [articles-resource]]))

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

;; -- Board resource (single board with threads) ---------------------------

(deftest board-resource-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-board *system* "myboard" "Test board")]
    ;; Create a thread so board has content
    (let [handler (threads-resource *system* "myboard")
          request (th/make-authenticated-request :post
                    :identity {:user/name "alice"}
                    :body {:thread/title "Thread 1"
                           :comment/content "Hello"})
          response (handler request)]
      (is (= 201 (:status response))))

    (testing "GET board returns threads with writenum defaulting to 0"
      (let [handler (board-resource *system* "myboard")
            request (th/make-authenticated-request :get
                      :identity {:user/name "alice"})
            response (handler request)
            body (parse-body response)]
        (is (= 200 (:status response)))
        (is (= 1 (count (:board/threads body))))
        ;; alice posted 1 comment so writenum should be 1
        (is (= 1 (:thread/writenum (first (:board/threads body)))))))

    (testing "GET board with different user has writenum 0"
      (th/create-test-user *system* "bob" "bob@test.com")
      (let [handler (board-resource *system* "myboard")
            request (th/make-authenticated-request :get
                      :identity {:user/name "bob"})
            response (handler request)
            body (parse-body response)]
        (is (= 200 (:status response)))
        (is (= 0 (:thread/writenum (first (:board/threads body)))))))))

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

;; -- Thread resource (single thread metadata) -----------------------------

(deftest thread-resource-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        ;; Create a thread
        create-handler (threads-resource *system* "default")
        create-resp (create-handler
                     (th/make-authenticated-request :post
                       :identity {:user/name "alice"}
                       :body {:thread/title "Meta test"
                              :comment/content "First comment"}))
        thread-id (:db/id (parse-body create-resp))]

    (testing "GET thread returns metadata without comments"
      (let [handler (thread-resource *system* "default" thread-id)
            request (th/make-authenticated-request :get
                      :identity {:user/name "alice"})
            response (handler request)
            body (parse-body response)]
        (is (= 200 (:status response)))
        (is (= "Meta test" (:thread/title body)))
        (is (nil? (:thread/comments body)))))

    (testing "GET readonly thread returns comments"
      (let [handler (thread-readonly-resource *system* thread-id)
            request (th/make-authenticated-request :get
                      :identity {:user/name "alice"})
            response (handler request)
            body (parse-body response)]
        (is (= 200 (:status response)))
        (is (= "Meta test" (:thread/title body)))
        (is (seq (:thread/comments body)))
        (is (= "First comment" (:comment/content (first (:thread/comments body)))))))))

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
