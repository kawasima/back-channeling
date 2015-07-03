(ns back-channeling.signup
  (:use [hiccup.core :only [html]]
        [ring.util.response :only [resource-response content-type header redirect]]
        [back-channeling [layout :only [layout]]])
  (:require [buddy.core.nonce :as nonce]
            [buddy.core.hash]
            (back-channeling [model :as model])))

(defn signup-view [req]
  (layout req
   [:div.ui.middle.aligned.center.aligned.login.grid
    [:div.column
     [:h2.ui.header
      [:div.content "Back channeling"]]
     [:form.ui.large.login.form {:method "post"}
      [:div.ui.stacked.segment
       [:div.field
        [:div.ui.left.icon.input
         [:i.user.icon]
         [:input {:type "text" :name "user/name" :placeholder "User name"}]]]
       [:div.field
        [:div.ui.left.icon.input
         [:i.mail.icon]
         [:input {:type "text" :name "user/email" :placeholder "Email address"}]]]
       [:div.field
        [:div.ui.left.icon.input
         [:i.lock.icon]
         [:input {:type "password" :name "user/password" :placeholder "Password"}]]]
       [:button.ui.fluid.large.teal.submit.button {:type "submit"} "Sign up"]]
      [:div.ui.error.message]]]]))

(defn signup [user]
  (let [salt (nonce/random-nonce 16)
        password (-> (into-array Byte/TYPE (concat salt (.getBytes (:user/password user))))
                     buddy.core.hash/sha256
                     buddy.core.codecs/bytes->hex)]
    (model/transact [(merge user
                            {:db/id #db/id[db.part/user -1]
                             :user/password password
                             :user/salt salt})])
          (redirect "/")))
