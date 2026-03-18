(ns back-channeling.resource.board
  (:require [liberator.core :as liberator]
            (back-channeling [util :refer [parse-request]])
            (back-channeling.boundary [boards :as boards]
                                      [threads :as threads]
                                      [comments :as comments])
            (back-channeling.resource [base :refer [base-resource has-permission?]])))

(def board-ng-names
  #{"default"})

(defn boards-resource [{:keys [datomic]}]
  (liberator/resource base-resource
   :allowed-methods [:get :post]
   :malformed? #(parse-request % [:map
                                  [:board/name [:and
                                                [:string {:min 1 :max 255}]
                                                [:re #"^[A-Za-z0-9_\-]+$"]
                                                [:fn {:error/message "Board name is reserved"}
                                                 (fn [v] (not (contains? board-ng-names v)))]]]])

   :allowed? #(case (get-in % [:request :request-method])
                :get  true
                :post (has-permission? % #{:create-board}))

   :post! (fn [{board :edn req :request}]
            {:db/id (boards/save datomic board)})

   :handle-created (fn [ctx]
                     {:db/id (:db/id ctx)})

   :handle-ok (fn [{identity :identity}]
                (boards/find-all datomic identity))))

(defn board-resource [{:keys [datomic]} board-name]
  (liberator/resource base-resource
   :allowed-methods [:get :put]
   :malformed? #(parse-request %)
   :allowed? #(case (get-in % [:request :request-method])
                :get (has-permission? % #{:read-board})
                :put (has-permission? % #{:modify-board}))
   :exists? (fn [ctx]
              (if-let [board (boards/find-by-name datomic board-name)]
                {:board board}
                false))

   :put! (fn [{old :board board :edn}]
           (boards/save datomic (merge old board) (:db/id old)))

   :handle-ok (fn [{board :board identity :identity}]
                (let [threads (boards/find-threads datomic (:db/id board) identity)
                      thread-ids (mapv :db/id threads)
                      writenums (if (seq thread-ids)
                                  (comments/count-writenums-batch datomic thread-ids identity)
                                  {})]
                  (-> board
                      (assoc :board/threads
                             (->> threads
                                  (mapv #(assoc % :thread/writenum (get writenums (:db/id %) 0)))
                                  (mapv #(update % :thread/watchers (fn [w] (apply hash-set w))))))
                      (assoc :user/permissions (:user/permissions identity)))))))
