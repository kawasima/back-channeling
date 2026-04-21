(ns back-channeling.resource.base-thread-allowed-test
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [back-channeling.test-helper :as th]
            [back-channeling.boundary.threads :as threads]
            [back-channeling.resource.base :refer [thread-allowed?]]))

(def ^:dynamic *system* nil)

(defn system-fixture [f]
  (let [sys (th/test-system)]
    (binding [*system* sys]
      (try
        (f)
        (finally
          (th/teardown-system sys))))))

(use-fixtures :each system-fixture)

(defn- create-thread [board-name title content user-name]
  (let [user-ref [:user/name user-name]
        [tempids thread-tempid] (threads/save (:datomic *system*) board-name
                                              {:thread/title title
                                               :comment/content content}
                                              user-ref)]
    (d/resolve-tempid (d/db (:connection *system*)) tempids thread-tempid)))

(deftest thread-allowed?-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-user *system* "bob" "bob@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        thread-id (create-thread "default" "Public Thread" "Hello" "alice")]

    (testing "allows access to public thread without special permissions"
      (let [ctx {:request {:identity {:user/name "bob"}}
                 :identity {:user/name "bob"}}]
        (is (true? (thread-allowed? ctx (:datomic *system*) #{:write-any-thread} thread-id)))))

    (testing "allows access when user has write-any-thread permission"
      (let [ctx {:request {:identity {:user/name "bob"
                                      :user/permissions #{:write-any-thread}}}
                 :identity {:user/name "bob"
                            :user/permissions #{:write-any-thread}}}]
        (is (true? (thread-allowed? ctx (:datomic *system*) #{:write-any-thread} thread-id)))))

    (testing "allows access when user has posted in the thread"
      ;; Alice posted the initial comment, so she should have access
      (let [ctx {:request {:identity {:user/name "alice"}}
                 :identity {:user/name "alice"}}]
        (is (true? (thread-allowed? ctx (:datomic *system*) #{:write-any-thread} thread-id)))))

    (testing "denies access to closed thread for user who hasn't posted"
      (threads/close-thread (:datomic *system*) thread-id)
      (let [ctx {:request {:identity {:user/name "bob"
                                      :user/permissions #{:write-thread}}}
                 :identity {:user/name "bob"
                            :user/permissions #{:write-thread}}}]
        (is (false? (thread-allowed? ctx (:datomic *system*) #{:write-any-thread} thread-id)))))

    (testing "permissions argument is honored (not hardcoded to write-any-thread)"
      ;; Thread is closed by the previous testing block.
      ;; bob has :read-any-thread but NOT :write-any-thread and has not posted.
      (let [ctx {:request {:identity {:user/name "bob"
                                      :user/permissions #{:read-any-thread}}}
                 :identity {:user/name "bob"
                            :user/permissions #{:read-any-thread}}}]
        (is (true?  (thread-allowed? ctx (:datomic *system*) #{:read-any-thread}  thread-id)))
        (is (false? (thread-allowed? ctx (:datomic *system*) #{:write-any-thread} thread-id)))))))
