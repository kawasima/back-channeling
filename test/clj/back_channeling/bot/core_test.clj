(ns back-channeling.bot.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.cache :as cache]
            [back-channeling.bot.core :refer [build-messages should-respond?]]))

(deftest build-messages-test
  (testing "builds messages with system prompt"
    (let [comments [{:comment/public? true
                     :comment/posted-by {:user/name "alice"}
                     :comment/content "hello"}
                    {:comment/public? true
                     :comment/posted-by {:user/name "mybot"}
                     :comment/content "hi there"}]
          result (build-messages comments "mybot" "You are a helpful bot.")]
      (is (= 3 (count result)))
      (is (= "system" (:role (first result))))
      (is (= "You are a helpful bot." (:content (first result))))
      (is (= "user" (:role (second result))))
      (is (= "alice: hello" (:content (second result))))
      (is (= "assistant" (:role (nth result 2))))
      (is (= "hi there" (:content (nth result 2))))))

  (testing "skips non-public comments"
    (let [comments [{:comment/public? true
                     :comment/posted-by {:user/name "alice"}
                     :comment/content "visible"}
                    {:comment/public? false
                     :comment/posted-by {:user/name "alice"}
                     :comment/content "deleted"}]
          result (build-messages comments "mybot" "system")]
      (is (= 2 (count result)))))

  (testing "limits context to max-context-messages"
    (let [comments (for [i (range 100)]
                     {:comment/public? true
                      :comment/posted-by {:user/name "alice"}
                      :comment/content (str "msg-" i)})
          result (build-messages comments "mybot" "system")]
      ;; 1 system + max-context-messages (50)
      (is (= 51 (count result))))))

(deftest should-respond?-test
  (testing "responds to @mention"
    (is (should-respond?
         {:comment/content "hey @mybot help"
          :thread/id 1
          :comment/posted-by {:user/name "alice"}}
         "mybot" (cache/lru-cache-factory {}))))

  (testing "responds to monitored thread"
    (is (should-respond?
         {:comment/content "no mention"
          :thread/id 1
          :comment/posted-by {:user/name "alice"}}
         "mybot" (cache/lru-cache-factory {1 true}))))

  (testing "does not respond to own messages"
    (is (not (should-respond?
              {:comment/content "@mybot"
               :thread/id 1
               :comment/posted-by {:user/name "mybot"}}
              "mybot" (cache/lru-cache-factory {})))))

  (testing "does not respond without mention or monitored thread"
    (is (not (should-respond?
              {:comment/content "hello everyone"
               :thread/id 99
               :comment/posted-by {:user/name "alice"}}
              "mybot" (cache/lru-cache-factory {1 true}))))))
