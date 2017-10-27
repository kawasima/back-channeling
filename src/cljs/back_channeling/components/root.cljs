(ns back-channeling.components.root
  (:require [om.core :as om :include-macros true]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put!]]
            [back-channeling.api :as api]
            [back-channeling.socket :as socket]
            [back-channeling.update :as update]
            [back-channeling.components.avatar :refer [avatar]])
  (:use [back-channeling.components.board :only [board-view boards-view]]
        [back-channeling.components.curation :only [article-page]]
        [back-channeling.component-helper :only [make-click-outside-fn]]))

(defn search-threads [owner board-name query]
  (api/request (str "/api/board/" board-name "/threads?q=" (js/encodeURIComponent query))
               {:handler (fn [results]
                           (om/set-state! owner :search-result results))}))

(defn root-view [app owner]
  (reify
    om/IInitState
    (init-state [_]
      {:open-profile? false
       :open-users? false
       :search-result nil
       :user {:user/name (.. js/document (querySelector "meta[property='bc:user:name']") (getAttribute "content"))
              :user/email (.. js/document (querySelector "meta[property='bc:user:email']") (getAttribute "content"))}
       :called-message nil
       :click-outside-fn nil})

    om/IWillMount
    (will-mount [_]
      (let [msgbox (om/get-shared owner :msgbox)]
        (update/init msgbox app)
        (api/request "/api/reactions"
                     {:handler
                      (fn [response]
                        (om/update! app :reactions response))})

        (api/request "/api/users"
                     {:handler
                      (fn [response]
                        (om/update! app :users (apply hash-set response)))})

        (when-let [on-click-outside (om/get-state owner :click-outside-fn)]
          (.removeEventListener js/document "mousedown" on-click-outside))))

    om/IDidMount
    (did-mount [_]
      (when-not (om/get-state owner :click-outside-fn)
        (om/set-state! owner :click-outside-fn
                       (make-click-outside-fn
                        (.. (om/get-node owner) (querySelector "div.site.menu"))
                        (fn [_]
                          (om/update-state! owner #(assoc %
                                                          :open-profile? false
                                                          :open-users? false
                                                          :search-result nil))))))
      (.addEventListener js/document "mousedown"
                         (om/get-state owner :click-outside-fn)))

    om/IRenderState
    (render-state [_ {:keys [open-profile? open-users? search-result user board-channel]}]
      (html
       [:div.full.height
        [:div.ui.fixed.site.menu
         [:div.item
          [:a {:href "#/"}
           [:img.ui.logo.image {:src (str (om/get-shared owner :prefix) "/img/logo.png")
                                :alt "Back Channeling"}]]]
         [:div.center.menu
          (when (= (:page app) :board)
            [:a.item {:href "#/"}
             [:h2.ui.header [:i.block.layout.icon] [:div.content (get-in app [:board :board/name])]]])
          [:div.item
           [:div.ui.search
            [:div.ui.icon.input
             [:input#search.prompt
              {:type "text"
               :placeholder "Keyword"
               :on-key-up (fn [_]
                            (if-let [query (.. js/document (getElementById "search") -value)]
                              (if (> (count query) 2)
                                (search-threads owner (get-in app [:board :board/name]) query)
                                (om/set-state! owner :search-result nil))))}]
             [:i.search.icon]]
            (when (not-empty search-result)
              [:div.results.transition.visible
               (for [res search-result]
                 [:a.result {:on-click
                             (fn [_]
                               (om/set-state! owner :search-result nil)
                               (set! (.-href js/location) (str "#/board/" (:board/name res) "/" (:db/id res))))}
                  [:div.content
                   [:div.title (:thread/title res)]]])])]]]

         [:div.right.menu
          [:a.item
           [:div {:on-click (fn [_]
                              (om/set-state! owner :open-users? (not open-users?)))}
            [:i.users.icon]
            [:div.ui.label (count (:users app))]]
           (when open-users?
             [:div.ui.flowing.popup.right.bottom.transition.visible {:style {:top "60px" :width "200px"}}
              [:div.ui.four.column.grid
               (for [member (:users app)]
                 [:column {:on-click (fn [_]
                                       (socket/send :call {:from user
                                                           :to #{member}
                                                           :message (str (:user/name user) " is calling!!")}))}
                  (om/build avatar member)])]])]
          [:div.ui.dropdown.item
           [:div {:on-click (fn [_]
                              (om/set-state! owner :open-profile? (not open-profile?)))}
            (om/build avatar user)
            [:span (:user/name user)] ]
           [:div.menu.transition {:class (if open-profile? "visible" "hidden")}
            [:a.item {:href "/logout"} "Logout"]]]]]
        (case (:page app)
          :boards (om/build boards-view (:boards app))
          :board (om/build board-view app
                           {:opts {:user user
                                   :reactions (:reactions app)}})
          :article (om/build article-page (:article app)
                             {:init-state {:thread (->> (:threads app)
                                                        (filter #(= (:thread/active? %) true))
                                                        first)}
                              :opts {:user user
                                     :board-name (get-in app [:board :board/name])}})
          [:div.main.content.full.height
           [:div.ui.active.centered.inline.text.loader "Loading..."]])]))))
