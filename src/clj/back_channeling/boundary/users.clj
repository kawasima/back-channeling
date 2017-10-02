(ns back-channeling.boundary.users
  (:require [datomic.api :as d]
            [back-channeling.websocket.socketapp :refer [find-users]]
            [back-channeling.database.datomic]))

(defprotocol Users
  (find-by-name  [datomic user-name])
  (find-by-token [datomic token]))

(extend-protocol Users
  back_channeling.database.datomic.Boundary
  (find-by-name [{:keys [connection]} user-name]
    (d/q '{:find [(pull ?u [:db/id :user/name :user/email]) .]
           :in [$ ?u-name]
           :where [[?u :user/name ?u-name]]}
         (d/db connection) user-name))

  (find-by-token [{:keys [connection]} token]
    (d/q '{:find [(pull ?u [:user/name :user/email]) .]
           :in [$ ?token]
           :where [[?c :token-credential/user ?u]
                   [?c :token-credential/token ?token]]}
         (d/db connection) token)))

(defn find-active-users [socketapp]
  (find-users socketapp))
