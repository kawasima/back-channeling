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
            [tags :ref :many]
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
            [format :enum [:plain :markdown :voice]]
            [reactions :ref :many]))
   (schema comment-reaction
           (fields
            [reaction-at :instant]
            [reaction-by :ref]
            [reaction :ref]))
   (schema reaction
           (fields
            [name :string :unique-value]
            [label :string]
            [src  :uri]))
   (schema user
           (fields
            [name :string :unique-value]
            [email :string :unique-value]
            [tags :ref :many]
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
            [comment-no :long]))
   (schema tag
           (fields
            [name :string :fulltext]
            [owners :ref :many]
            [private? :boolean]
            [description :string]))])

(defn generate-enums [& enums]
  (apply concat
         (map #(s/get-enums (name (first %)) :db.part/user (second %)) enums)))

(defn dbparts []
  [(part "message")])

(defn create-default-values [conn]
  @(d/transact conn
                   [{:db/id #db/id[:db.part/user]
                     :board/name "default"
                     :board/description "Default board"}
                    {:db/id #db/id[:db.part/user -1]
                     :reaction/name "GM"
                     :reaction/label "( ノﾟДﾟ)"}
                    {:db/id #db/id[:db.part/user -2]
                     :reaction/name "THX"
                     :reaction/label "(´▽｀)"}
                    {:db/id #db/id[:db.part/user -3]
                     :reaction/name "SRY"
                     :reaction/label "(m´・ω・｀)m"}
                    {:db/id #db/id[:db.part/user -4]
                     :reaction/name "BYE"
                     :reaction/label "( ´Д｀)ﾉ"}
                    {:db/id #db/id[:db.part/user -5]
                     :reaction/name "BR"
                     :reaction/label "( ｀・ω・´)ﾉ"}
                    {:db/id #db/id[:db.part/user -6]
                     :reaction/name "GJ"
                     :reaction/label "(・∀・)"}
                    {:db/id #db/id[:db.part/user -7]
                     :reaction/name "BAD"
                     :reaction/label "(・A・)"}
                    {:db/id #db/id[:db.part/user -8]
                     :reaction/name "OK"
                     :reaction/label "(｀･ω･´)ゞ"}
                    {:db/id #db/id[:db.part/user -9]
                     :reaction/name "NG"
                     :reaction/label "(´・д・｀)"}
                    {:db/id #db/id[:db.part/user -10]
                     :reaction/name "HERE"
                     :reaction/label "(ﾟдﾟ)/"}
                    {:db/id #db/id[:db.part/user -11]
                     :reaction/name "OMG"
                     :reaction/label "ヽ(`Д´)ﾉ"}
                    {:db/id #db/id[:db.part/user -12]
                     :reaction/name "LOL"
                     :reaction/label "((´∀｀))"}
                    ]))

(defn create-schema [conn]
  (let [schema (concat
                (s/generate-parts (dbparts))
                (s/generate-schema (dbschema)))]
    @(d/transact conn schema)
    (when-not (d/q '{:find [?e .] :where [[?e :board/name "default"]]} (d/db conn))
      (create-default-values conn))))


(defrecord ModelMigration []
  component/Lifecycle
  (start [component]
    (create-schema (get-in component [:datomic :connection]))
    component)
  (stop [component]
    component))

(defn migration-model []
  (ModelMigration.))
