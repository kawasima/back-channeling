(ns back-channeling.resource.base
  (:require (back-channeling.boundary [comments :as comments]
                                      [threads :as threads])))

(def base-resource
  {:available-media-types ["application/edn" "application/json"]
   :authorized? (fn [ctx]
                  (if-let [identity (get-in ctx [:request :identity])]
                    {:identity identity}
                    false))})

;; -- Permission sets -------------------------------------------------------
;; Centralised so that resource handlers share a single definition.

(def perm-read-thread   #{:read-thread :read-any-thread})
(def perm-write-thread  #{:write-thread :write-any-thread})
(def perm-delete-comment #{:delete-comment :delete-any-comment})

(defn has-permission? [ctx permissions]
  (let [user-permissions (get-in ctx [:request :identity :user/permissions])]
    (if (nil? user-permissions)
      ;; Permission system not active (no Bouncr integration) — allow access
      ;; for any authenticated user. The :authorized? check in base-resource
      ;; already rejects unauthenticated requests.
      true
      ;; Permission system active — require at least one matching permission
      (boolean (some permissions user-permissions)))))

(defn thread-allowed? [ctx datomic permissions thread-id]
  (or (has-permission? ctx #{:write-any-thread})
      (:thread/public? (threads/pull datomic thread-id))
      (> (or (comments/count-writenum datomic thread-id (:identity ctx)) 0) 0)))
