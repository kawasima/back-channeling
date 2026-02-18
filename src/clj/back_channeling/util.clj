(ns back-channeling.util
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [malli.core :as m]
            [malli.error :as me]))

(defn- body-as-string
  "Returns a request body as String."
  [ctx]
  (if-let [body (get-in ctx [:request :body])]
    (condp instance? body
      java.lang.String body
      (slurp (io/reader body)))))

(defn- validate
  "Validate the given model with the given spec of validation."
  [model validation-schema]
  (if validation-schema
    (if-let [errors (m/explain validation-schema model)]
      {:message (pr-str (me/humanize errors))}
      [false {:edn model}])
    [false {:edn model}]))

(defn parse-request
  ([context]
   (parse-request context nil))
  ([context validation-schema]
   (when (#{:put :post} (get-in context [:request :request-method]))
     (try
       (if-let [body (body-as-string context)]
         (case (get-in context [:request :content-type])
           "application/edn"  (validate (edn/read-string body) validation-schema)
           "application/json" (validate (json/read-str body :key-fn keyword) validation-schema)
           {:message "Unknown format."})
         false)
       (catch Exception e
         {:message (format "IOException: %s" (.getMessage e))})))))
