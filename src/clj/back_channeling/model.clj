(ns back-channeling.model
  (:use [datomic-schema.schema :only [fields part schema]]
        [environ.core :only [env]])
  (:require [datomic.api :as d]
            [datomic-schema.schema :as s]))

(def uri (or (env :datomic-url "datomic:free://localhost:4334/bc")))
;;(def uri (or (env :datomic-url "datomic:mem://bc")))
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
            [comments :ref :many]
            [since :instant]
            [last-updated :instant]
            [watchers :ref :many]))
   (schema comment
           (fields
            [posted-at :instant]
            [posted-by :ref]
            [content :string :fulltext]
            [format :enum [:plain :markdown]]))
   (schema user
           (fields
            [name :string :unique-value]
            [email :string :unique-value]
            [password :string]
            [salt :bytes]))
   (schema article
           (fields
            [name :string]
            [content :string]
            [thread :ref]
            [curator :ref]))
   (schema notification
           (fields
            [target-user :ref :many]
            [type :enum [:referred :mentioned]]))])

(defn generate-enums [& enums]
  (apply concat
         (map #(s/get-enums (name (first %)) :db.part/user (second %)) enums)))

(defn create-schema []
  (d/create-database uri)
  (reset! conn (d/connect uri))
  (let [schema (concat
                (s/generate-parts (dbparts))
                #_(generate-enums [])
                (s/generate-schema (dbschema)))]
    (transact schema)
    (when-not (query '{:find [?e .] :where [[?e :board/name "default"]]})
      (transact [{:db/id #db/id[:db.part/user] :board/name "default" :board/description "Default board"}]))))

