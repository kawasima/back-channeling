(ns back-channeling.auth.util)

(defn api-access?
  "Returns true if the request expects an API response (JSON or EDN)."
  [req]
  (when-let [accept (get-in req [:headers "accept"])]
    (or (.contains accept "application/json")
        (.contains accept "application/edn"))))
