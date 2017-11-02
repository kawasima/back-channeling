(ns back-channeling.migrator.schema
  (:require [integrant.core :as ig]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [duct.logger :refer [log]]
            [datomic.api :as d])
  (:import [java.util Date]))

(defn find-migration-files []
  (let [loader (.getContextClassLoader (Thread/currentThread))]
    (when-let [in (.getResourceAsStream loader "back_channeling/migration")]
      (->> (io/reader in)
           line-seq
           (filter #(.endsWith % ".edn"))
           sort
           (map #(.getResource loader (str "back_channeling/migration/" %)))))))

(def schema-version
  [{:db/id #db/id[:db.part/db]
    :db/ident :schema-version/version
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/unique :db.unique/identity
    :db.install/_attribute :db.part/db}
   {:db/id #db/id[:db.part/db]
    :db/ident :schema-version/installed-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}])

(defn find-or-create-version [connection]
  (let [version-attr (d/q '{:find [?v .]
                            :in [$ ?v]
                            :where [[?e :db/ident ?v]]}
                          (d/db connection) :schema-version/version)]

    (if version-attr
      (d/q '{:find [(max ?v) .]
             :where [[?e :schema-version/version ?v]]}
           (d/db connection))
      (do @(d/transact connection schema-version)
          nil))))

(defn extract-version [url]
  (as-> url u
    (.getFile u)
    (re-find #"v(\d+)_" u)
    (nth u 1)
    (Integer/parseInt u)))

(defmethod ig/init-key :back-channeling.migrator/schema
  [key {:keys [up down datomic logger] :as opts}]
  (let [connection (:connection datomic)
        migrations (find-migration-files)
        current-version (find-or-create-version connection)
        readers {'db/id #(apply d/tempid %)}]
    (doseq [mig migrations]
      (let [tx-data (edn/read-string {:readers readers} (slurp mig))
            version (or (extract-version mig) 0)]
        (when (> version (or current-version 0))
          (log logger :report ::migration {:version version})

          @(d/transact
            connection
            (conj tx-data {:db/id #db/id[:db.part/user]
                           :schema-version/version version
                           :schema-version/installed-at (Date.)})))))))
