(ns back-channeling.components.board
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan timeout]]
            [back-channeling.api :as api]
            [back-channeling.components.avatar :refer [avatar]])
  (:use [back-channeling.comment-helper :only [format-plain]])
  (:import [goog.i18n DateTimeFormat]))

(enable-console-print!)

(def date-format-m  (DateTimeFormat. goog.i18n.DateTimeFormat.Format.MEDIUM_DATETIME
                                     (aget goog.i18n (str "DateTimeSymbols_" (.-language js/navigator)))))

(defn save-comment [comment on-success]
  (api/request (str "/api/thread/" (:thread/id comment) "/comments")
               :POST
               comment
               {:handler (fn [response]
                           (on-success response))}))
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

(defn watch-thread [thread user owner]
  (api/request (str "/api/thread/" (:db/id thread))
               :PUT
               {:add-watcher user}
               {:handler (fn [response]
                           (om/set-state! owner :watching? true))}))

(defn unwatch-thread [thread user owner]
  (api/request (str "/api/thread/" (:db/id thread))
               :PUT
               {:remove-watcher user}
               {:handler (fn [response]
                           (om/set-state! owner :watching? false))}))

(defcomponent comment-new-view [thread owner]
  (init-state [_] {:comment ""
                   :comment-format "comment.format/plain"})
  (render-state [_ {:keys [comment comment-format]}]
    (html
     [:form.ui.reply.form
      [:div.ui.equal.width.grid
       [:div.row
        [:div.column
         [:div.field
          [:textarea#comment
           {:name "comment"
            :on-change (fn [e]
                         (om/set-state! owner :comment (.. e -target -value)))} comment]]
         [:div.actions
          [:div.two.fields
           [:div.field
            [:select {:name "format"
                      :on-change (fn [e]
                                   (om/set-state! owner :comment-format (.. e -target -value)))}
             [:option {:value "comment.format/plain"} "Plain"]
             [:option {:value "comment.format/markdown"} "Markdown"]]]
           [:div.field
            [:div.ui.blue.labeled.submit.icon.button
             {:on-click (fn [_]
                          (let [dom (om/get-node owner)]
                            (save-comment {:thread/id  (:db/id thread)
                                           :comment/content (.. dom (querySelector "[name=comment]") -value)
                                           :comment/format (keyword (.. dom (querySelector "[name=format]") -value))}
                                          
                                          (fn [_] (om/set-state! owner :comment "")))))}
             [:i.icon.edit] "New comment"]]]]]
        [:div.column
         [:div.preview
          [:div.ui.top.right.attached.label "Preview"]
          [:div.attached
           (case comment-format
             "comment.format/plain" (format-plain comment) 
             "comment.format/markdown" {:dangerouslySetInnerHTML {:__html (js/marked comment)}})]]]]]])))

(defcomponent thread-view [thread owner {:keys [board-name]}]
  (render [_]
    (html
     [:div.ui.thread.comments
      [:h3.ui.dividing.header (:thread/title thread)]
      [:a {:href (str "#/curation/" board-name "/" (:db/id thread))}
       [:i.external.share.icon]]
      (for [comment (:thread/comments thread)]
        [:div.comment
         (om/build avatar (get-in comment [:comment/posted-by :user/email]))
         [:div.content
          [:a.number (:comment/no comment)] ": "
          [:a.author (get-in comment [:comment/posted-by :user/name])]
          [:div.metadata
           [:span.date (.format date-format-m (get-in comment [:comment/posted-at]))]]
          [:div.text (case (get-in comment [:comment/format :db/ident])
                       :comment.format/markdown {:dangerouslySetInnerHTML {:__html (js/marked (:comment/content comment))}}
                       (format-plain (:comment/content comment)))]]])
      (om/build comment-new-view thread)])))

(defn toggle-sort-key [owner sort-key]
  (let [[col direction] (om/get-state owner :sort-key)]
    (om/set-state! owner :sort-key
                   [sort-key
                    (if (= col sort-key)
                      (case direction :asc :desc :desc :asc)
                      :asc)])))

(defcomponent thread-watch-icon [thread owner]
  (init-state [_]
    {:hover? false})
  (render-state [_ {:keys [watching? hover? user]}]
    (html
     [:td
      {:on-click (fn [_]
                   (if watching?
                     (unwatch-thread thread user owner)
                     (watch-thread thread user owner)))
       :on-mouse-over (fn [_] (om/set-state! owner :hover? true))
       :on-mouse-out  (fn [_] (om/set-state! owner :hover? false))}
      [:i.icon {:class (case [watching? hover?]
                         [true true]   "hide red"
                         [true false]  "unhide green"
                         [false true]  "unhide green"
                         [false false] "hide grey")}]])))

(defcomponent thread-list-view [threads owner {:keys [board-name]}]
  (init-state [_]
    {:sort-key [:thread/since :desc]
     :user {:user/name  (.. js/document (querySelector "meta[property='bc:user:name']") (getAttribute "content"))
            :user/email (.. js/document (querySelector "meta[property='bc:user:email']") (getAttribute "content"))}})
  (render-state [_ {:keys [board-channel sort-key user]}]
    (html
     [:table.ui.violet.table
      [:thead
       [:tr
        [:th ""]
        [:th {:on-click (fn [_] (toggle-sort-key owner :thread/title))}
         "Title" (when (= (first sort-key) :thread/title) (case (second sort-key)
                                                            :asc  [:i.caret.up.icon]
                                                            :desc [:i.caret.down.icon]))]
        [:th {:on-click (fn [_] (toggle-sort-key owner :thread/resnum))}
         "Res" (when (= (first sort-key) :thread/resnum) (case (second sort-key)
                                                            :asc  [:i.caret.up.icon]
                                                            :desc [:i.caret.down.icon]))]
        [:th {:on-click (fn [_] (toggle-sort-key owner :thread/since))}
         "Since" (when (= (first sort-key) :thread/since) (case (second sort-key)
                                                            :asc  [:i.caret.up.icon]
                                                            :desc [:i.caret.down.icon]))]]]
      [:tbody
       (for [thread (->> (vals threads)
                        (sort-by (first sort-key) (case (second sort-key)
                                                    :asc < :desc >))) ]
         [:tr
          (om/build thread-watch-icon thread {:init-state {:watching? (boolean ((:thread/watchers thread) (:user/name user)))
                                                           :user user}})
          [:td
           [:a {:href (str "#/board/" board-name "/" (:db/id thread))}
            (:thread/title thread)]]
          [:td (:thread/resnum thread)]
          [:td (.format date-format-m (:thread/since thread))]])]])))


(defcomponent thread-new-view [board owner]
  (init-state [_] {:comment ""
                   :comment-format "comment.format/plain"})
  (render-state [_ {:keys [comment comment-format]}]
    (html
     [:form.ui.reply.form
      [:div.ui.equal.width.grid
       [:div.row
        [:div.column
         [:div.field
          [:label "Title"]
          [:input {:type "text" :name "title"}]]
         [:div.field
          [:textarea {:name "comment"
                      :on-change (fn [e]
                                   (om/set-state! owner :comment (.. e -target -value)))}]]
         [:div.actions
          [:div.two.fields
           [:div.field
            [:select {:name "format"
                      :on-change (fn [e]
                                   (om/set-state! owner :comment-format (.. e -target -value)))}
             [:option {:value "comment.format/plain"} "Plain"]
             [:option {:value "comment.format/markdown"} "Markdown"]]]
           [:div.field
            [:div.ui.blue.labeled.submit.icon.button
             {:on-click (fn [_]
                          (let [dom (om/get-node owner)
                                title (.. dom (querySelector "[name=title]") -value)]
                            (if (->> [title comment] (keep not-empty) not-empty)
                              (save-thread {:board/name (:board/name board)
                                            :thread/title title
                                            :comment/content comment})
                              (js/alert "title and content are required."))))}
             [:i.icon.edit] "Create thread"]]]]]
        [:div.column
         [:div.preview
          [:div.ui.top.right.attached.label "Preview"]
          [:div
           (case comment-format
             "comment.format/plain" (format-plain comment)
             "comment.format/markdown" {:dangerouslySetInnerHTML {:__html (js/marked comment)}})]]]]]])))

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
     :current-tab 0})
  (will-mount [_]
    (go-loop []
      (let [[cmd data] (<! (om/get-state owner :channel))]
        (case cmd
          :open-tab (open-tab owner data)
          :open-thread (open-thread (get-in board [:board/threads data])
                                    (om/get-state owner :channel)))
        (recur))))
  (render-state [_ {:keys [tabs current-tab channel]}]
    (html
     [:div.main.content
      (om/build thread-list-view (:board/threads board)
                {:init-state {:board-channel channel}
                 :opts {:board-name (:board/name board)}})
      [:div.ui.top.attached.segment
       [:div.ui.top.attached.tabular.menu
        (for [tab tabs]
          [:a.item
           (merge {:on-click (fn [_]
                               (om/set-state! owner :current-tab (:id tab)))}
                  (when (= current-tab (:id tab)) {:class "active"}))
           (:name tab)
           (when (not= (:id tab) 0)
             [:span
            [:i.close.icon {:on-click (fn [e]
                                        (om/update-state! owner :tabs (fn [tabs] (vec (remove #(= (:id %) (:id tab)) tabs))))
                                        (when (= (om/get-state owner :current-tab) (:id tab))
                                          (om/update-state! owner :current-tab 0))
                                        (.stopPropagation e))}]])])]
       (for [tab tabs]
         [:div.ui.bottom.attached.tab.segment
          (when (= current-tab (:id tab)) {:class "active"})
          (if (= current-tab 0)
            (om/build thread-new-view board)
            (om/build thread-view (get-in board [:board/threads current-tab])
                      {:opts {:board-name (:board/name board)}}))])]])))


