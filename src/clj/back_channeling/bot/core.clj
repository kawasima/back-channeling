(ns back-channeling.bot.core
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [back-channeling.bot.ai :as ai]
            [back-channeling.bot.ai.claude]
            [back-channeling.bot.ai.openai]
            [back-channeling.bot.api :as bot-api]
            [back-channeling.bot.ws :as bot-ws]
            [back-channeling.mention :as mention])
  (:import [java.util.concurrent Executors TimeUnit ScheduledExecutorService]))

;; -- State -----------------------------------------------------------------

(defonce state (atom {:access-token nil
                      :ws-connection nil
                      :monitored-threads #{}}))

(defn- get-token [] (:access-token @state))

;; -- Message building ------------------------------------------------------

(def ^:private max-context-messages 50)

(defn build-messages
  [comments bot-name system-prompt]
  (into [{:role "system" :content system-prompt}]
        (->> comments
             (keep (fn [comment]
                     (when (:comment/public? comment)
                       (let [author (get-in comment [:comment/posted-by :user/name])
                             role (if (= author bot-name) "assistant" "user")
                             content (if (= role "user")
                                       (str author ": " (:comment/content comment))
                                       (:comment/content comment))]
                         {:role role :content content}))))
             (take-last max-context-messages))))

;; -- Response logic --------------------------------------------------------

(defn should-respond?
  [notify-data bot-name monitored-threads]
  (let [content (:comment/content notify-data)
        thread-id (:thread/id notify-data)
        poster (get-in notify-data [:comment/posted-by :user/name])
        mentions (mention/extract-mentions (or content ""))]
    (and (not= poster bot-name)
         (or (contains? mentions bot-name)
             (contains? monitored-threads thread-id)))))

(defn- handle-notify!
  [config ai-provider notify-data]
  (let [{:keys [bot-name system-prompt]} config
        thread-id (:thread/id notify-data)
        board-name (:board/name notify-data)
        access-token (get-token)
        monitored-threads (:monitored-threads @state)]
    (when (should-respond? notify-data bot-name monitored-threads)
      (swap! state update :monitored-threads conj thread-id)
      (try
        (let [poster (get-in notify-data [:comment/posted-by :user/name])
              thread (bot-api/fetch-thread config access-token thread-id)
              messages (build-messages (:thread/comments thread) bot-name system-prompt)
              response (ai/chat-completion ai-provider messages {:max-tokens 1024})
              reply (str "@" poster " " response)]
          (bot-api/post-comment! config access-token board-name thread-id reply))
        (catch Exception e
          (.println System/err (str "Error handling notification: " (.getMessage e)))
          (.printStackTrace e System/err))))))

;; -- WebSocket message dispatch --------------------------------------------

(defn- on-ws-message [config ai-provider ^ScheduledExecutorService executor raw-message]
  (try
    (let [[cmd data] (edn/read-string raw-message)]
      (case cmd
        :notify (.submit executor ^Runnable (fn [] (handle-notify! config ai-provider data)))
        nil))
    (catch Exception e
      (.println System/err (str "Error parsing WebSocket message: " (.getMessage e))))))

;; -- Connection management -------------------------------------------------

(declare connect-ws!)

(defn- connect-ws!
  [config ai-provider ^ScheduledExecutorService executor]
  (let [access-token (get-token)
        ws-url (str (.replace ^String (:server-url config) "http" "ws")
                    "/ws?token=" access-token)]
    (try
      (let [ws (bot-ws/connect! ws-url
                 {:on-message (fn [msg] (on-ws-message config ai-provider executor msg))
                  :on-close (fn [status-code reason]
                              (.println System/err (str "WebSocket closed: " status-code " " reason))
                              (.schedule executor
                                ^Runnable (fn []
                                            (try
                                              (let [new-token (bot-api/authenticate! config)]
                                                (swap! state assoc :access-token new-token))
                                              (catch Exception e
                                                (.println System/err (str "Re-authentication failed: " (.getMessage e)))))
                                            (connect-ws! config ai-provider executor))
                                (long 5) TimeUnit/SECONDS))
                  :on-error (fn [error]
                              (.println System/err (str "WebSocket error: " (.getMessage error))))})]
        (swap! state assoc :ws-connection ws))
      (catch Exception e
        (.println System/err (str "WebSocket connection failed: " (.getMessage e)))
        ;; Retry after delay
        (.schedule executor
          ^Runnable (fn [] (connect-ws! config ai-provider executor))
          (long 10) TimeUnit/SECONDS)))))

(defn- start-token-refresh!
  [config ^ScheduledExecutorService scheduler]
  (.scheduleAtFixedRate scheduler
    ^Runnable (fn []
                (try
                  (let [new-token (bot-api/authenticate! config)]
                    (swap! state assoc :access-token new-token)
                    (.println System/err "Token refreshed"))
                  (catch Exception e
                    (.println System/err (str "Token refresh failed: " (.getMessage e))))))
    (long 20) (long 20) TimeUnit/MINUTES))

;; -- Configuration ---------------------------------------------------------

(defn load-config []
  (let [path (or (System/getenv "BOT_CONFIG_PATH")
                 "back_channeling/bot_config.edn")
        source (or (io/resource path) path)]
    (try
      (edn/read-string (slurp source))
      (catch Exception e
        (throw (ex-info (str "Failed to load bot config from: " path)
                        {:path path} e))))))

(defn create-ai-provider [{:keys [ai-provider]}]
  (case (:type ai-provider)
    :claude (back-channeling.bot.ai.claude/->ClaudeProvider
              (:api-key ai-provider)
              (or (:model ai-provider) "claude-sonnet-4-20250514"))
    :openai (back-channeling.bot.ai.openai/->OpenAIProvider
              (:api-key ai-provider)
              (or (:model ai-provider) "gpt-4o")
              (or (:base-url ai-provider) "https://api.openai.com/v1"))))

;; -- Entry point -----------------------------------------------------------

(defn -main [& _args]
  (let [config (load-config)
        ai-provider (create-ai-provider config)
        executor (Executors/newScheduledThreadPool 2)]
    (.println System/err (str "Starting bot as " (:bot-name config)))
    (try
      (let [token (bot-api/authenticate! config)]
        (swap! state assoc :access-token token)
        (.println System/err "Authenticated, token obtained"))
      (catch Exception e
        (.println System/err (str "Initial authentication failed: " (.getMessage e)))
        (System/exit 1)))
    (start-token-refresh! config executor)
    (connect-ws! config ai-provider executor)
    (.println System/err "Bot connected and listening")
    ;; Shutdown hook to clean up executor
    (.addShutdownHook (Runtime/getRuntime)
      (Thread. ^Runnable (fn []
                           (.println System/err "Shutting down bot...")
                           (.shutdown executor)
                           (when-not (.awaitTermination executor 10 TimeUnit/SECONDS)
                             (.shutdownNow executor)))))
    @(promise)))
