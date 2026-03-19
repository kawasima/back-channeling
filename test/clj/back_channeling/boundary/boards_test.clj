(ns back-channeling.boundary.boards-test
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [back-channeling.test-helper :as th]
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

(deftest find-readnum-test
  (let [{:keys [connection]} *system*
        user-id (th/create-test-user *system* "alice" "alice@test.com")
        board-id (th/create-test-board *system* "board1" "Test board")
        ;; Create a thread
        thread-id (d/tempid :db.part/user)
        tx @(d/transact connection
              [{:db/id thread-id
                :thread/title "Thread 1"
                :thread/since (java.util.Date.)
                :thread/last-updated (java.util.Date.)}
               [:db/add board-id :board/threads thread-id]])
        thread-eid (d/resolve-tempid (d/db connection) (:tempids tx) thread-id)]

    (testing "returns 0 when no read-comment record exists"
      (is (= 0 (boards/find-readnum (d/db connection) thread-eid "alice"))))

    (testing "returns comment-no after saving read-comment"
      @(d/transact connection
         [{:db/id (d/tempid :db.part/user)
           :read-comment/thread thread-eid
           :read-comment/user user-id
           :read-comment/comment-no 5}])
      (is (= 5 (boards/find-readnum (d/db connection) thread-eid "alice"))))

    (testing "returns 0 for a different user"
      (is (= 0 (boards/find-readnum (d/db connection) thread-eid "bob"))))))

(deftest find-by-name-test
  (testing "returns nil when board does not exist"
    (is (nil? (boards/find-by-name (:datomic *system*) "nonexistent"))))

  (testing "returns board when it exists"
    (th/create-test-board *system* "myboard" "My Board")
    (let [result (boards/find-by-name (:datomic *system*) "myboard")]
      (is (some? result))
      (is (= "myboard" (:board/name result)))
      (is (= "My Board" (:board/description result))))))

(deftest find-all-test
  (testing "returns list including default board from migration"
    (let [result (boards/find-all (:datomic *system*) {:user/name "alice"})]
      (is (vector? result))
      (is (= 1 (count result)))
      (is (= "default" (:board/name (first result))))))

  (testing "returns created boards"
    (th/create-test-board *system* "board-a" "Board A")
    (th/create-test-board *system* "board-b" "Board B")
    (let [result (boards/find-all (:datomic *system*) {:user/name "alice"})
          names (set (map :board/name result))]
      (is (contains? names "board-a"))
      (is (contains? names "board-b")))))

(deftest save-board-test
  (testing "save new board returns entity id"
    (let [board-id (boards/save (:datomic *system*)
                                {:board/name "new-board"
                                 :board/description "New board"})]
      (is (number? board-id))
      (let [found (boards/find-by-name (:datomic *system*) "new-board")]
        (is (= "new-board" (:board/name found))))))

  (testing "save existing board updates it"
    (let [board-id (boards/save (:datomic *system*)
                                {:board/name "update-me"
                                 :board/description "Original"})]
      (boards/save (:datomic *system*)
                   {:board/name "update-me"
                    :board/description "Updated"}
                   board-id)
      (let [found (boards/find-by-name (:datomic *system*) "update-me")]
        (is (= "Updated" (:board/description found)))))))

(deftest find-threads-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        board-id (th/create-test-board *system* "threadboard" "Thread Board")
        {:keys [connection]} *system*
        ;; Create a thread with a comment
        thread-id (d/tempid :db.part/user -1)
        comment-id (d/tempid :db.part/user -2)
        user-ref [:user/name "alice"]
        now (java.util.Date.)
        tx @(d/transact connection
              [{:db/id thread-id
                :thread/title "T1"
                :thread/since now
                :thread/last-updated now
                :thread/public? true}
               {:db/id comment-id
                :comment/posted-at now
                :comment/posted-by user-ref
                :comment/content "Hello"
                :comment/public? true}
               [:db/add thread-id :thread/comments comment-id]
               [:db/add board-id :board/threads thread-id]])]

    (testing "returns threads with resnum and readnum"
      (let [threads (boards/find-threads (:datomic *system*) board-id
                                         {:user/name "alice"})]
        (is (= 1 (count threads)))
        (let [t (first threads)]
          (is (= "T1" (:thread/title t)))
          (is (= 1 (:thread/resnum t)))
          (is (= 0 (:thread/readnum t))))))))
