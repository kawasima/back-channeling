(ns back-channeling.resource.base-test
  (:require [clojure.test :refer :all]
            [back-channeling.resource.base :refer [has-permission?
                                                    perm-read-thread
                                                    perm-write-thread
                                                    perm-delete-comment]]))

(defn- ctx-with-permissions [permissions]
  {:request {:identity {:user/permissions permissions}}})

(defn- ctx-without-permissions []
  {:request {:identity {:user/name "testuser"}}})

(deftest has-permission?-test
  (testing "returns true when permission system is not active (nil permissions)"
    (is (true? (has-permission? (ctx-without-permissions) perm-read-thread))))

  (testing "returns true when user has matching permission"
    (is (true? (has-permission? (ctx-with-permissions #{:read-thread}) perm-read-thread))))

  (testing "returns true when user has one of the alternative permissions"
    (is (true? (has-permission? (ctx-with-permissions #{:read-any-thread}) perm-read-thread))))

  (testing "returns false when user has no matching permission"
    (is (false? (has-permission? (ctx-with-permissions #{:create-board}) perm-read-thread))))

  (testing "returns false when user has empty permission set"
    (is (false? (has-permission? (ctx-with-permissions #{}) perm-read-thread)))))

(deftest permission-constant-test
  (testing "perm-read-thread contains expected permissions"
    (is (= #{:read-thread :read-any-thread} perm-read-thread)))

  (testing "perm-write-thread contains expected permissions"
    (is (= #{:write-thread :write-any-thread} perm-write-thread)))

  (testing "perm-delete-comment contains expected permissions"
    (is (= #{:delete-comment :delete-any-comment} perm-delete-comment))))
