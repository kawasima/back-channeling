(ns back-channeling.resource.thread
  (:require [liberator.core :as liberator]
            [bouncer.validators :as v]
            [datomic.api :as d]

            (back-channeling [util :refer [parse-request]])
            [back-channeling.websocket.socketapp :refer [broadcast-message]]
            (back-channeling.boundary [threads :as threads]
                                      [users :as users])
            (back-channeling.resource [base :refer [base-resource has-permission?]])))

(defn threads-resource [{:keys [datomic socketapp]} board-name]
  (liberator/resource base-resource
   :allowed-methods [:get :post]
   :malformed? #(parse-request % {:thread/title [[v/required]
                                                 [v/max-count 255]]
                                  :comment/content [[v/required]
                                                    [v/max-count 4000]]})
   :allowed? #(case (get-in % [:request :request-method])
                :get  (has-permission? % #{:read-board})
                :post (has-permission? % #{:create-thread}))

   :handle-created (fn [ctx]
                     {:db/id (:db/id ctx)})

   :post! (fn [{th :edn req :request identity :identity}]
            (let [user (users/find-by-name datomic (:user/name identity))
                  [tempids thread-id] (threads/save datomic board-name th user)]
              (broadcast-message socketapp [:update-board {:board/name board-name}])
              {:db/id (d/resolve-tempid (d/db (:connection datomic)) tempids thread-id)}))

   :handle-ok (fn [{{{:keys [q]} :params} :request}]
                (when q
                  (threads/find-threads datomic board-name q)))))

(defn thread-resource [{:keys [datomic]} board-name thread-id]
  (liberator/resource base-resource
   :allowed-methods [:get :put]
   :malformed? #(parse-request %)

   :allowed? #(case (get-in % [:request :request-method])
                :get  (has-permission? % #{:read-thread})
                :put  (has-permission? % #{:add-watcher :remove-watcher}))

   :put! (fn [{{:keys [add-watcher remove-watcher]} :edn req :request}]
           (when-let [user (users/find-by-name datomic (get-in req [:identity :user/name]))]
             (when add-watcher
               (threads/add-watcher datomic thread-id user))
             (when remove-watcher
               (threads/remove-watcher datomic thread-id user))))
   :handle-created (fn [_]
                     {:status "ok"})
   :handle-ok (fn [_]
                (threads/find-thread datomic thread-id))))
