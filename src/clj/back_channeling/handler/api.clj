(ns back-channeling.handler.api
  (:require [integrant.core :as ig]
            [compojure.core :refer :all]
            [compojure.coercions :refer :all]
            [clojure.data.json :as json]
            [clj-time.format :as time-fmt]
            [clj-time.coerce :refer [from-date to-date]]
            (back-channeling.resource [board  :refer [boards-resource board-resource]]
                                      [thread :refer [threads-resource thread-resource]]
                                      [comment :refer [comments-resource comment-resource]]
                                      [voice   :refer [voices-resource]]
                                      [article :refer [articles-resource article-resource]]
                                      [user    :refer [users-resource user-resource]]
                                      [token   :refer [token-resource]]
                                      [reaction :refer [reactions-resource]])))

(def iso8601-formatter (time-fmt/formatters :basic-date-time))

(extend-type java.util.Date
  json/JSONWriter
  (-write [date out]
    (json/-write (time-fmt/unparse iso8601-formatter (from-date date)) out)))

(extend-type java.util.UUID
  json/JSONWriter
  (-write [uuid out]
    (json/-write (.toString uuid) out)))

(defmethod ig/init-key :back-channeling.handler/api [_ {:keys [prefix] :as options}]
  (context "/api" []
    (ANY "/token" []  (token-resource options))
    (ANY "/boards" [] (boards-resource options))
    (context "/board/:board-name" [board-name]
      (ANY "/" []
        (board-resource options board-name))
      (ANY "/threads" []
        (threads-resource options board-name))
      (ANY "/thread/:thread-id" [thread-id :<< as-int]
        (thread-resource options board-name thread-id))
      (ANY "/thread/:thread-id/comments" [thread-id :<< as-int]
        (comments-resource options board-name thread-id 1 nil))
      (ANY ["/thread/:thread-id/comments/:comment-range" :comment-range #"\d+\-(\d+)?"]
          [thread-id :<< as-int comment-range]
        (let [[_ from to] (re-matches #"(\d+)\-(\d+)?" comment-range)]
          (comments-resource options
                             board-name
                             thread-id
                             (when from (Long/parseLong from))
                             (when to (Long/parseLong to)))))
      (ANY "/thread/:thread-id/voices" [thread-id :<< as-int]
        (voices-resource options board-name thread-id))
      (ANY "/thread/:thread-id/comment/:comment-no"
        [thread-id :<< as-int comment-no :<< as-int]
        (comment-resource options board-name thread-id comment-no))
      ;; get board realm permissions
      (ANY "/user/:user-name" [user-name]
        (user-resource options user-name)))

    (ANY "/articles" []
      (articles-resource options))
    (ANY "/article/:article-id" [article-id :<< as-int]
      (article-resource options article-id))
    (ANY "/users" [] (users-resource options))
    (ANY "/user/:user-name" [user-name]
      (user-resource options user-name))
    (ANY "/reactions" [] (reactions-resource options))))
