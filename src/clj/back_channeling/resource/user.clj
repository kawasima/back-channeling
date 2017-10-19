(ns back-channeling.resource.user
  (:require [liberator.core :as liberator]
            [bouncer.validators :as v]
            (back-channeling [util :refer [parse-request]])
            (back-channeling.boundary [users :as users])
            (back-channeling.resource [base :refer [base-resource]])))

(defn users-resource [{:keys [socketapp]}]
  (liberator/resource base-resource
   :allowed-methods [:get]
   :handle-ok (fn [_]
                (vec (users/find-active-users socketapp)))))

(defn user-resource [{:keys [datomic]} user-name]
  (liberator/resource base-resource
   :allowed-methods [:get]
   :exists? (fn [ctx]
              (when-let [user (users/find-by-name datomic user-name)]
                {::user user}))

   :handle-ok (fn [{user ::user identity :identity}]
                (if (= (:user/name identity) user-name)
                  (assoc user :user/permissions (:user/permissions identity))
                  user))))