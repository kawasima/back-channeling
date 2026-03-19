(ns back-channeling.boundary.threads-test
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [back-channeling.test-helper :as th]
            [back-channeling.boundary.threads :as threads]))

(def ^:dynamic *system* nil)

(defn system-fixture [f]
  (let [sys (th/test-system)]
    (binding [*system* sys]
      (try
        (f)
        (finally
          (th/teardown-system sys))))))

(use-fixtures :each system-fixture)

(defn- create-thread-via-boundary [board-name title content user-name]
  (let [user-ref [:user/name user-name]
        [tempids thread-tempid] (threads/save (:datomic *system*) board-name
                                              {:thread/title title
                                               :comment/content content}
                                              user-ref)]
    (d/resolve-tempid (d/db (:connection *system*)) tempids thread-tempid)))

(deftest save-thread-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-board *system* "default" "Default board")]

    (testing "save creates a thread with initial comment"
      (let [thread-id (create-thread-via-boundary "default" "My Thread" "First post" "alice")]
        (is (number? thread-id))
        (let [thread (threads/find-thread (:datomic *system*) thread-id)]
          (is (= "My Thread" (:thread/title thread)))
          (is (= 1 (count (:thread/comments thread))))
          (is (= "First post" (:comment/content (first (:thread/comments thread))))))))))

(deftest find-thread-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        thread-id (create-thread-via-boundary "default" "Find Me" "Content" "alice")]

    (testing "find-thread returns thread with comments and comment numbers"
      (let [thread (threads/find-thread (:datomic *system*) thread-id)]
        (is (= "Find Me" (:thread/title thread)))
        (is (= 1 (:comment/no (first (:thread/comments thread)))))))

    (testing "find-thread-meta returns metadata without comments"
      (let [meta (threads/find-thread-meta (:datomic *system*) thread-id)]
        (is (= "Find Me" (:thread/title meta)))
        (is (nil? (:thread/comments meta)))))))

(deftest pull-thread-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        thread-id (create-thread-via-boundary "default" "Pull Me" "Content" "alice")]

    (testing "pull returns thread attributes"
      (let [result (threads/pull (:datomic *system*) thread-id)]
        (is (= "Pull Me" (:thread/title result)))
        (is (= thread-id (:db/id result)))))))

(deftest watcher-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-user *system* "bob" "bob@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        thread-id (create-thread-via-boundary "default" "Watch Me" "Content" "alice")]

    (testing "add-watcher adds a user as watcher"
      (threads/add-watcher (:datomic *system*) thread-id {:user/name "bob"})
      (let [watchers (:thread/watchers (threads/find-watchers (:datomic *system*) thread-id))]
        (is (some #(= "bob" (:user/name %)) watchers))))

    (testing "remove-watcher removes a user from watchers"
      (threads/remove-watcher (:datomic *system*) thread-id {:user/name "bob"})
      (let [watchers (:thread/watchers (threads/find-watchers (:datomic *system*) thread-id))]
        (is (not (some #(= "bob" (:user/name %)) watchers)))))))

(deftest open-close-thread-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        thread-id (create-thread-via-boundary "default" "Toggle Me" "Content" "alice")]

    (testing "close-thread sets public? to false"
      (threads/close-thread (:datomic *system*) thread-id)
      (let [meta (threads/find-thread-meta (:datomic *system*) thread-id)]
        (is (false? (:thread/public? meta)))))

    (testing "open-thread sets public? to true"
      (threads/open-thread (:datomic *system*) thread-id)
      (let [meta (threads/find-thread-meta (:datomic *system*) thread-id)]
        (is (true? (:thread/public? meta)))))))
