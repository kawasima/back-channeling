(ns back-channeling.bot.ai.claude
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [back-channeling.bot.ai :as ai]))

(defrecord ClaudeProvider [api-key model]
  ai/AIProvider
  (chat-completion [_ messages options]
    (let [system-msg (->> messages (filter #(= "system" (:role %))) first :content)
          chat-msgs (->> messages (remove #(= "system" (:role %))))
          body (cond-> {:model model
                        :max_tokens (get options :max-tokens 1024)
                        :messages (mapv #(select-keys % [:role :content]) chat-msgs)}
                 system-msg (assoc :system system-msg))
          response (http/post "https://api.anthropic.com/v1/messages"
                    {:headers {"x-api-key" api-key
                               "anthropic-version" "2023-06-01"
                               "content-type" "application/json"}
                     :body (json/write-str body)
                     :as :json})]
      (-> response :body :content first :text))))
