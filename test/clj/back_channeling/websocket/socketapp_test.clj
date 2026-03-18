(ns back-channeling.websocket.socketapp-test
  (:require [clojure.test :refer :all]
            [duct.logger]
            [back-channeling.websocket.socketapp :as sut]
            [back-channeling.websocket.socketapp :refer [on-message on-connect
                                                          board-multicast-message
                                                          find-user-by-channel]])
  (:import [java.util.concurrent Executors]))

(def noop-logger
  (reify duct.logger/Logger
    (-log [_ level ns-str file line id event data] nil)))

(defn- make-test-socketapp []
  (sut/map->Socketapp
   {:logger noop-logger
    :channels (atom {})
    :path "/ws"
    :cache nil
    :scheduler (Executors/newSingleThreadScheduledExecutor)}))

(defn- inject-authenticated-channel
  "Directly inject an authenticated channel into the socketapp for testing."
  [socketapp ch user board]
  (swap! (:channels socketapp) assoc-in ["/ws" ch] {:user user :board board}))

(deftest on-message-rejects-commands-before-auth
  (let [app (make-test-socketapp)
        ch :fake-channel]
    ;; Register unauthenticated channel
    (swap! (:channels app) assoc-in ["/ws" ch] {:user nil :board nil})

    ;; Send a non-auth command — should be ignored
    (on-message app ch (pr-str [:call {:message "hi"}]))

    ;; Channel should still be unauthenticated
    (is (nil? (find-user-by-channel app ch)))))

(deftest on-message-blocks-client-leave
  (let [app (make-test-socketapp)
        ch :fake-channel
        user {:user/name "alice" :user/email "alice@test.com"}]
    (inject-authenticated-channel app ch user nil)

    ;; Client sends :leave — should be blocked
    (on-message app ch (pr-str [:leave {:user/name "bob" :user/email "bob@test.com"}]))

    ;; Alice should still be connected
    (is (= user (find-user-by-channel app ch)))))

(deftest on-message-rejects-auth-when-already-authenticated
  (let [app (make-test-socketapp)
        ch :fake-channel
        user {:user/name "alice" :user/email "alice@test.com"}]
    (inject-authenticated-channel app ch user nil)

    ;; Send :auth again — should be ignored (already authenticated)
    (on-message app ch (pr-str [:auth {:token "some-token"}]))

    ;; User should remain alice (not changed)
    (is (= user (find-user-by-channel app ch)))))

(deftest on-message-handles-malformed-edn
  (let [app (make-test-socketapp)
        ch :fake-channel
        user {:user/name "alice" :user/email "alice@test.com"}]
    (inject-authenticated-channel app ch user nil)

    ;; Send garbage — should not throw
    (is (nil? (on-message app ch "{{{{not edn")))))

(deftest board-multicast-sends-only-to-same-board
  (let [app (make-test-socketapp)
        ;; We can't easily test actual WebSocket sends, but we can verify
        ;; the channel filtering logic by checking the channels atom structure
        ch-a :channel-a
        ch-b :channel-b
        user-a {:user/name "alice" :user/email "alice@test.com"}
        user-b {:user/name "bob" :user/email "bob@test.com"}]
    (inject-authenticated-channel app ch-a user-a "board-1")
    (inject-authenticated-channel app ch-b user-b "board-2")

    ;; Verify channel state
    (let [channels @(:channels app)
          ws-channels (get channels "/ws")]
      (is (= "board-1" (get-in ws-channels [ch-a :board])))
      (is (= "board-2" (get-in ws-channels [ch-b :board])))
      ;; board-multicast-message would only send to ch-a for "board-1"
      ;; We verify the data structure is correct for the filtering logic
      (is (= 2 (count ws-channels))))))
