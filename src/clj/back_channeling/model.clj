(ns back-channeling.model
  (:use [datomic-schema.schema :only [fields part schema]]
        [environ.core :only [env]])
  (:require [datomic.api :as d]
            [datomic-schema.schema :as s]))

(def uri (or (env :datomic-url "datomic:free://localhost:4334/bc")))
(defonce conn (atom nil))

(defn query [q & params]
  (let [db (d/db @conn)]
    (apply d/q q db params)))

(defn pull [pattern eid]
  (let [db (d/db @conn)]
    (d/pull db pattern eid)))

(defn transact [transaction]
  @(d/transact @conn transaction))

(defn dbparts []
  [(part "message")])

(defn dbschema []
  [(schema board
           (fields
            [name :string :indexed :unique-value :fulltext]
            [description :string]
            [threads :ref :many]))
   (schema thread
           (fields
            [title :string]
            [messages :ref :many]))
   (schema message
           (fields
            [posted-at :instant]
            [posted-by :ref]
            [content :string]))
   (schema user
           (fields
            [name :string]
            [email :string]))])

(defn generate-enums [& enums]
  (apply concat
         (map #(s/get-enums (name (first %)) :db.part/user (second %)) enums)))

(defn create-schema []
  (d/create-database uri)
  (reset! conn (d/connect uri))
  (let [schema (concat
                (s/generate-parts (dbparts))
                (generate-enums [:action [:abort :alert]])
                (s/generate-schema (dbschema)))]
    (d/transact
     @conn
     schema)))

