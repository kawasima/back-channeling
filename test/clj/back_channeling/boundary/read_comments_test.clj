(ns back-channeling.boundary.read-comments-test
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [back-channeling.test-helper :as th]
            [back-channeling.boundary.read-comments :as read-comments]
            [back-channeling.boundary.boards :as boards]))

(def ^:dynamic *system* nil)

(defn system-fixture [f]
  (let [sys (th/test-system)]
    (binding [*system* sys]
      (try
        (f)
        (finally
          (th/teardown-system sys))))))

(use-fixtures :each system-fixture)

(defn- create-thread [{:keys [connection]} board-id]
  (let [thread-id (d/tempid :db.part/user)
        now (java.util.Date.)
        tx @(d/transact connection
              [{:db/id thread-id
                :thread/title "Test Thread"
                :thread/since now
                :thread/last-updated now}
               [:db/add board-id :board/threads thread-id]])]
    (d/resolve-tempid (d/db connection) (:tempids tx) thread-id)))

(deftest save-read-comment-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        board-id (th/create-test-board *system* "default" "Default board")
        thread-id (create-thread *system* board-id)]

    (testing "creates read-comment record"
      (read-comments/save (:datomic *system*) thread-id
                          {:user/name "alice"} 5)
      (is (= 5 (boards/find-readnum (d/db (:connection *system*))
                                    thread-id "alice"))))

    (testing "updates to higher comment-no"
      (read-comments/save (:datomic *system*) thread-id
                          {:user/name "alice"} 10)
      (is (= 10 (boards/find-readnum (d/db (:connection *system*))
                                     thread-id "alice"))))

    (testing "does not decrease comment-no"
      (read-comments/save (:datomic *system*) thread-id
                          {:user/name "alice"} 3)
      (is (= 10 (boards/find-readnum (d/db (:connection *system*))
                                     thread-id "alice"))))))
