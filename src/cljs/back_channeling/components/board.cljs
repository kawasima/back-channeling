(ns back-channeling.components.board
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan timeout]]
            [back-channeling.api :as api]
            [back-channeling.components.avatar :refer [avatar]])
  (:use [back-channeling.comment-helper :only [format-plain]]
        [back-channeling.component-helper :only [make-click-outside-fn]])
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
               {:handler (fn [response]
                           (set! (.-href js/location) (str "#/board/" (:board/name thread) "/" (:db/id response))))}))

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
                   :comment-format "comment.format/plain"
                   :focus? false
                   :click-outside-fn nil})

  (will-mount [_]
    (when-let [on-click-outside (om/get-state owner :click-outside-fn)]
      (.removeEventListener js/document "mousedown" on-click-outside)))
  (did-mount [_]
    (when-not (om/get-state owner :click-outside-fn)
      (om/set-state! owner :click-outside-fn
                   (make-click-outside-fn
                    (om/get-node owner)
                    #(om/set-state! owner :focus? false))))
    (.addEventListener js/document "mousedown"
                       (om/get-state owner :click-outside-fn)))
  (did-update [_ _ _]
    (if (om/get-state owner :focus?)
      (.. (om/get-node owner) (querySelector "textarea") focus)))
  (render-state [_ {:keys [comment comment-format focus?]}]
    (html
     [:form.ui.reply.form {:on-submit (fn [e] (.preventDefault e))}
      [:div.ui.equal.width.grid
       (if focus?
         [:div.row
          [:div.column
           [:div.field
            [:textarea#comment
             {:name "comment"
              :value comment
              :on-change (fn [e]
                           (om/set-state! owner :comment (.. e -target -value)))
              :on-key-up (fn [e]
                           (when (and (= (.-which e) 0x0d) (.-ctrlKey e))
                             (let [btn (.. (om/get-node owner) (querySelector "button.submit.button"))]
                               (.click btn))))}]]
           [:div.actions
            [:div.two.fields
             [:div.field
              [:select {:name "format"
                        :on-change (fn [e]
                                     (om/set-state! owner :comment-format (.. e -target -value)))}
               [:option {:value "comment.format/plain"} "Plain"]
               [:option {:value "comment.format/markdown"} "Markdown"]]]
             [:div.field
              [:button.ui.blue.labeled.submit.icon.button
               {:on-click (fn [e]
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
               "comment.format/markdown" {:dangerouslySetInnerHTML {:__html (js/marked comment)}})]]]]
         [:div.row
          [:div.column
           [:div.ui.left.icon.input.field
            [:i.edit.icon]
            [:input {:type "text" :value comment :on-focus (fn [_] (om/set-state! owner :focus? true))}]]]])]])))

(defn scroll-to-comment [owner thread]
  (let [comment-no (or (om/get-state owner :target-comment) (count (:thread/comments thread))) 
        comment-dom (.. (om/get-node owner)
                        (querySelector (str "[data-comment-no='" comment-no "']")))
        scroll-pane (.. (om/get-node owner)
                           (querySelector "div.scroll-pane"))]
      (when comment-dom
        (set! (.-scrollTop scroll-pane) (some->> (.getBoundingClientRect comment-dom) (.-top))))))

(defcomponent thread-view [thread owner {:keys [board-name]}]
  (did-mount [_]
    (scroll-to-comment owner thread))
  (did-update [_ _ _]
    (scroll-to-comment owner thread))
  (render [_]
    (html
     [:div.ui.thread.comments
      [:h3.ui.dividing.header (:thread/title thread)]
      [:a.curation.link {:href (str "#/curation/" board-name "/" (:db/id thread))}
       [:i.external.share.big.icon]]
      [:div.scroll-pane
       (for [comment (:thread/comments thread)]
        [:div.comment {:data-comment-no (:comment/no comment)}
         (om/build avatar (get-in comment [:comment/posted-by :user/email]))
         [:div.content
          [:a.number (:comment/no comment)] ": "
          [:a.author (get-in comment [:comment/posted-by :user/name])]
          [:div.metadata
           [:span.date (.format date-format-m (get-in comment [:comment/posted-at]))]]
          [:div.text (case (get-in comment [:comment/format :db/ident])
                       :comment.format/markdown {:dangerouslySetInnerHTML {:__html (js/marked (:comment/content comment))}}
                       (format-plain (:comment/content comment)))]]])]
      (if (> (count (:thread/comments thread)) 1000)
        [:div.ui.error.message
         [:div.header "Over 1000 comments. You can't add any comment to this thread."]]
        (om/build comment-new-view thread))])))

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
    {:sort-key [:thread/last-updated :desc]
     :user {:user/name  (.. js/document (querySelector "meta[property='bc:user:name']") (getAttribute "content"))
            :user/email (.. js/document (querySelector "meta[property='bc:user:email']") (getAttribute "content"))}})
  (render-state [_ {:keys [board-channel sort-key user]}]
    (html
     [:div.table.container
      [:div.tbody.container
       [:table.ui.table
        [:thead
         [:tr
          [:th ""]
          [:th {:on-click (fn [_] (toggle-sort-key owner :thread/title))}
           "Title" [:div "Title " (when (= (first sort-key) :thread/title) (case (second sort-key)
                                                                             :asc  [:i.caret.up.icon]
                                                                             :desc [:i.caret.down.icon]))]]
          [:th {:on-click (fn [_] (toggle-sort-key owner :thread/resnum))}
           "Res"
           [:div "Res" (when (= (first sort-key) :thread/resnum) (case (second sort-key)
                                                          :asc  [:i.caret.up.icon]
                                                          :desc [:i.caret.down.icon]))]]
          [:th {:on-click (fn [_] (toggle-sort-key owner :thread/last-updated))}
           "Last updated"
           [:div "Last updated"
            (when (= (first sort-key) :thread/last-updated) (case (second sort-key)
                                                              :asc  [:i.caret.up.icon]
                                                              :desc [:i.caret.down.icon]))]]
          [:th {:on-click (fn [_] (toggle-sort-key owner :thread/since))}
           "Since"
           [:div "Since"
            (when (= (first sort-key) :thread/since) (case (second sort-key)
                                                              :asc  [:i.caret.up.icon]
                                                              :desc [:i.caret.down.icon]))]]]]
        [:tbody
         (for [thread (->> (vals threads)
                           (map #(if (:thread/watchers %) % (assoc % :thread/watchers #{})))
                           (sort-by (first sort-key) (case (second sort-key)
                                                       :asc < :desc >)))]
           [:tr
            (om/build thread-watch-icon thread {:init-state {:watching? (boolean ((:thread/watchers thread) (:user/name user)))
                                                             :user user}})
            [:td
             [:a {:href (str "#/board/" board-name "/" (:db/id thread))}
              (:thread/title thread)]]
            [:td (:thread/resnum thread)]
            [:td (.format date-format-m (:thread/last-updated thread))]
            [:td (.format date-format-m (:thread/since thread))]])]]]])))

(defcomponent thread-new-view [board owner]
  (init-state [_] {:comment ""
                   :title ""
                   :comment-format "comment.format/plain"})
  (render-state [_ {:keys [comment comment-format title]}]
    (html
     [:form.ui.reply.form {:on-submit (fn [e] (.preventDefault e))}
      [:div.ui.equal.width.grid
       [:div.row
        [:div.column
         [:div.field
          [:label "Title"]
          [:input {:type "text" :name "title" :value title
                   :on-change (fn [e] (om/set-state! owner :title (.. e -target -value)))}]]
         [:div.field
          [:textarea {:name "comment"
                      :value comment
                      :on-change (fn [e]
                                   (om/set-state! owner :comment (.. e -target -value)))
                      :on-key-up (fn [e]
                                   (when (and (= (.-which e) 0x0d) (.-ctrlKey e))
                                     (let [btn (.. (om/get-node owner) (querySelector "button.submit.button"))]
                                       (.click btn))))}]]
         [:div.actions
          [:div.two.fields
           [:div.field
            [:select {:name "format"
                      :on-change (fn [e]
                                   (om/set-state! owner :comment-format (.. e -target -value)))}
             [:option {:value "comment.format/plain"} "Plain"]
             [:option {:value "comment.format/markdown"} "Markdown"]]]
           [:div.field
            [:button.ui.blue.labeled.submit.icon.button
             {:on-click (fn [_]
                          (let [dom (om/get-node owner)
                                title (.. dom (querySelector "[name=title]") -value)]
                            (if (->> [title comment] (keep not-empty) not-empty)
                              (do (save-thread {:board/name (:board/name @board)
                                                :thread/title title
                                                :comment/content comment})
                                  (om/update-state! owner #(assoc % :comment "" :title "")))
                              (js/alert "title and content are required."))))}
             [:i.icon.edit] "Create thread"]]]]]
        [:div.column
         [:div.preview
          [:div.ui.top.right.attached.label "Preview"]
          [:div
           (case comment-format
             "comment.format/plain" (format-plain comment)
             "comment.format/markdown" {:dangerouslySetInnerHTML {:__html (js/marked comment)}})]]]]]])))


(defcomponent board-view [board owner]
  (init-state [_]
    {:tabs [{:id 0 :name "New"}]})
  
  (render-state [_ {:keys [tabs target-thread target-comment channel]}]
    (if (->> tabs (filter #(= target-thread (:id %))) empty?)
        (om/update-state! owner :tabs #(conj % {:id target-thread :name (get-in board [:board/threads target-thread :thread/title])})))
    (html
     [:div.main.content
      (om/build thread-list-view (:board/threads board)
                {:init-state {:board-channel channel}
                 :opts {:board-name (:board/name board)}})
      [:div.ui.top.attached.segment
       [:div.ui.top.attached.tabular.menu
        (for [tab tabs]
          [:a.item (merge {:on-click (fn [_]
                               (set! (.-href js/location)
                                     (if (= (:id tab) 0)
                                       "#/"
                                       (str "#/board/" (:board/name board) "/" (:id tab)))))}
                          (when (= target-thread (:id tab)) {:class "active"})) 
           [:span (:name tab)] 
           (when (not= (:id tab) 0)
             [:span
              [:i.close.icon {:on-click (fn [e]
                                          (om/update-state! owner #(assoc %
                                                                          :tabs (vec (remove (fn [t] (= (:id t) (:id tab))) (:tabs %)))
                                                                          :target-thread (if (= (:target-thread %) (:id tab)) 0 (:target-thread %))) )
                                          (when (= target-thread (:id tab))
                                            (set! (.. js/location -href) "#/"))
                                          (.stopPropagation e))}]])])]
       (for [tab tabs]
         [:div.ui.bottom.attached.tab.segment
          (when (= target-thread (:id tab)) {:class "active"})
          (if (= target-thread 0)
            (om/build thread-new-view board)
            (om/build thread-view (get-in board [:board/threads target-thread])
                      {:state {:target-comment target-comment}
                       :opts {:board-name (:board/name board)}}))])]])))


