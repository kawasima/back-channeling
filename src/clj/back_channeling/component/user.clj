(ns back-channeling.component.user
  (:require [clojure.tools.logging :as log]
            [liberator.core :as liberator]
            [bouncer.validators :as v]
            [com.stuartsierra.component :as component]
            (back-channeling [util :refer [parse-request]])
            (back-channeling.component [datomic :as d]
                                       [socketapp :refer [find-users]])))

(defn find-user-by-name [datomic user-name]
  (d/query datomic
           '{:find [(pull ?u [:db/id :user/name :user/email :user/tags]) .]
             :in [$ ?u-name]
             :where [[?u :user/name ?u-name]]}
           user-name))

(defn list-resource [{:keys [socketapp]}]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:get]
   :handle-ok (fn [_]
                (vec (find-users socketapp)))))

(defn entry-resource [{:keys [datomic]} user-name]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:get]
   :exists? (fn [ctx]
              (when-let [user (find-user-by-name datomic user-name)]
                {::user user}))

   :handle-ok (fn [{user ::user}]
                user)))

(defrecord User []
  component/Lifecycle

  (start [component]
    component)

  (stop [component]
    component))

(defn user-component [& options]
  (map->User options))
