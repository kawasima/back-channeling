(ns back-channeling.test-helper
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [datomic.api :as d]
            [integrant.core :as ig]
            [duct.core :as duct]
            [duct.logger]
            [back-channeling.database.datomic]
            [back-channeling.migrator.schema]
            [back-channeling.websocket.socketapp :refer [ISendMessage]]))

(duct/load-hierarchy)

;; -- Mock socketapp ---------------------------------------------------------

(defrecord MockSocketapp [messages]
  ISendMessage
  (broadcast-message [_ message]
    (swap! messages conj {:type :broadcast :message message}))
  (multicast-message [_ message users]
    (swap! messages conj {:type :multicast :message message :users users}))
  (board-multicast-message [_ message board-name]
    (swap! messages conj {:type :board-multicast :message message :board board-name}))
  (on-connect [_ exchange channel] nil)
  (on-message [_ ch message] nil)
  (on-close [_ ch close-reason] nil))

(defn mock-socketapp []
  (->MockSocketapp (atom [])))

(defn sent-messages [mock]
  @(:messages mock))

;; -- Test system ------------------------------------------------------------

(defn- unique-db-uri []
  (str "datomic:mem://test-" (java.util.UUID/randomUUID)))

(defn test-system
  "Create a minimal test system with in-memory Datomic and mock socketapp.
   Returns a map with :datomic, :cache, :socketapp keys suitable for passing
   to resource constructors."
  []
  (let [uri (unique-db-uri)
        _ (d/create-database uri)
        connection (d/connect uri)
        datomic (back_channeling.database.datomic.Boundary. connection)
        ;; Run migrations
        config (duct/read-config (io/resource "back_channeling/config.edn"))
        _ (ig/init-key :back-channeling.migrator/schema
                       {:datomic datomic
                        :logger (reify duct.logger/Logger
                                  (-log [_ level ns-str file line id event data] nil))})
        cache (ig/init-key :back-channeling.database/cache {})
        socketapp (mock-socketapp)]
    {:datomic datomic
     :cache cache
     :socketapp socketapp
     :connection connection
     :uri uri}))

(defn teardown-system [{:keys [uri connection]}]
  (d/release connection)
  (d/delete-database uri))

(defn create-test-user
  "Create a user in the test database. Returns the user entity id."
  [{:keys [connection]} user-name email]
  (let [user-id (d/tempid :db.part/user)
        tx-result @(d/transact connection
                     [{:db/id user-id
                       :user/name user-name
                       :user/email email}])]
    (d/resolve-tempid (d/db connection) (:tempids tx-result) user-id)))

(defn create-test-board
  "Create a board in the test database."
  [{:keys [connection]} board-name description]
  (let [board-id (d/tempid :db.part/user)
        tx-result @(d/transact connection
                     [{:db/id board-id
                       :board/name board-name
                       :board/description description}])]
    (d/resolve-tempid (d/db connection) (:tempids tx-result) board-id)))

(defn make-authenticated-request
  "Build a Ring request map with identity (authenticated user)."
  [method & {:keys [identity body content-type]}]
  (cond-> {:request-method method
           :headers {"accept" "application/edn"}}
    identity (assoc :identity identity)
    body     (assoc :body (pr-str body))
    true     (assoc :content-type (or content-type
                                      (when body "application/edn")))))
