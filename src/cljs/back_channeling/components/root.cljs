(ns back-channeling.components.root
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan timeout]]
            [back-channeling.routing :as routing]
            [back-channeling.api :as api]
            [back-channeling.socket :as socket]
            [back-channeling.components.avatar :refer [avatar]])
  (:use [back-channeling.components.board :only [board-view]]
        [back-channeling.components.curation :only [curation-page]]
        [cljs.reader :only [read-string]]))

(defn refresh-board [app board-name]
  (api/request (str "/api/board/" board-name)
               {:handler (fn [response]
                           (if (get-in app [:boards board-name])
                             (om/transact! app [:boards board-name :board/threads]
                                           (fn [threads]
                                             (merge-with merge (:board/threads response) threads)))
                             (let [board (update-in response [:board/threads]
                                                    (fn [threads]
                                                      (->> threads
                                                           (map (fn [t] {(:db/id t) t}))
                                                           (reduce merge {}))))]
                               (om/update! app [:boards board-name] board))))}))

(defn fetch-comments [app {:keys [board/name thread/id]}]
  (api/request (str "/api/thread/" id)
               {:handler (fn [thread]
                           (om/transact! app [:boards "default" :board/threads]
                                         (fn [threads]
                                           (assoc threads id thread))))}))

(defn search-threads [owner board-name query]
  (api/request (str "/api/board/" board-name "/threads?q=" (js/encodeURIComponent query))
               {:handler (fn [results]
                           (om/set-state! owner :search-result results))}))

(defcomponent root-view [app owner]
  (init-state [_]
    {:open-profile? false
     :open-users? false
     :search-result nil
     :board-channel (chan)
     :user {:user/name  (.. js/document (querySelector "meta[property='bc:user:name']") (getAttribute "content"))
            :user/email (.. js/document (querySelector "meta[property='bc:user:email']") (getAttribute "content"))}
     :called-message nil})
  (will-mount [_]
    (routing/init app owner)
    (refresh-board app "default")
    (api/request "/api/users"
                 {:handler (fn [response]
                             (om/update! app :users (apply hash-set response)))})
    (socket/open (str "ws://" (.-host js/location) "/ws")
                 :on-open (fn []
                            (socket/send :join (om/get-state owner :user)))
                 :on-message (fn [message]
                               (let [[cmd data] (read-string message)]
                                 (case cmd
                                   :update-board (refresh-board app "default")
                                   :update-thread (fetch-comments app data)
                                   :join  (om/transact! app [:users] #(conj % data))
                                   :leave (om/transact! app [:users] #(disj % data))
                                   :call  (js/alert (:message data)))))))
  (did-update [_ _ _]
    (when-let [thread-id (:target-thread app)]
      (put! (om/get-state owner :board-channel) [:open-thread thread-id])))

  (render-state [_ {:keys [open-profile? open-users? search-result user board-channel]}]
    (html
     [:div
      [:div.ui.fixed.menu
       [:div.item
        "Back channeling"]
       [:div.center.menu
        [:div.item
         [:div.ui.search
          [:div.ui.icon.input
           [:input#search.prompt
            {:type "text"
             :placeholder "Keyword"
             :on-key-up (fn [_]
                         (if-let [query (.. js/document (getElementById "search") -value)]
                           (when (> (count query) 2)
                             (search-threads owner "default" query))))}]
           [:i.search.icon]]
          (when (not-empty search-result)
            [:div.results.transition.visible
             (for [res search-result]
               [:a.result {:on-click
                           (fn [_]
                             (put! board-channel [:open-tab {:db/id (:db/id res)
                                                             :thread/title (:thread/title res)}])
                             (om/set-state! owner :search-result nil))}
                [:div.content
                 [:div.title (:thread/title res)]]])])]]]
       
       [:div.right.menu
        [:a.item
         [:div {:on-click (fn [_]
                            (om/set-state! owner :open-users? (not open-users?)))}
          [:i.users.icon]
          [:floating.ui.label (count (:users app))]]
         (when open-users?
           [:div.ui.flowing.popup.left.bottom.transition.visible {:style {:top "60px"}}
            [:ui.grid
             (for [member (:users app)]
               [:column {:on-click (fn [_]
                                     (socket/send :call {:from user
                                                         :to #{member}
                                                         :message (str (:user/name user) " is calling!!")}))}
                (om/build avatar (:user/email member))])]])]
        [:div.ui.dropdown.item
         [:div {:on-click (fn [_]
                            (om/set-state! owner :open-profile? (not open-profile?)))}
          (om/build avatar (:user/email user))
          [:span (:user/name user)] ]
         [:div.menu.transition {:class (if open-profile? "visible" "hidden")} 
          [:a.item {:href "/logout"} "Logout"]]]]]
      (when-let [board (get-in app [:boards "default"])]
        (case (:page app)
          :board (om/build board-view board
                           {:init-state {:channel board-channel}})
          :curation (om/build curation-page (get-in board [:board/threads (:target-thread app)]))))])))
