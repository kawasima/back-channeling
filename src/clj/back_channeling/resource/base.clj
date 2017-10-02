(ns back-channeling.resource.base)

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
