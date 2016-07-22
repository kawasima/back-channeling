(ns back-channeling.signup
  (:require [hiccup.core :refer [html]]
            [hiccup.page :refer [include-js]]
            [ring.util.response :refer [resource-response content-type header redirect]]
            [ring.middleware.flash :refer [flash-response]]
            [buddy.core.nonce :as nonce]
            [buddy.core.hash]
            [bouncer.core :as b]
            [bouncer.validators :as v :refer [defvalidator]]
            [back-channeling [layout :refer [layout]]]
            [back-channeling.component [datomic :as d]]))

(def robot-svg [:svg#robot-svg {:version "1.1" :xmlns "http://www.w3.org/2000/svg" :xmlns/xlink "http://www.w3.org/1999/xlink" :width "64px" :height "64px" :x "0px" :y "0px"}
                [:g {:transform "scale(2.5)"}
                 [:path {:d "M15.907,1.762C14.925,0.682,13.519,0,11.948,0c-1.569,0-2.977,0.682-3.958,1.762c-0.872,0.96-1.409,2.233-1.409,3.636
c0.001,2.561,0.545,3.904,1.435,4.612c0.162,0.129,0.334,0.237,0.518,0.327c-0.072-0.058-0.141-0.119-0.206-0.183
c-0.538-0.529-0.875-1.257-0.881-2.06C7.415,7.266,7.399,6.71,7.38,6.066c-0.008-0.339-0.02-0.702-0.033-1.146
c0-1.676,1.743-2.929,3.304-2.929h2.595c1.892,0,3.304,1.546,3.304,2.937c-0.013,0.435-0.023,0.797-0.033,1.137
c-0.019,0.643-0.034,1.2-0.067,2.022c-0.006,0.807-0.345,1.539-0.884,2.067c-0.07,0.07-0.146,0.135-0.223,0.198
c0.202-0.097,0.393-0.215,0.568-0.358c0.872-0.713,1.405-2.057,1.405-4.596C17.316,3.996,16.779,2.722,15.907,1.762L15.907,1.762z"}]
                 [:path {:d "M13.181,2.459h-2.464C9.313,2.459,7.819,3.597,7.819,5c0.038,1.194,0.047,1.801,0.096,3.004
c0.007,0.913,0.497,1.707,1.222,2.151c0.39,0.241,0.85,0.381,1.342,0.381h2.941c0.492,0,0.952-0.14,1.343-0.381
c0.724-0.445,1.214-1.237,1.221-2.151C16.032,6.801,16.042,6.195,16.08,5C16.08,3.597,14.584,2.459,13.181,2.459z M10.479,6.921
c-0.16,0.124-0.357,0.197-0.575,0.197c-0.528,0-0.957-0.429-0.957-0.957c0-0.53,0.429-0.957,0.957-0.957
c0.184,0,0.356,0.055,0.502,0.145c0.271,0.169,0.454,0.468,0.454,0.812C10.86,6.473,10.71,6.748,10.479,6.921z M13.995,7.119
c-0.218,0-0.416-0.074-0.575-0.197c-0.231-0.174-0.382-0.449-0.382-0.759c0-0.344,0.182-0.643,0.454-0.812
c0.146-0.09,0.318-0.145,0.503-0.145c0.529,0,0.957,0.427,0.957,0.957C14.951,6.69,14.524,7.119,13.995,7.119z"}]
                 [:path {:d "M15.111,6.144"}]
                 [:path {:d "M16.816,22.637c0,0.75-0.624,1.363-1.386,1.363H8.468c-0.763,0-1.386-0.613-1.386-1.363v-9.714
c0-0.75,0.624-1.363,1.386-1.363h6.961c0.763,0,1.386,0.614,1.386,1.363V22.637z"}]
                 [:g
                  [:path {:d "M5.611,19.411v-6.201c0-0.482-0.499-0.873-1.114-0.873c-0.616,0-1.115,0.391-1.115,0.873v6.201H5.611z"}]
                  [:path {:d "M5.741,20.082c0.542-0.004,0.98,0.311,0.98,0.691c0,0.025-0.005,0.049-0.005,0.072s0.011,0.048,0.005,0.072l0.006,0.85
c0,0.306-0.355,0.557-0.794,0.557c-0.434,0-0.789-0.25-0.789-0.557v-0.298l-1.291,0.004v0.294c0,0.311-0.359,0.56-0.793,0.56
c-0.44,0-0.794-0.25-0.794-0.56v-0.847c0-0.043,0.011-0.087,0.022-0.131c0-0.004,0-0.008,0-0.012c0-0.386,0.439-0.696,0.986-0.696
H5.741z"}]]
                 [:g
                  [:path {:d "M20.618,19.411v-6.201c0-0.482-0.499-0.873-1.115-0.873c-0.616,0-1.114,0.391-1.114,0.873v6.201H20.618z"}]
                  [:path {:d "M20.747,20.082c0.542-0.004,0.98,0.311,0.98,0.691c0,0.025-0.005,0.049-0.005,0.072s0.011,0.048,0.005,0.072l0.006,0.85
c0,0.306-0.354,0.557-0.794,0.557c-0.434,0-0.788-0.25-0.788-0.557v-0.298l-1.291,0.004v0.294c0,0.311-0.359,0.56-0.793,0.56
c-0.439,0-0.794-0.25-0.794-0.56v-0.847c0-0.043,0.011-0.087,0.022-0.131c0-0.004,0-0.008,0-0.012c0-0.386,0.44-0.696,0.986-0.696
H20.747z"}]]]])

(def human-svg [:svg#human-svg {:version "1.1" :xmlns "http://www.w3.org/2000/svg" :xmlns/xlink "http://www.w3.org/1999/xlink" :width "64px" :height "64px" :x "0px" :y "0px"}
                [:g {:transform "scale(0.7)"}
                 [:path {:d "M46.004,21.672c5.975,0,10.836-4.861,10.836-10.836S51.979,0,46.004,0c-5.975,0-10.835,4.861-10.835,10.836
S40.029,21.672,46.004,21.672z"}]
                 [:path {:d "M68.074,54.008L59.296,26.81c-0.47-1.456-2.036-2.596-3.566-2.596h-1.312H53.48H38.526h-0.938h-1.312
c-1.53,0-3.096,1.14-3.566,2.596l-8.776,27.198c-0.26,0.807-0.152,1.623,0.297,2.24s1.193,0.971,2.041,0.971h2.25
c1.53,0,3.096-1.14,3.566-2.596l2.5-7.75v10.466v0.503v29.166c0,2.757,2.243,5,5,5h0.352c2.757,0,5-2.243,5-5V60.842h2.127v26.166
c0,2.757,2.243,5,5,5h0.352c2.757,0,5-2.243,5-5V57.842v-0.503v-10.47l2.502,7.754c0.47,1.456,2.036,2.596,3.566,2.596h2.25
c0.848,0,1.591-0.354,2.041-0.971S68.334,54.815,68.074,54.008z"}]]])

(defn signup-view [{params :param error-map :error-map :as req}]
  (layout req
   [:div.ui.middle.aligned.center.aligned.login.grid
    [:div.column
     [:h2.ui.header
      [:div.content
       [:img.ui.image {:src "/img/logo.png"}]]]
     [:form.ui.large.login.form (merge {:method "post"}
                                       (when error-map {:class "error"})) 
      [:div.ui.stacked.segment
       [:div.field
        [:div.ui.two.column.grid
         [:div.row
          [:div.column.account-type.on  human-svg]
          [:div.column.account-type.off robot-svg]]]]
       (when error-map
         [:div.ui.error.message
          [:ul.list
           (for [msg (concat (vals error-map))]
             [:li msg])]])
       [:div.field (when (:user/name error-map)
                     {:class "error"})
        [:div.ui.left.icon.input
         [:i.user.icon]
         [:input {:type "text" :name "user/name" :placeholder "User name" :value (:user/name params)}]]]
       [:div#email-field.field (when (:user/email error-map)
                                 {:class "error"})
        [:div.ui.left.icon.input
         [:i.mail.icon]
         [:input {:type "text" :name "user/email" :placeholder "Email address" :value (:user/email params)}]]]
       [:div#password-field.field (when (:user/password error-map)
                                    {:class "error"})
        [:div.ui.left.icon.input
         [:i.lock.icon]
         [:input {:type "password" :name "user/password" :placeholder "Password"}]]]
       [:div#token-field.field (when (:user/token error-map)
                                 {:class "error"})
        [:div.ui.action.input
         [:input {:type "text" :name "user/token" :placeholder "Token" :readonly true}]
         [:button.ui.icon.button {:type "button"}
          [:i.refresh.icon]]]]
       [:button.ui.fluid.large.teal.submit.button {:type "submit"} "Sign up"]]]]]
   (include-js "/js/signup.js")))

(defvalidator unique-email-validator
  {:default-message-format "%s is used by someone."}
  [email datomic]
  (nil? (d/query datomic
                 '{:find [?u .]
                   :in [$ ?email]
                   :where [[?u :user/email ?email]]}
                 email)))

(defvalidator unique-name-validator
  {:default-message-format "%s is used by someone."}
  [name datomic]
  (nil? (d/query datomic
                 '{:find [?u .]
                   :in [$ ?name]
                   :where [[?u :user/name ?name]]}
                 name)))

(defn validate-user [datomic user]
  (b/validate user
              :user/password [[v/required :pre (comp nil? :user/token)]
                              [v/min-count 8 :message "Password must be at least 8 characters long." :pre (comp nil? :user/token)]]
              :user/email    [[v/required]
                              [v/email]
                              [v/max-count 100 :message "Email is too long."]
                              [unique-email-validator datomic]]
              :user/token    [[v/required :pre (comp nil? :user/password)]
                              [v/matches #"[0-9a-z]{16}" :pre (comp nil? :user/password)]]
              :user/name     [[v/required]
                              [v/min-count 3 :message "Username must be at least 3 characters long."]
                              [v/max-count 20 :message "Username is too long."]
                              [unique-name-validator datomic]]))

(defn signup [datomic user]
  (let [[result map] (validate-user datomic user)]
    (if-let [error-map (:bouncer.core/errors map)]
      (signup-view {:error-map error-map :params user})
      (let [salt (nonce/random-nonce 16)
            password (some-> (not-empty (:user/password user))
                             (.getBytes)
                             (#(into-array Byte/TYPE (concat salt %)))
                             buddy.core.hash/sha256
                             buddy.core.codecs/bytes->hex)]
        (if-not (or password (:user/token user))
          (throw (Exception.)))
        (d/transact datomic
                    [(merge user
                            {:db/id #db/id[db.part/user -1]}
                            (when password
                              {:user/password password
                               :user/salt salt})
                            (when-let [token (:user/token user)]
                              {:user/token token}))])
        (-> (redirect "/")
            (flash-response {:flash (str "Create account " (:user/name user))}))))))
