(ns back-channeling.boundary.users-test
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [back-channeling.test-helper :as th]
            [back-channeling.boundary.users :as users]))

(def ^:dynamic *system* nil)

(defn system-fixture [f]
  (let [sys (th/test-system)]
    (binding [*system* sys]
      (try
        (f)
        (finally
          (th/teardown-system sys))))))

(use-fixtures :each system-fixture)

(deftest find-by-name-test
  (testing "returns nil for non-existent user"
    (is (nil? (users/find-by-name (:datomic *system*) "ghost"))))

  (testing "returns user when found"
    (th/create-test-user *system* "alice" "alice@test.com")
    (let [user (users/find-by-name (:datomic *system*) "alice")]
      (is (some? user))
      (is (= "alice" (:user/name user)))
      (is (= "alice@test.com" (:user/email user))))))

(deftest find-by-token-test
  (let [_ (th/create-test-user *system* "alice" "alice@test.com")
        {:keys [connection]} *system*
        user-ref [:user/name "alice"]]

    (testing "returns nil for non-existent token"
      (is (nil? (users/find-by-token (:datomic *system*) "nonexistent"))))

    (testing "returns user for valid token"
      @(d/transact connection
         [{:db/id (d/tempid :db.part/user)
           :token-credential/user user-ref
           :token-credential/token "abc123"}])
      (let [user (users/find-by-token (:datomic *system*) "abc123")]
        (is (some? user))
        (is (= "alice" (:user/name user)))))))
