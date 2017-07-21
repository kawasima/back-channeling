(ns back-channeling.component.tag
  (:require [clojure.tools.logging :as log]
            [liberator.core :as liberator]
            [bouncer.validators :as v]
            [com.stuartsierra.component :as component]
            (back-channeling [util :refer [parse-request]])
            (back-channeling.component [datomic :as d])))

(defn save-tag [datomic tag]
  (let [tag-id (d/tempid :db.part/user)
        owner-id (d/query datomic
                      '{:find [?u .]
                        :in [$ ?name]
                        :where [[?u :user/name ?name]]}
                      (:tag/owner-name tag))
        tempids (-> (d/transact datomic
                                [{:db/id tag-id
                                  :tag/name (:tag/name tag)
                                  :tag/description (or (:tag/description tag) "")
                                  :tag/owners owner-id
                                  :tag/private? (or (:tag/private? tag) false)}])
                    :tempids)]
    [tempids tag-id]))

(defn list-resource [{:keys [datomic]}]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:get :post]
   :malformed? #(parse-request % {:tag/name [[v/required]
                                             [v/max-count 255]]
                                  :tag/owner-name [[v/required]]})

   :post! (fn [{tag :edn req :request}]
            (let [[tempids tag-id] (save-tag datomic tag)]
              {:db/id (d/resolve-tempid datomic tempids tag-id)}))

   :handle-created (fn [ctx]
                     {:db/id (:db/id ctx)})

   :handle-ok (fn [_]
                (d/query datomic
                         '{:find [[(pull ?tag [:*]) ...]]
                           :where [[?tag :tag/name]]}))))

(defn entry-resource [{:keys [datomic]} tag-id]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:get :put]
   :malformed? #(parse-request %)

   :authorized? (fn [ctx]
                  (if-let [identity (get-in ctx [:request :identity])]
                    {::identity identity}
                    false))

   :allowed? (fn [ctx]
               (let [identity (::identity ctx)
                     method (get-in ctx [:request :request-method])
                     owner-name (d/query datomic
                                         '{:find [?u-name .]
                                           :in [$ ?tag ?u-name]
                                           :where [[?tag :tag/owners ?owner]
                                                   [?owner :user/name ?u-name]]}
                                         tag-id (:user/name identity))]
                 (if (or owner-name (= method :get))
                   true
                   false)))

   :exists? (fn [ctx]
              (if-let [tag (d/pull datomic '[:*] tag-id)]
                {::tag tag}
                false))

   :new? (fn [_] false)

   :put! (fn [{old ::tag new :edn identity ::identity}]
           (let [owner-id (d/query datomic
                                   '{:find [?u .]
                                     :in [$ ?name]
                                     :where [[?u :user/name ?name]]}
                                   (:tag/owner-name new))
                 qs [(when-let [name (:tag/name new)]               [:db/add tag-id :tag/name name])
                     (when-let [description (:tag/description new)] [:db/add tag-id :tag/description description])
                     (when     owner-id                             [:db/add tag-id :tag/owners owner-id])]]
             (d/transact datomic (filter some? qs))))

   :handle-ok (fn [{tag ::tag}]
                tag)))

(defrecord Tag []
  component/Lifecycle

  (start [component]
    component)

  (stop [component]
    component))

(defn tag-component [& options]
  (map->Tag options))
