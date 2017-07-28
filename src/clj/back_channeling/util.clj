(ns back-channeling.util
  (:require [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [bouncer.core :as b]))

(defn- body-as-string
  "Returns a request body as String."
  [ctx]
  (if-let [body (get-in ctx [:request :body])]
    (condp instance? body
      java.lang.String body
      (slurp (io/reader body)))))

(defn- validate
  "Validate the given model with the given spec of validation."
  [model validation-spec]
  (if validation-spec
    (let [[result map] (b/validate model validation-spec)]
      (if result
        {:message (pr-str (:bouncer.core/errors map))}
        [false {:edn model}]))
    [false {:edn model}]))

(defn parse-request
  ([context]
   (parse-request context nil))
  ([context validation-spec]
   (when (#{:put :post} (get-in context [:request :request-method]))
     (try
       (if-let [body (body-as-string context)]
         (case (get-in context [:request :content-type])
           "application/edn"  (validate (edn/read-string body) validation-spec)
           "application/json" (validate (json/read-str body :key-fn keyword) validation-spec)
           {:message "Unknown format."})
         false)
       (catch Exception e
         (log/error e "fail to parse edn.")
         {:message (format "IOException: %s" (.getMessage e))})))))
