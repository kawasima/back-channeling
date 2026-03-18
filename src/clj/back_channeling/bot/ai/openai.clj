(ns back-channeling.bot.ai.openai
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [back-channeling.bot.ai :as ai]))

(defrecord OpenAIProvider [api-key model base-url]
  ai/AIProvider
  (chat-completion [_ messages options]
    (let [body {:model model
                :max_tokens (get options :max-tokens 1024)
                :messages (mapv #(select-keys % [:role :content]) messages)}
          response (http/post (str base-url "/chat/completions")
                    {:headers {"Authorization" (str "Bearer " api-key)
                               "Content-Type" "application/json"}
                     :body (json/write-str body)
                     :as :json})]
      (-> response :body :choices first :message :content))))
