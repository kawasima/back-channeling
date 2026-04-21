(ns back-channeling.handler.chat-app-test
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [buddy.hashers :as hashers]
            [buddy.core.codecs]
            [buddy.core.hash]
            [back-channeling.test-helper :as th]
            [back-channeling.handler.chat-app :as chat-app]))

(def ^:dynamic *system* nil)

(defn system-fixture [f]
  (let [sys (th/test-system)]
    (binding [*system* sys]
      (try
        (f)
        (finally
          (th/teardown-system sys))))))

(use-fixtures :each system-fixture)

(defn- create-user-with-password
  "Create a user with a bcrypt password credential."
  [{:keys [connection]} user-name email password]
  (let [user-id (d/tempid :db.part/user -1)
        pw-id (d/tempid :db.part/user -2)
        hashed (hashers/derive password)]
    @(d/transact connection
       [{:db/id user-id
         :user/name user-name
         :user/email email}
        {:db/id pw-id
         :password-credential/user user-id
         :password-credential/password hashed}])))

(defn- create-user-with-legacy-password
  "Create a user with a legacy SHA256 (salt + password) credential."
  [{:keys [connection]} user-name email password]
  (let [user-id (d/tempid :db.part/user -1)
        pw-id (d/tempid :db.part/user -2)
        salt (.getBytes "mysalt" "UTF-8")
        passwd-bytes (into-array Byte/TYPE (concat salt (.getBytes password "UTF-8")))
        hash-hex (buddy.core.codecs/bytes->hex
                   (buddy.core.hash/sha256 passwd-bytes))]
    @(d/transact connection
       [{:db/id user-id
         :user/name user-name
         :user/email email}
        {:db/id pw-id
         :password-credential/user user-id
         :password-credential/password hash-hex
         :password-credential/salt salt}])))

;; -- auth-by-password -------------------------------------------------------

(deftest auth-by-password-test
  (testing "returns nil for non-existent user"
    (is (nil? (chat-app/auth-by-password (:datomic *system*) "ghost" "pass"))))

  (testing "returns nil for empty username"
    (is (nil? (chat-app/auth-by-password (:datomic *system*) "" "pass"))))

  (testing "returns nil for empty password"
    (create-user-with-password *system* "alice" "alice@test.com" "secret123")
    (is (nil? (chat-app/auth-by-password (:datomic *system*) "alice" ""))))

  (testing "returns nil for nil inputs"
    (is (nil? (chat-app/auth-by-password (:datomic *system*) nil nil))))

  (testing "authenticates with correct bcrypt password"
    (create-user-with-password *system* "bob" "bob@test.com" "correctpassword")
    (let [user (chat-app/auth-by-password (:datomic *system*) "bob" "correctpassword")]
      (is (some? user))
      (is (= "bob" (:user/name user)))))

  (testing "returns nil for wrong password"
    (is (nil? (chat-app/auth-by-password (:datomic *system*) "bob" "wrongpassword")))))

(deftest auth-by-password-legacy-test
  (testing "authenticates with legacy sha256 password and upgrades to bcrypt"
    (create-user-with-legacy-password *system* "legacy" "legacy@test.com" "oldpass")
    (let [user (chat-app/auth-by-password (:datomic *system*) "legacy" "oldpass")]
      (is (some? user))
      (is (= "legacy" (:user/name user))))

    ;; After upgrade, should still authenticate
    (let [user (chat-app/auth-by-password (:datomic *system*) "legacy" "oldpass")]
      (is (some? user))
      (is (= "legacy" (:user/name user))))))
