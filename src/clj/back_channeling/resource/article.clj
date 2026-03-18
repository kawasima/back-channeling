(ns back-channeling.resource.article
  (:require [liberator.core :as liberator]

            (back-channeling [util :refer [parse-request]])
            (back-channeling.boundary [articles :as articles])
            (back-channeling.resource [base :refer [base-resource has-permission?]])))

(defn articles-resource [{:keys [datomic]}]
  (liberator/resource base-resource
   :allowed-methods [:get :post]
   :malformed? #(parse-request %)
   :exists? (fn [{{article-name :article/name} :edn :as ctx}]
              (case (get-in ctx [:request :request-method])
                :get  true
                :post (if article-name
                        (if (articles/find-by-name datomic article-name)
                          {::existing true}
                          false)
                        false)))
   :conflict? (fn [ctx] (::existing ctx))

   :post! (fn [{article :edn}]
            {:db/id (articles/save datomic article)})

   :handle-created (fn [ctx]
                     {:db/id (:db/id ctx)})
   :handle-ok (fn [_] (articles/find-all datomic))))

(defn article-resource [{:keys [datomic]} article-id]
  (liberator/resource base-resource
   :allowed-methods [:get :put :delete]
   :malformed? #(parse-request %)
   :put! (fn [{article :edn}]
           (let [retract-transaction (->> (articles/find-blocks datomic article-id)
                                          :article/blocks
                                          (map (fn [{id :db/id}]
                                                 [:db/retract article-id :article/blocks id])))]
             (articles/save datomic article retract-transaction)))

   :handle-ok (fn [_]
                (articles/find-by-id datomic article-id))))
