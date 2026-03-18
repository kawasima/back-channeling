(ns back-channeling.bot.api
  "HTTP client for Back-Channeling server API."
  (:require [clj-http.client :as http]))

(defn authenticate!
  "Exchange a bot-token for an access-token. Returns the access-token string."
  [{:keys [server-url bot-token]}]
  (let [resp (http/post (str server-url "/api/token")
               {:query-params {"code" bot-token}
                :headers {"Accept" "application/edn"
                          "Content-Type" "application/edn"}
                :as :clojure
                :throw-exceptions false})]
    (if (<= 200 (:status resp) 299)
      (:access-token (:body resp))
      (throw (ex-info "Authentication failed"
                      {:status (:status resp) :body (:body resp)})))))

(defn fetch-thread
  "Fetch a thread by id. Returns the thread map."
  [{:keys [server-url]} access-token thread-id]
  (let [resp (http/get (str server-url "/api/thread/" thread-id)
               {:headers {"Accept" "application/edn"
                          "Authorization" (str "Token " access-token)}
                :as :clojure
                :throw-exceptions false})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (throw (ex-info "Failed to fetch thread"
                      {:status (:status resp) :thread-id thread-id})))))

(defn post-comment!
  "Post a comment to a thread."
  [{:keys [server-url]} access-token board-name thread-id content]
  (let [resp (http/post (str server-url "/api/board/" board-name "/thread/" thread-id "/comments")
               {:headers {"Accept" "application/edn"
                          "Authorization" (str "Token " access-token)
                          "Content-Type" "application/edn"}
                :body (pr-str {:comment/content content
                               :comment/format :comment.format/markdown})
                :throw-exceptions false})]
    (when-not (<= 200 (:status resp) 299)
      (throw (ex-info "Failed to post comment"
                      {:status (:status resp) :thread-id thread-id})))))
