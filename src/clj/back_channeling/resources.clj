(ns back-channeling.resources
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            (back-channeling [model :as model]
                             [server :as server]))
  (:use [compojure.core :only [defroutes ANY]]
        [liberator.representation :only [ring-response]]
        [liberator.core :only [defresource]])
  (:import [java.util Date]))

(defn- body-as-string [ctx]
  (if-let [body (get-in ctx [:request :body])]
    (condp instance? body
      java.lang.String body
      (slurp (io/reader body)))))

(defn- parse-edn [context]
  (when (#{:put :post} (get-in context [:request :request-method]))
    (try
      (if-let [body (body-as-string context)]
        (let [data (edn/read-string body)]
          [false {:edn data}])
        false)
      (catch Exception e
        (log/error e "fail to parse edn.")
        {:message (format "IOException: %s" (.getMessage e))}))))


(defresource boards-resource []
  :available-media-types ["application/edn"]
  :allowed-methods [:get]
  :handle-ok (fn [_]
               (model/query '{:find [(pull ?board [:*]) ...]
                              :where [[?board :board/name]]})))

(defresource board-resource [board-name]
  :available-media-types ["application/edn"]
  :allowed-methods [:get]
  :handle-ok (fn [_]
               (let [board (model/query '{:find [(pull ?board [:*]) .]
                                          :in [$ ?b-name]
                                          :where [[?board :board/name ?b-name]]} board-name)]
                 (->> (model/query '{:find [?thread (count ?comment)]
                                     :in [$ ?board]
                                     :where [[?board :board/threads ?thread]
                                             [?thread :thread/comments ?comment]]}
                                   (:db/id board))
                      (map #(-> (model/pull '[:db/id :thread/title :thread/since] (first %))
                                (assoc :thread/resnum (second %))))
                      ((fn [threads] (assoc board :board/threads threads)))))))

(defresource threads-resource [board-name]
  :available-media-types ["application/edn"]
  :allowed-methods [:get :post]
  :malformed? #(parse-edn %)
  :post! (fn [{th :edn}]
           (let [user (or (model/query '{:find [?u .]
                                        :in [$ ?name ?email]
                                        :where [[?u :user/name ?name]
                                                [?u :user/email ?email]]}
                                       (:user/name th)
                                       (:user/email th))
                          (d/tempid :db.part/user))]
             (model/transact [[:db/add [:board/name (:board/name th)] :board/threads #db/id[:db.part/user -1]]
                              {:db/id #db/id[:db.part/user -1]
                               :thread/title (:thread/title th)
                               :thread/since (Date.)}
                              [:db/add #db/id[:db.part/user -1] :thread/comments #db/id[:db.part/user -2]]
                              {:db/id #db/id[:db.part/user -2]
                               :comment/posted-at (Date.)
                               :comment/posted-by user
                               :comment/content (:comment/content th)}
                              {:db/id user
                               :user/name (:user/name th)
                               :user/email (:user/email th)}])
             (server/broadcast-message "/ws" [:update-board {:board/name board-name}])))
  :handle-ok (fn [_]
               (model/query '{:find [[(pull ?thread
                                            [:*]) ...]]
                              :in [$ ?b-name]
                              :where [[?board :board/name ?b-name]
                                      [?thread :thread/title]]})))

(defresource thread-resource [thread-id]
  :available-media-types ["application/edn"]
  :allowed-methods [:get]
  :malformed? #(parse-edn %)
  :handle-ok (fn [_]
               (-> (model/pull '[:*
                                 {:thread/comments
                                  [:*
                                   {:comment/posted-by [:*]}]}] thread-id)
                   (update-in [:thread/comments] (partial map-indexed #(assoc %2  :comment/no (inc %1)))))))

(defresource comments-resource [thread-id]
  :available-media-types ["application/edn"]
  :allowed-methods [:get :post]
  :malformed? #(parse-edn %)
  :post! (fn [{comment :edn}]
           (let [user (or (model/query '{:find [?u .]
                                        :in [$ ?name ?email]
                                        :where [[?u :user/name ?name]
                                                [?u :user/email ?email]]}
                                       (:user/name comment)
                                       (:user/email comment))
                          (d/tempid :db.part/user))]
             (model/transact [[:db/add thread-id :thread/comments #db/id[:db.part/user -1]]
                              {:db/id #db/id[:db.part/user -1]
                               :comment/posted-at (Date.)
                               :comment/posted-by user
                               :comment/content (:comment/content comment)}
                              {:db/id user
                               :user/name (:user/name comment)
                               :user/email (:user/email comment)}])
             (server/broadcast-message "/ws" [:update-thread {:thread/id thread-id}])))
  :handle-ok (fn [_]))

(defroutes api-routes
  (ANY "/boards" [] boards-resource)
  (ANY "/board/:board-name" [board-name]
    (board-resource board-name))
  (ANY "/board/:board-name/threads" [board-name]
    (threads-resource board-name))
  (ANY "/thread/:thread-id" [thread-id]
    (thread-resource (Long/parseLong thread-id)))
  (ANY "/thread/:thread-id/comments" [thread-id]
    (comments-resource (Long/parseLong thread-id))))

