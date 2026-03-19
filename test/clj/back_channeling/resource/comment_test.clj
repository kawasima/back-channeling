(ns back-channeling.resource.comment-test
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [datomic.api :as d]
            [back-channeling.test-helper :as th]
            [back-channeling.boundary.threads :as threads]
            [back-channeling.resource.comment :refer [comments-resource comment-resource]]))

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

(defn- create-thread [board-name title content user-name]
  (let [user-ref [:user/name user-name]
        [tempids thread-tempid] (threads/save (:datomic *system*) board-name
                                              {:thread/title title
                                               :comment/content content}
                                              user-ref)]
    (d/resolve-tempid (d/db (:connection *system*)) tempids thread-tempid)))

(deftest comments-resource-get-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        thread-id (create-thread "default" "T1" "First comment" "alice")]

    (testing "GET comments returns comment list"
      (let [handler (comments-resource *system* "default" thread-id 1 nil)
            request (th/make-authenticated-request :get
                      :identity {:user/name "alice"})
            response (handler request)
            body (parse-body response)]
        (is (= 200 (:status response)))
        (is (= 1 (count body)))
        (is (= "First comment" (:comment/content (first body))))
        (is (= 1 (:comment/no (first body))))))

    (testing "GET comments without auth returns 401"
      (let [handler (comments-resource *system* "default" thread-id 1 nil)
            request {:request-method :get
                     :headers {"accept" "application/edn"}}
            response (handler request)]
        (is (= 401 (:status response)))))))

(deftest comments-resource-post-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        thread-id (create-thread "default" "T1" "First comment" "alice")]

    (testing "POST comment adds a comment"
      (let [handler (comments-resource *system* "default" thread-id 1 nil)
            request (th/make-authenticated-request :post
                      :identity {:user/name "alice"}
                      :body {:comment/content "Second comment"})
            response (handler request)]
        (is (= 201 (:status response)))))

    (testing "POST comment with markdown format succeeds"
      (let [handler (comments-resource *system* "default" thread-id 1 nil)
            request (th/make-authenticated-request :post
                      :identity {:user/name "alice"}
                      :body {:comment/content "**bold** text"
                             :comment/format :comment.format/markdown})
            response (handler request)]
        (is (= 201 (:status response)))))

    (testing "POST without auth returns 401"
      (let [handler (comments-resource *system* "default" thread-id 1 nil)
            request {:request-method :post
                     :headers {"accept" "application/edn"}
                     :content-type "application/edn"
                     :body (pr-str {:comment/content "Anon"})}
            response (handler request)]
        (is (= 401 (:status response)))))))

(deftest comments-resource-range-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        thread-id (create-thread "default" "T1" "Comment 1" "alice")]
    ;; Add more comments
    (doseq [i (range 2 6)]
      (let [handler (comments-resource *system* "default" thread-id 1 nil)
            request (th/make-authenticated-request :post
                      :identity {:user/name "alice"}
                      :body {:comment/content (str "Comment " i)})]
        (handler request)))

    (testing "GET with range returns subset"
      (let [handler (comments-resource *system* "default" thread-id 2 4)
            request (th/make-authenticated-request :get
                      :identity {:user/name "alice"})
            response (handler request)
            body (parse-body response)]
        (is (= 200 (:status response)))
        (is (= 3 (count body)))
        (is (= 2 (:comment/no (first body))))
        (is (= 4 (:comment/no (last body))))))))

(deftest comment-resource-delete-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        thread-id (create-thread "default" "T1" "To be deleted" "alice")]

    (testing "DELETE own comment hides it"
      (let [handler (comment-resource *system* "default" thread-id 1)
            request (th/make-authenticated-request :delete
                      :identity {:user/name "alice"})
            response (handler request)]
        (is (= 204 (:status response)))))

    (testing "hidden comment still visible to admin (read-any-thread permission)"
      (let [handler (comments-resource *system* "default" thread-id 1 nil)
            request (th/make-authenticated-request :get
                      :identity {:user/name "alice"
                                 :user/permissions #{:read-thread :read-any-thread}})
            response (handler request)
            body (parse-body response)]
        (is (= "To be deleted" (:comment/content (first body))))))

    (testing "hidden comment shows (deleted) to user without read-any-thread"
      (th/create-test-user *system* "bob" "bob@test.com")
      (let [handler (comments-resource *system* "default" thread-id 1 nil)
            request (th/make-authenticated-request :get
                      :identity {:user/name "bob"
                                 :user/permissions #{:read-thread :write-thread}})
            response (handler request)
            body (parse-body response)]
        (is (= "(deleted)" (:comment/content (first body))))))))

(deftest comment-resource-reaction-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        thread-id (create-thread "default" "T1" "React to me" "alice")]

    (testing "POST reaction to comment"
      (let [handler (comment-resource *system* "default" thread-id 1)
            request (th/make-authenticated-request :post
                      :identity {:user/name "alice"}
                      :body {:reaction/name "GJ"})
            response (handler request)]
        (is (= 201 (:status response)))))))
