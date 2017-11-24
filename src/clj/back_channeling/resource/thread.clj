(ns back-channeling.resource.thread
  (:require [liberator.core :as liberator]
            [bouncer.validators :as v]
            [datomic.api :as d]

            (back-channeling [util :refer [parse-request]])
            [back-channeling.websocket.socketapp :refer [broadcast-message]]
            (back-channeling.boundary [threads :as threads]
                                      [users :as users])
            (back-channeling.resource [base :refer [base-resource has-permission? thread-allowed?]])))

(defn threads-resource [{:keys [datomic socketapp]} board-name]
  (liberator/resource base-resource
   :allowed-methods [:get :post]
   :malformed? #(parse-request % {:thread/title [[v/required]
                                                 [v/max-count 255]]
                                  :comment/content [[v/required]
                                                    [v/max-count 4000]]})
   :allowed? #(case (get-in % [:request :request-method])
                :get  (has-permission? % #{:read-board})
                :post (has-permission? % #{:write-thread :write-any-thread}))

   :handle-created (fn [ctx]
                     {:db/id (:db/id ctx)})

   :post! (fn [{th :edn identity :identity}]
            (let [user (users/find-by-name datomic (:user/name identity))
                  [tempids temp-thread-id] (threads/save datomic board-name th user)
                  thread-id (d/resolve-tempid (d/db (:connection datomic)) tempids temp-thread-id)]
              (threads/add-watcher datomic thread-id identity)
              (broadcast-message socketapp [:update-board {:board/name board-name}])
              {:db/id thread-id}))

   :handle-ok (fn [{{{:keys [q]} :params} :request :as ctx}]
                (when q
                  (->> (threads/find-threads datomic board-name q)
                       (filter #(thread-allowed? ctx datomic #{:read-any-thread} (:db/id %))))))))

(defn thread-resource [{:keys [datomic]} board-name thread-id]
  (liberator/resource base-resource
   :allowed-methods [:put]
   :malformed? #(parse-request %)

   :allowed? #(case (get-in % [:request :request-method])
                :get  (has-permission? % #{:read-thread :read-any-thread})
                :put  (has-permission? % #{:read-thread :read-any-thread}))

   :put! (fn [{{:keys [add-watcher remove-watcher open-thread close-thread]} :edn identity :identity :as ctx}]
           (when (thread-allowed? ctx datomic #{:read-any-thread} thread-id)
             (when add-watcher
               (threads/add-watcher datomic thread-id identity))
             (when remove-watcher
               (threads/remove-watcher datomic thread-id identity))
             (when open-thread
               (threads/open-thread datomic thread-id))
             (when close-thread
               (threads/close-thread datomic thread-id))))

   :handle-created (fn [_]
                     {:status "ok"})
   :handle-ok (fn [_]
                (threads/find-thread datomic thread-id))))
