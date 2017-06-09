(ns back-channeling.component.user
  (:require [clojure.tools.logging :as log]
            [liberator.core :as liberator]
            [bouncer.validators :as v]
            [com.stuartsierra.component :as component]
            (back-channeling [util :refer [parse-request]])
            (back-channeling.component [datomic :as d]
                                       [socketapp :refer [find-users]])))

(defn find-authorized-tag [datomic tag-id user-name]
  (let [public (d/query datomic
                        '{:find [(pull ?t [:*]) .]
                          :in [$ ?t]
                          :where [[?t :tag/private? false]]}
                        tag-id)
        private (d/query datomic
                         '{:find [(pull ?t [:*]) .]
                           :in [$ ?t ?u-name]
                           :where [[?t :tag/private? true]
                                   [?t :tag/owners ?o]
                                   [?o :user/name ?u-name]]}
                         tag-id
                         user-name)]
    (if public
      public
      private)))

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
              (if-let [user (d/query datomic
                                     '{:find [(pull ?u [:user/name :user/email :user/tags]) .]
                                       :in [$ ?u-name]
                                       :where [[?u :user/name ?u-name]]}
                                     user-name)]
                {::user user}
                false))

   :handle-ok (fn [{user ::user}]
                user)))

(defn tags-resource [{:keys [datomic]} user-name]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:post]
   :malformed? #(parse-request % {:db/id [[v/required]]})
   :allowed? (fn [ctx]
               (let [identity (get-in ctx [:request :identity])
                     user (d/query datomic
                                   '{:find [(pull ?u [:db/id :user/name :user/email :user/tags]) .]
                                     :in [$ ?u-name]
                                     :where [[?u :user/name ?u-name]]}
                                   user-name)
                     tag (find-authorized-tag datomic (get-in ctx [:edn :db/id]) (:user/name identity))]
                 (if tag
                   {::tag tag ::user user ::identity identity}
                   false)))

   :exists? (fn [{tag ::tag user ::user}]
              (when (d/query datomic
                             '{:find [?t .]
                               :in [$ ?u-name ?t]
                               :where [[?u :user/name ?u-name]
                                       [?u :user/tags ?t]]}
                             (:user/name user) (:db/id tag))
                true))

   :post! (fn [{tag ::tag user ::user}]
              (d/transact datomic [[:db/add (:db/id user) :user/tags (:db/id tag)]])
              {:db/id (:db/id tag)})

   :post-to-existing? (fn [_] false)

   :handle-created (fn [{id :db/id}]
                     {:db/id id})))

(defn tag-resource [{:keys [datomic]} user-name tag-id]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:delete]

   :allowed? (fn [ctx]
               (let [identity (get-in ctx [:request :identity])
                     user (d/query datomic
                                   '{:find [(pull ?u [:db/id :user/name :user/email :user/tags]) .]
                                     :in [$ ?u-name]
                                     :where [[?u :user/name ?u-name]]}
                                   user-name)
                     tag (find-authorized-tag datomic tag-id (:user/name identity))]
                 (if tag
                   {::tag tag ::user user ::identity identity}
                   false)))

   :exists? (fn [{identity ::identity}]
              (when (d/query datomic
                             '{:find [?t .]
                               :in [$ ?u-name ?t]
                               :where [[?u :user/name ?u-name]
                                       [?u :user/tags ?t]]}
                             user-name tag-id)
                true))

   :delete! (fn [{user ::user tag ::tag}]
              (d/transact datomic [[:db/retract (:db/id user) :user/tags (:db/id tag)]]))))

(defrecord User []
  component/Lifecycle

  (start [component]
    component)

  (stop [component]
    component))

(defn user-component [& options]
  (map->User options))
