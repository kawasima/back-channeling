(ns back-channeling.signup-test
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [back-channeling.test-helper :as th]
            [back-channeling.signup :as signup]))

(def ^:dynamic *system* nil)

(defn system-fixture [f]
  (let [sys (th/test-system)]
    (binding [*system* sys]
      (try
        (f)
        (finally
          (th/teardown-system sys))))))

(use-fixtures :each system-fixture)

;; -- unique-email? / unique-name? -------------------------------------------

(deftest unique-email?-test
  (let [{:keys [connection]} *system*]
    (testing "returns true when email is not used"
      (is (true? (signup/unique-email? connection "new@test.com"))))

    (testing "returns false when email already exists"
      (th/create-test-user *system* "alice" "alice@test.com")
      (is (false? (signup/unique-email? connection "alice@test.com"))))))

(deftest unique-name?-test
  (let [{:keys [connection]} *system*]
    (testing "returns true when name is not used"
      (is (true? (signup/unique-name? connection "newuser"))))

    (testing "returns false when name already exists"
      (th/create-test-user *system* "bob" "bob@test.com")
      (is (false? (signup/unique-name? connection "bob"))))))

;; -- validate-user ----------------------------------------------------------

(deftest validate-user-test
  (testing "valid user with password passes validation"
    (let [[errors _] (signup/validate-user (:datomic *system*)
                       {:user/name "validuser"
                        :user/email "valid@test.com"
                        :password-credential/password "password123"})]
      (is (nil? errors))))

  (testing "valid user with token passes validation"
    (let [[errors _] (signup/validate-user (:datomic *system*)
                       {:user/name "tokenuser"
                        :user/email "token@test.com"
                        :token-credential/token "abcdef0123456789"})]
      (is (nil? errors))))

  (testing "missing password and token fails"
    (let [[errors _] (signup/validate-user (:datomic *system*)
                       {:user/name "noauth"
                        :user/email "noauth@test.com"})]
      (is (some? (:password-credential/password errors)))))

  (testing "short password fails"
    (let [[errors _] (signup/validate-user (:datomic *system*)
                       {:user/name "shortpw"
                        :user/email "short@test.com"
                        :password-credential/password "1234567"})]
      (is (some? (:password-credential/password errors)))))

  (testing "invalid token format fails"
    (let [[errors _] (signup/validate-user (:datomic *system*)
                       {:user/name "badtoken"
                        :user/email "bad@test.com"
                        :token-credential/token "INVALID!"})]
      (is (some? (:token-credential/token errors)))))

  (testing "missing email fails"
    (let [[errors _] (signup/validate-user (:datomic *system*)
                       {:user/name "noemail"
                        :password-credential/password "password123"})]
      (is (some? (:user/email errors)))))

  (testing "invalid email fails"
    (let [[errors _] (signup/validate-user (:datomic *system*)
                       {:user/name "bademail"
                        :user/email "not-an-email"
                        :password-credential/password "password123"})]
      (is (some? (:user/email errors)))))

  (testing "too long email fails"
    (let [long-email (str (apply str (repeat 95 "a")) "@b.com")
          [errors _] (signup/validate-user (:datomic *system*)
                       {:user/name "longemail"
                        :user/email long-email
                        :password-credential/password "password123"})]
      (is (some? (:user/email errors)))))

  (testing "missing username fails"
    (let [[errors _] (signup/validate-user (:datomic *system*)
                       {:user/email "noname@test.com"
                        :password-credential/password "password123"})]
      (is (some? (:user/name errors)))))

  (testing "short username fails"
    (let [[errors _] (signup/validate-user (:datomic *system*)
                       {:user/name "ab"
                        :user/email "short@test.com"
                        :password-credential/password "password123"})]
      (is (some? (:user/name errors)))))

  (testing "long username fails"
    (let [[errors _] (signup/validate-user (:datomic *system*)
                       {:user/name "aaaaabbbbbcccccdddddee"
                        :user/email "long@test.com"
                        :password-credential/password "password123"})]
      (is (some? (:user/name errors)))))

  (testing "duplicate email fails"
    (th/create-test-user *system* "existing" "existing@test.com")
    (let [[errors _] (signup/validate-user (:datomic *system*)
                       {:user/name "newuser"
                        :user/email "existing@test.com"
                        :password-credential/password "password123"})]
      (is (some? (:user/email errors)))))

  (testing "duplicate username fails"
    (th/create-test-user *system* "taken" "taken@test.com")
    (let [[errors _] (signup/validate-user (:datomic *system*)
                       {:user/name "taken"
                        :user/email "unique@test.com"
                        :password-credential/password "password123"})]
      (is (some? (:user/name errors))))))
