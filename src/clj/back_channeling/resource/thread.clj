(ns back-channeling.resource.thread
  (:require [liberator.core :as liberator]
            [datomic.api :as d]

            (back-channeling [util :refer [parse-request]])
            [back-channeling.websocket.socketapp :refer [board-multicast-message]]
            (back-channeling.boundary [threads :as threads]
                                      [users :as users])
            (back-channeling.resource [base :refer [base-resource has-permission? thread-allowed?
                                                      perm-read-thread perm-write-thread]])))

(defn threads-resource [{:keys [datomic socketapp]} board-name]
  (liberator/resource base-resource
   :allowed-methods [:get :post]
   :malformed? #(parse-request % [:map
                                  [:thread/title [:string {:min 1 :max 255}]]
                                  [:comment/content [:string {:min 1 :max 4000}]]])
   :allowed? #(case (get-in % [:request :request-method])
                :get  (has-permission? % perm-read-thread)
                :post (has-permission? % perm-write-thread))

   :handle-created (fn [ctx]
                     {:db/id (:db/id ctx)})

   :post! (fn [{th :edn identity :identity}]
            (let [user (users/find-by-name datomic (:user/name identity))
                  [tempids temp-thread-id] (threads/save datomic board-name th user)
                  thread-id (d/resolve-tempid (d/db (:connection datomic)) tempids temp-thread-id)]
              (threads/add-watcher datomic thread-id identity)
              (board-multicast-message socketapp [:update-board {:board/name board-name}] board-name)
              {:db/id thread-id}))

   :handle-ok (fn [{{{:keys [q]} :params} :request :as ctx}]
                (when q
                  (->> (threads/find-threads datomic board-name q)
                       (filter #(thread-allowed? ctx datomic #{:read-any-thread} (:db/id %))))))))

(defn thread-resource [{:keys [datomic socketapp]} board-name thread-id]
  (liberator/resource base-resource
   :allowed-methods [:get :put]
   :malformed? #(parse-request %)

   :allowed? #(case (get-in % [:request :request-method])
                :get  (has-permission? % perm-read-thread)
                :put  (has-permission? % perm-read-thread))

   :put! (fn [{{:keys [add-watcher remove-watcher open-thread close-thread]} :edn identity :identity :as ctx}]
           (when (thread-allowed? ctx datomic #{:read-any-thread} thread-id)
             (when add-watcher
               (threads/add-watcher datomic thread-id identity))
             (when remove-watcher
               (threads/remove-watcher datomic thread-id identity))
             (when (has-permission? ctx #{:read-any-thread})
               (when open-thread
                 (threads/open-thread datomic thread-id)
                 (board-multicast-message socketapp [:update-board {:board/name board-name}] board-name))
               (when close-thread
                 (threads/close-thread datomic thread-id)
                 (board-multicast-message socketapp [:update-board {:board/name board-name}] board-name)))))

   :handle-created (fn [_]
                     {:status "ok"})
   :handle-ok (fn [_]
                (threads/find-thread-meta datomic thread-id))))

;; Readonly resource returns full thread with comments (used by bot, curation)
(defn thread-readonly-resource [{:keys [datomic]} thread-id]
  (liberator/resource base-resource
   :allowed-methods [:get]
   :allowed? #(has-permission? % perm-read-thread)
   :handle-ok (fn [_]
                (threads/find-thread datomic thread-id))))
