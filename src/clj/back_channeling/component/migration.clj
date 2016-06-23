(ns back-channeling.component.migration
  (:require [com.stuartsierra.component :as component]
            [datomic-schema.schema :refer [fields part schema]]
            [datomic.api :as d]
            [datomic-schema.schema :as s]))

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
            [format :enum [:plain :markdown :voice]]))
   (schema user
           (fields
            [name :string :unique-value]
            [email :string :unique-value]
            [password :string]
            [salt :bytes]
            [token :string]))
   (schema article
           (fields
            [name :string :unique-value]
            [blocks :ref :many]
            [thread :ref]
            [curator :ref]))
   (schema curating-block
           (fields
            [posted-at :instant]
            [posted-by :ref]
            [content :string]
            [format :enum [:plain :markdown :voice]]))
   (schema notification
           (fields
            [target-users :ref :many]
            [type :enum [:referred :mentioned]]
            [thread :ref]
            [comment-no :long]))])

(defn generate-enums [& enums]
  (apply concat
         (map #(s/get-enums (name (first %)) :db.part/user (second %)) enums)))

(defn dbparts []
  [(part "message")])

(defn create-schema [conn]
  (let [schema (concat
                (s/generate-parts (dbparts))
                #_(generate-enums [])
                (s/generate-schema (dbschema)))]
    @(d/transact conn schema)
    (when-not (d/q '{:find [?e .] :where [[?e :board/name "default"]]} (d/db conn))
      @(d/transact conn
                   [{:db/id #db/id[:db.part/user]
                     :board/name "default"
                     :board/description "Default board"}]))))

(defrecord ModelMigration []
  component/Lifecycle
  (start [component]
    (create-schema (get-in component [:datomic :connection]))
    component)
  (stop [component]
    component))

(defn migration-model []
  (ModelMigration.))
