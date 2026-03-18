(ns back-channeling.mention-test
  (:require [clojure.test :refer :all]
            [back-channeling.mention :refer [extract-mentions]]))

(deftest extract-mentions-test
  (testing "single mention"
    (is (= #{"alice"} (extract-mentions "hello @alice"))))

  (testing "multiple mentions"
    (is (= #{"alice" "bob"} (extract-mentions "@alice please review with @bob"))))

  (testing "no mentions"
    (is (= #{} (extract-mentions "no mentions here"))))

  (testing "nil input"
    (is (= #{} (extract-mentions nil))))

  (testing "empty string"
    (is (= #{} (extract-mentions ""))))

  (testing "mention with underscores and hyphens"
    (is (= #{"my-bot" "test_user"} (extract-mentions "@my-bot and @test_user"))))

  (testing "too short username (< 3 chars) is ignored"
    (is (= #{} (extract-mentions "@ab too short"))))

  (testing "username longer than 20 chars matches up to 20"
    (is (= #{"aaaaabbbbbcccccddddd"} (extract-mentions "@aaaaabbbbbcccccddddde too long"))))

  (testing "mention at start of text"
    (is (= #{"bot"} (extract-mentions "@bot hello"))))

  (testing "mention at end of text"
    (is (= #{"bot"} (extract-mentions "hello @bot"))))

  (testing "duplicate mentions are deduped"
    (is (= #{"alice"} (extract-mentions "@alice said hi @alice")))))
