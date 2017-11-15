(ns back-channeling.resource.board
  (:require [liberator.core :as liberator]
            [bouncer.validators :as v]
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
   :malformed? #(parse-request % {:board/name [[v/required]
                                               [v/max-count 255]
                                               [v/matches #"^[A-Za-z0-9_\-]+$"]
                                               [v/every (fn [v]
                                                          (not (contains? board-ng-names v)))]]})

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

   :put! (fn [{old ::board board :edn}]
           (boards/save datomic board (:db/id old)))

   :handle-ok (fn [{board :board identity :identity}]
                (->> (boards/find-threads datomic (:db/id board) identity)
                     (map #(assoc % :thread/writenum (comments/count-writenum datomic (:db/id %) identity)))
                     (map #(update % :thread/watchers
                        (fn [watchers] (apply hash-set watchers))

                        ))
                     ((fn [threads] (assoc board :board/threads (vec threads))))
                     ((fn [board] (assoc board :user/permissions (:user/permissions identity))))))))
