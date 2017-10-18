(ns back-channeling.resource.base
  (:require (back-channeling.boundary [comments :as comments]
                                      [threads :as threads])))

(def base-resource
  {:available-media-types ["application/edn" "application/json"]
   :authorized? (fn [ctx]
                  (if-let [identity (get-in ctx [:request :identity])]
                    {:identity identity}
                    false))})

(defn has-permission? [ctx permissions]
  (if-let [has-permissions (get-in ctx [:request :identity :user/permissions])]
    (some permissions has-permissions)
    true))

(defn thread-allowed? [ctx datomic permissions thread-id]
  (or (has-permission? ctx #{:write-any-thread})
      (:thread/public? (threads/pull datomic thread-id))
      (> (comments/count-by-identity datomic thread-id (:identity ctx)) 0)))
