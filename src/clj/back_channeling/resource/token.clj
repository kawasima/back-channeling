(ns back-channeling.resource.token
  (:require [liberator.core :as liberator]
            [bouncer.validators :as v]
            (back-channeling [util :refer [parse-request]])
            (back-channeling.boundary [users :as users]
                                      [tokens :as tokens])))

(defn token-resource [{:keys [datomic cache]}]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:post]
   :malformed? (fn [ctx]
                 (if-let [identity (get-in ctx [:request :identity])]
                   [false {::identity identity}]
                   (if-let [code (get-in ctx [:request :params :code])]
                     (if-let [identity (users/find-by-token datomic code)]
                       [false {::identity identity}]
                       {:message "code is invalid."})
                     {:message "code is required."})))

   :post! (fn [{identity ::identity}]
            (let [access-token (tokens/new-token cache identity)]
              {::post-response (merge identity
                                      {:token-type "bearer"
                                       :access-token access-token})}))
   :handle-created (fn [ctx]
                     (::post-response ctx))))
