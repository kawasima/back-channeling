(ns back-channeling.boundary.comments-test
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [back-channeling.test-helper :as th]
            [back-channeling.boundary.threads :as threads]
            [back-channeling.boundary.comments :as comments]))

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

(defn- add-comment [thread-id content user-name]
  (let [now (java.util.Date.)
        comment-id (d/tempid :db.part/user)]
    (comments/save (:datomic *system*)
                   [{:db/id comment-id
                     :comment/posted-at now
                     :comment/posted-by [:user/name user-name]
                     :comment/content content
                     :comment/format :comment.format/plain
                     :comment/public? true}
                    [:db/add thread-id :thread/comments comment-id]
                    [:db/add thread-id :thread/last-updated now]])))

(deftest find-by-thread-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        thread-id (create-thread "default" "T1" "First" "alice")]

    (testing "returns comments for a thread"
      (let [result (comments/find-by-thread (:datomic *system*) thread-id)]
        (is (= 1 (count result)))
        (is (= "First" (:comment/content (first result))))
        (is (= "alice" (get-in (first result) [:comment/posted-by :user/name])))))

    (testing "returns multiple comments after adding more"
      (add-comment thread-id "Second" "alice")
      (let [result (comments/find-by-thread (:datomic *system*) thread-id)]
        (is (= 2 (count result)))))))

(deftest count-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        thread-id (create-thread "default" "T1" "First" "alice")]

    (testing "counts comments in thread"
      (is (= 1 (comments/count (:datomic *system*) thread-id))))

    (testing "count increases after adding comment"
      (add-comment thread-id "Second" "alice")
      (is (= 2 (comments/count (:datomic *system*) thread-id))))))

(deftest count-writenum-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-user *system* "bob" "bob@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        thread-id (create-thread "default" "T1" "By alice" "alice")]

    (testing "counts comments by specific user"
      (is (= 1 (comments/count-writenum (:datomic *system*) thread-id
                                        {:user/name "alice"})))
      (is (nil? (comments/count-writenum (:datomic *system*) thread-id
                                         {:user/name "bob"}))))

    (testing "bob's writenum increases after posting"
      (add-comment thread-id "By bob" "bob")
      (is (= 1 (comments/count-writenum (:datomic *system*) thread-id
                                        {:user/name "bob"}))))))

(deftest count-writenums-batch-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        t1 (create-thread "default" "T1" "C1" "alice")
        t2 (create-thread "default" "T2" "C2" "alice")]

    (testing "returns writenum for multiple threads at once"
      (let [result (comments/count-writenums-batch (:datomic *system*)
                                                   [t1 t2]
                                                   {:user/name "alice"})]
        (is (= 1 (get result t1)))
        (is (= 1 (get result t2)))))))

(deftest hide-comment-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        thread-id (create-thread "default" "T1" "Visible" "alice")]

    (testing "hide sets comment public? to false"
      (comments/hide (:datomic *system*) thread-id 1)
      (let [result (comments/find-by-thread (:datomic *system*) thread-id)
            comment (first result)]
        (is (false? (:comment/public? comment)))))))

(deftest add-reaction-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        _ (th/create-test-board *system* "default" "Default board")
        thread-id (create-thread "default" "T1" "React to me" "alice")
        ;; Find a reaction entity from the seeded data
        {:keys [connection]} *system*
        reaction-id (d/q '{:find [?r .]
                           :where [[?r :reaction/name "GJ"]]}
                         (d/db connection))]

    (testing "add-reaction attaches a reaction to a comment"
      (comments/add-reaction (:datomic *system*) reaction-id thread-id 1
                             [:user/name "alice"])
      (let [result (comments/find-by-thread (:datomic *system*) thread-id)
            comment (first result)]
        (is (seq (:comment/reactions comment)))
        (is (= "(・∀・)" (get-in (first (:comment/reactions comment))
                               [:comment-reaction/reaction :reaction/label])))))))
