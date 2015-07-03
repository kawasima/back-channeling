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
     :search-result nil
     :board-channel (chan)})
  (will-mount [_]
    (routing/init app owner)
    (refresh-board app "default")
    (socket/open (str "ws://" (.-host js/location) "/ws")
                 :on-open (fn []
                            (socket/send [:join {:user/name (.. js/document (querySelector "meta[property='bc:user:name']") (getAttribute "content"))
                                                 :user/email (.. js/document (querySelector "meta[property='bc:user:email']") (getAttribute "content"))}]))
                 :on-message (fn [message]
                               (let [[cmd data] (read-string message)]
                                 (case cmd
                                   :update-board (refresh-board app "default")
                                   :update-thread (fetch-comments app data)
                                   :join  (om/transact! app [:users] #(conj % data))
                                   :leave (om/transact! app [:users] #(disj % data)))))))

  (render-state [_ {:keys [open-profile? search-result board-channel]}]
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
         [:i.users.icon]]
        [:div.ui.dropdown.item
         [:div {:on-click (fn [_]
                            (om/set-state! owner :open-profile? (not open-profile?)))}
          (om/build avatar (.. js/document (querySelector "meta[property='bc:user:email']") (getAttribute "content")))
          [:span (.. js/document (querySelector "meta[property='bc:user:name']") (getAttribute "content"))] ]
         [:div.menu.transition {:class (if open-profile? "visible" "hidden")} 
          [:a.item {:href "/logout"} "Logout"]]]]]
      (om/build board-view (get-in app [:boards "default"]) {:init-state {:channel board-channel}})])))
