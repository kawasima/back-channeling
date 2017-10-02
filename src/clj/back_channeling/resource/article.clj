(ns back-channeling.resource.article
  (:require [liberator.core :as liberator]
            [bouncer.validators :as v]

            (back-channeling [util :refer [parse-request]])
            (back-channeling.boundary [articles :as articles])))

(defn articles-resource [{:keys [datomic]}]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:get :post]
   :malformed? #(parse-request %)
   :post-to-existing? (fn [{{article-name :article/name} :edn :as ctx}]
                        (not
                         (or
                          (#{:get} (get-in ctx [:request :request-method]))
                          (and (#{:post} (get-in ctx [:request :request-method]))
                               (articles/find-by-name datomic article-name)))))

   ;; Only :post-to-existing? = false pattern.
   :put-to-existing? (fn [ctx]
                       (#{:post} (get-in ctx [:request :request-method])))
   :conflict? (fn [ctx]
                (#{:post} (get-in ctx [:request :request-method])))

   :post! (fn [{article :edn req :request}]
            {:db/id (articles/save datomic article)})

   :handle-created (fn [ctx]
                     {:db/id (:db/id ctx)})
   :handle-ok (fn [_] (articles/find-all datomic))))

(defn article-resource [{:keys [datomic]} article-id]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:get :put :delete]
   :malformed? #(parse-request %)
   :put! (fn [{article :edn req :request}]
           (let [retract-transaction (->> (articles/find-blocks datomic article-id)
                                          :article/blocks
                                          (map (fn [{id :db/id}]
                                                 [:db/retract article-id :article/blocks id])))]
             (articles/save datomic article retract-transaction)))

   :handle-ok (fn [_]
                (articles/find-by-id datomic article-id))))
