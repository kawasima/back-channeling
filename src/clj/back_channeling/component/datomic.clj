(ns back-channeling.component.datomic
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]))

(defn query [{:keys [connection]} q & params]
  (let [db (d/db connection)]
    (apply d/q q db params)))

(defn pull [{:keys [connection]} pattern eid]
  (let [db (d/db connection)]
    (d/pull db pattern eid)))

(defn transact [{:keys [connection]} transaction]
  @(d/transact connection transaction))

(defn resolve-tempid [{:keys [connection]} tempids tempid]
  (let [db (d/db connection)]
    (d/resolve-tempid db tempids tempid)))

(defn tempid [n]
  (d/tempid n))

(defn dbparts []
  [(d/part "message")])

(defrecord DatomicConnection [uri]
  component/Lifecycle
  (start [component]
    (if (:connection component)
      component
      (let [create? (d/create-database uri)]
        (assoc component
               :connection (d/connect uri)))))
  (stop [component]
    (dissoc component :connection)))

(defn datomic-connection
  [uri]
  (DatomicConnection. uri))
