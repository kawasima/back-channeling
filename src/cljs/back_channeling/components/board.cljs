(ns back-channeling.components.board
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan timeout]]
            [back-channeling.api :as api]))

(enable-console-print!)

(defn save-comment [comment]
  (api/request (str "/api/thread/" (:thread/id comment) "/comments")
               :POST
               comment
               {:handler (fn [response]
                           )}))
(defn save-thread [thread]
  (api/request (str "/api/board/" (:board/name thread) "/threads")
               :POST
               thread
               {:handler (fn [response])}))

(defn open-thread [thread ch]
  (api/request (str "/api/thread/" (:db/id thread))
               {:handler (fn [response]
                           (om/update! thread [:thread/comments] (:thread/comments response))
                           (put! ch [:open-tab response]))}))

(defcomponent comment-new-view [thread owner]
  (render [_]
    (html
     [:form.ui.reply.form
      [:div.two.fields
       [:div.field
        [:label "Name"]
        [:input#name {:type "text" :name "name"}]]
       [:div.field
        [:label "Email"]
        [:input#email {:type "text" :name "email"}]]]
      [:div.field
       [:textarea#comment {:name "comment"}]]
      [:div.ui.blue.labeled.submit.icon.button
       {:on-click (fn [_]
                    (let [dom (om/get-node owner)]
                      (save-comment {:thread/id  (:db/id thread)
                                     :comment/content (.. dom (querySelector "[name=comment]") -value)
                                     :user/name  (or (not-empty (.. dom (querySelector "[name=name]") -value)) "名無しさん")
                                     :user/email (or (not-empty (.. dom (querySelector "[name=email]") -value)) "sage")})) )}
       [:i.icon.edit] "New comment"]])))

(defcomponent thread-view [thread owner]
  (render [_]
    (html
     [:div.ui.comments
      [:h3.ui.dividing.header (:thread/title thread)]
      (for [comment (:thread/comments thread)]
        [:div.comment
         [:a.avatar]
         [:div.content
          [:a.number (:comment/no comment)] ": "
          [:a.author (get-in comment [:comment/posted-by :user/name])]
          [:div.metadata
           [:span.date (get-in comment [:comment/posted-at])]]
          [:div.text (:comment/content comment)]]])
      (om/build comment-new-view thread)])))


(defcomponent thread-list-view [threads owner]  
  (render-state [_ {:keys [board-channel]}]
    (html
     [:table.ui.basi.table
      [:thead
       [:tr
        [:th "Title"]
        [:th "Res"]
        [:th "Since"]]]
      (for [thread (vals threads)]
        [:tr
         [:td
          [:a {:on-click (fn [_]
                           (open-thread thread board-channel))}
           (:thread/title thread)]]
         [:td (:thread/resnum thread)]
         [:td (:thread/since thread)]])])))


(defcomponent thread-new-view [board owner]
  (render [_]
    (html
     [:form.ui.reply.form
      [:div.field
       [:label "Title"]
       [:input {:type "text" :name "title"}]]
      [:div.two.fields
       [:div.field
        [:label "Name"]
        [:input {:type "text" :name "name"}]]
       [:div.field
        [:label "Email"]
        [:input#email {:type "text" :name "email"}]]]
      [:div.field
       [:textarea {:name "content"}]]
      [:div.ui.blue.labeled.submit.icon.button
       {:on-click (fn [_]
                    (let [dom (om/get-node owner)
                          title (.. dom (querySelector "[name=title]") -value)
                          content (.. dom (querySelector "[name=content]") -value)]
                      (if (->> [title content] (keep not-empty) not-empty)
                        (save-thread {:board/name (:board/name board)
                                      :thread/title title
                                      :comment/content content
                                      :user/name  (or (not-empty (.. dom (querySelector "[name=name]") -value)) "名無しさん")
                                      :user/email (or (not-empty (.. dom (querySelector "[name=email]") -value)) "sage")})
                        (js/alert "title and content are required."))))}
       [:i.icon.edit] "Create thread"]])))

(defn open-tab [owner data]
  (let [tabs (om/get-state owner :tabs)]
    (if-let [tab (->> tabs (filter #(= (:db/id data) (:id %))) first)]
      (om/set-state! owner :current-tab (:db/id data))
      (do
        (om/update-state! owner :tabs #(conj % {:id (:db/id data) :name (:thread/title data)}))
        (om/set-state! owner :current-tab (:db/id data))))))

(defcomponent board-view [board owner]
  (init-state [_]
    {:tabs [{:id 0 :name "New"}]
     :current-tab 0
     :channel (chan)})
  (will-mount [_]
    (go-loop []
      (let [[cmd data] (<! (om/get-state owner :channel))]
        (case cmd
          :open-tab (open-tab owner data))
        (recur))))
  (render-state [_ {:keys [tabs current-tab channel]}]
    (html
     [:div
      (om/build thread-list-view (:board/threads board)
                {:init-state {:board-channel channel}})
      [:div.ui.top.attached.segment
       [:div.ui.top.attached.tabular.menu
        (for [tab tabs]
          [:a.item
           (merge {:on-click (fn [_]
                               (om/set-state! owner :current-tab (:id tab)))}
                  (when (= current-tab (:id tab)) {:class "active"}))
           (:name tab)])]
       (for [tab tabs]
         [:div.ui.bottom.attached.tab.segment
          (when (= current-tab (:id tab)) {:class "active"})
          (if (= current-tab 0)
            (om/build thread-new-view board)
            (om/build thread-view (get-in board [:board/threads current-tab])))])]])))


