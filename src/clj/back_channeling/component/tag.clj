(ns back-channeling.component.tag
  (:require [clojure.tools.logging :as log]
            [liberator.core :as liberator]
            [bouncer.validators :as v]
            [com.stuartsierra.component :as component]
            (back-channeling [util :refer [parse-request]])
            (back-channeling.component [datomic :as d]
                                       [user :as user])))

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
                                  :tag/private? (or (:tag/private? tag) false)
                                  :tag/color (-> tag
                                                 (get :tag/color :tag.color/white)
                                                 keyword)
                                  :tag/priority (or (:tag/priority tag) 0)}])
                    :tempids)]
    [tempids tag-id]))

(defn find-tag [datomic tag-id]
  (when tag-id
    (d/query datomic
             '{:find [(pull ?t [:*]) .]
               :in [$ ?t]
               :where [[?t :tag/name]]}
             tag-id)))

(defn allowed? [tag user-id]
  (or (not (:tag/private? tag)) (some #(= user-id %) (:tag/owners tag))))

(defn list-resource [{:keys [datomic]}]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:get :post]
   :malformed? #(parse-request % {:tag/name [[v/required]
                                             [v/max-count 255]]
                                  :tag/owner-name [[v/required]]
                                  :tag/color [[v/member [:tag.color/white
                                                         :tag.color/black
                                                         :tag.color/grey
                                                         :tag.color/yellow
                                                         :tag.color/orange
                                                         :tag.color/green
                                                         :tag.color/red
                                                         :tag.color/blue
                                                         :tag.color/pink
                                                         :tag.color/purple
                                                         :tag.color/brown
                                                         "tag.color/white"
                                                         "tag.color/black"
                                                         "tag.color/grey"
                                                         "tag.color/yellow"
                                                         "tag.color/orange"
                                                         "tag.color/green"
                                                         "tag.color/red"
                                                         "tag.color/blue"
                                                         "tag.color/pink"
                                                         "tag.color/purple"
                                                         "tag.color/brown"]]]})

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
                     (when     owner-id                             [:db/add tag-id :tag/owners owner-id])
                     (when-let [color (:tag/color new)]             [:db/add tag-id :tag/color color])
                     (when-let [priority (:tag/priority new)]       [:db/add tag-id :tag/priority priority])]]
             (d/transact datomic (filter some? qs))))

   :handle-ok (fn [{tag ::tag}]
                tag)))

(defn user-list-resource [{:keys [datomic]} user-name]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:get :post]
   :malformed? #(parse-request % {:db/id [[v/required]]})
   :allowed? (fn [ctx]
               (let [identity (get-in ctx [:request :identity])
                     user (user/find-user-by-name datomic user-name)
                     method (get-in ctx [:request :request-method])
                     tag (find-tag datomic (get-in ctx [:edn :db/id]))]
                 (when (or (= method :get) (allowed? tag (:db/id identity)))
                   {::tag tag ::user user ::identity identity ::method method})))

   :exists? (fn [{tag ::tag user ::user method ::method}]
              (if (= method :post)
                (when (d/query datomic
                               '{:find [?t .]
                                 :in [$ ?u ?t]
                                 :where [[?u :user/tags ?t]]}
                               (:db/id user) (:db/id tag))
                  true)
                (if-let [tags (d/query datomic
                                       '{:find [[(pull ?t [:*]) ...]]
                                         :in [$ ?u]
                                         :where [[?t :tag/owners ?u]
                                                 [?t :tag/private? true]]}
                                       (:db/id user))]
                  {::tags tags})))

   :handle-ok (fn [{tags ::tags}]
                tags)

   :post! (fn [{tag ::tag user ::user}]
              (d/transact datomic [[:db/add (:db/id user) :user/tags (:db/id tag)]])
              {:db/id (:db/id tag)})

   :post-to-existing? (fn [_] false)

   :handle-created (fn [{id :db/id}]
                     {:db/id id})))

(defn user-resource [{:keys [datomic]} user-name tag-id]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:delete]

   :allowed? (fn [ctx]
               (let [identity (get-in ctx [:request :identity])
                     user (user/find-user-by-name datomic user-name)
                     tag (find-tag datomic tag-id)]
                 (when (allowed? tag (:db/id identity))
                   {::tag tag ::user user ::identity identity})))

   :exists? (fn [{user ::user tag ::tag}]
              (some #(= (:db/id tag) %) (:user/tags user)))

   :delete! (fn [{user ::user tag ::tag}]
              (d/transact datomic [[:db/retract (:db/id user) :user/tags (:db/id tag)]]))))

(defrecord Tag []
  component/Lifecycle

  (start [component]
    component)

  (stop [component]
    component))

(defn tag-component [& options]
  (map->Tag options))
