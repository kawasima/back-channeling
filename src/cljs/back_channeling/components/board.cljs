(ns back-channeling.components.board
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [sablono.core :as html :refer-macros [html]]
            [cljs.core.async :refer [put! <! chan timeout]]
            [medley.core :as m]
            [bouncer.core :as b]
            [bouncer.validators :as v]
            [back-channeling.api :as api]
            [back-channeling.notification :as notification]
            [back-channeling.audio :as audio]
            [back-channeling.components.avatar :refer [avatar]]
            [back-channeling.components.comment :refer [comment-view]]
            [back-channeling.helper :refer [find-thread]]
            [back-channeling.comment-helper :refer [format-plain]]
            [back-channeling.component-helper :refer [make-click-outside-fn]]
            [goog.i18n.DateTimeSymbols_ja])
  (:import [goog.i18n DateTimeFormat]))

(enable-console-print!)

(def date-format-m  (DateTimeFormat. goog.i18n.DateTimeFormat.Format.MEDIUM_DATETIME
                                     (aget goog.i18n (str "DateTimeSymbols_" (.-language js/navigator)))))

(defn save-board [board]
  (api/request "/api/boards"
               :POST
               board
               {:handler (fn [response]
                           (set! (.-href js/location) (str "#/board/" (:board/name board))))}))

(defn save-comment [board-name comment on-success]
  (if (= (:comment/format comment) :comment.format/voice)
    (let [blob (:comment/content comment)]
      (api/request (str "/api/board/" board-name "/thread/" (:thread/id comment) "/voices")
                   :POST
                   blob
                   {:format (case (.-type blob)
                              "audio/webm" :webm
                              "audio/ogg"  :ogg
                              "audio/wav"  :wav)
                    :handler (fn [response]
                               (api/request (str "/api/board/" board-name "/thread/" (:thread/id comment) "/comments")
                                            :POST
                                            (merge comment response)
                                            {:handler #(on-success %)}))}))
    (api/request (str "/api/board/" board-name "/thread/" (:thread/id comment) "/comments")
               :POST
               comment
               {:handler (fn [response]
                           (on-success response))})))

(defn watch-thread [board-name thread user owner]
  (api/request (str "/api/board/" board-name "/thread/" (:db/id thread))
               :PUT
               {:add-watcher user}
               {:handler (fn [response]
                           (put! (om/get-shared owner :msgbox) [:watch-thread {:thread thread :board/name board-name}])
                           (om/set-state! owner :watching? true))})
  (notification/initialize))

(defn unwatch-thread [board-name thread user owner]
  (api/request (str "/api/board/" board-name "/thread/" (:db/id thread))
               :PUT
               {:remove-watcher user}
               {:handler (fn [response]
                           (put! (om/get-shared owner :msgbox) [:watch-thread {:thread thread :board/name board-name}])
                           (om/set-state! owner :watching? false))}))

(defn comment-new-view [{:keys [app thread]} owner]
  (reify
    om/IInitState
    (init-state [_]
      {:comment {:comment/content ""
                 :comment/format "comment.format/plain"
                 :thread/id (when thread (:db/id @thread)) }
       :focus? false
       :saving? false
       :recording-status :none
       :click-outside-fn nil})

    om/IWillMount
    (will-mount [_]
      (when-let [on-click-outside (om/get-state owner :click-outside-fn)]
        (.removeEventListener js/document "mousedown" on-click-outside)))

    om/IDidMount
    (did-mount [_]
      (when-not (om/get-state owner :click-outside-fn)
        (om/set-state! owner :click-outside-fn
                       (make-click-outside-fn
                        (om/get-node owner)
                        (fn [_]
                          (om/set-state! owner :focus? false)))))

      (.addEventListener js/document "mousedown"
                         (om/get-state owner :click-outside-fn)))

    om/IDidUpdate
    (did-update [_ _ _]
      (when-let [textarea (.. (om/get-node owner) (querySelector "textarea"))]
        (if (om/get-state owner :focus?)
          (.focus textarea))))

    om/IRenderState
    (render-state [_ {:keys [comment focus? saving? error-map recording-status audio-url]}]
    (html
     [:form.ui.reply.form {:on-submit (fn [e] (.preventDefault e))}
      [:div.ui.equal.width.grid
       (if focus?
         [:div.row
          [:div.column
           [:div.field (when (:comment/content error-map)
                         {:class "error"})
            (if  (= (:comment/format comment) "comment.format/voice")
              (case recording-status
                :recording
                [:button.ui.large.red.circular.button
                 {:on-click (fn [e]
                              (letfn [(update-content [blob]
                                        (om/update-state!
                                         owner
                                         #(-> %
                                              (assoc :recording-status :none)
                                              (assoc-in [:comment :comment/content] blob))))]
                                (audio/stop-recording
                                 (fn [blob]
                                   (om/set-state! owner :recording-status :encoding)
                                   (if (#{"audio/wav"} (.-type blob))
                                     (audio/wav->ogg blob update-content)
                                     (update-content blob))))))}
                 [:i.large.stop.icon] "Stop"]

                :encoding
                [:button.ui.large.red.circular.disable.button
                 [:i.large.mute.icon] "Stopping..."]

                :none
                [:button.ui.large.basic.circular.red.button
                 {:on-click (fn [e]
                              (audio/start-recording)
                              (om/set-state! owner :recording-status :recording))}
                 [:i.large.unmute.icon] "Record"]))
            [:textarea
             (merge {:name "comment"
                     :value (:comment/content comment)
                     :on-change (fn [e]
                                  (when-not (= (om/get-state owner [:comment :comment/format]) "comment.format/voice")
                                    (om/set-state! owner [:comment :comment/content] (.. e -target -value))))
                     :on-key-up (fn [e]
                                  (when (and (= (.-which e) 0x0d) (.-ctrlKey e))
                                    (let [btn (.. (om/get-node owner) (querySelector "button.submit.button"))]
                                      (.click btn))))}
                    (when (= (:comment/format comment) "comment.format/voice")
                      {:style {:display "none"}}))]]
           [:div.actions
            [:div.two.fields
             [:div.field
              [:select {:name "format"
                        :value (:comment/format comment)
                        :on-change (fn [e]
                                     (om/set-state! owner [:comment :comment/format] (.. e -target -value)))}
               [:option {:value "comment.format/plain"} "Plain"]
               [:option {:value "comment.format/markdown"} "Markdown"]
               (when (audio/audio-available?)
                [:option {:value "comment.format/voice"} "Voice"])]]
             [:div.field
              [:button.ui.blue.labeled.submit.icon.button
               (merge {:on-click (fn [e]
                            (let [comment (om/get-state owner :comment)
                                  [result map] (b/validate comment
                                                           :comment/content v/required)]
                              (if result
                                (om/set-state! owner :error-map (:bouncer.core/errors map))
                                (do
                                  (om/set-state! owner :saving? true)
                                  (save-comment (get-in app [:board :board/name])
                                                (update-in comment [:comment/format] keyword)
                                                (fn [_]
                                                  (om/update-state!
                                                   owner
                                                   #(-> %
                                                        (assoc-in [:comment :comment/content] "")
                                                        (assoc :saving? false)))))))))}
                      (when saving?
                        {:class "loading"}))

               [:i.icon.edit] "New comment"]]]]]
          [:div.column
           [:div.preview
            [:div.ui.top.right.attached.label "Preview"]
            (case (:comment/format comment)
              "comment.format/plain"
              [:div.attached (format-plain (:comment/content comment) :board-name (get-in app [:board :board/name]) :thread-id (:db/id thread))]

              "comment.format/markdown"
              [:div.attached {:key "preview-markdown"
                              :dangerouslySetInnerHTML {:__html (.render js/md (:comment/content comment))}}]

              "comment.format/voice"
              [:div.attached
               (let [content (:comment/content comment)]
                 (when (instance? js/Blob content)
                   [:audio {:controls true
                            :src (.createObjectURL js/URL content)}]))])]]]
         [:div.row
          [:div.column
           [:div.ui.left.icon.input.field
            [:i.edit.icon]
            [:input {:type "text" :value (:comment/content comment) :on-focus (fn [_] (om/set-state! owner :focus? true))}]]]])]]))))

(defn- find-element [orig-el attr-name]
  (loop [el orig-el]
    (if (.hasAttribute el attr-name)
      el
      (when-let [parent (.-parentNode el)]
        (recur parent)))))

(defn thread-view [{:keys [app thread] :as state} owner]
  (reify
    om/IInitState
    (init-state [_]
      {:reaction-top 0
       :open-reactions? false
       :selected 0
       :open-menu? false})

    om/IWillMount
    (will-mount [_]
      (when-let [on-click-outside (om/get-state owner :click-outside-fn)]
        (.removeEventListener js/document "mousedown" on-click-outside)))

    om/IDidMount
    (did-mount [_]
      (put! (om/get-shared owner :msgbox) [:scroll-to-comment {:comment/no (or (get-in app [:page :comment/no]) (count (:thread/comments thread)))}])
      (when-not (om/get-state owner :click-outside-fn)
        (om/set-state! owner :click-outside-fn
                       (make-click-outside-fn
                        (.. (om/get-node owner) (querySelector "div.reactions.segment"))
                        (fn [_]
                          (om/set-state! owner :open-reactions? false)))))
      (.addEventListener js/document "mousedown"
                         (om/get-state owner :click-outside-fn)))

    om/IRenderState
    (render-state [_ {:keys [reaction-top selected open-reactions? open-menu?]}]
      (html
       [:div.ui.full.height.thread.comments
        [:div.ui.secondary.pointing.menu
         [:div.item
          [:h3.ui.header
           (-> (get-in app [:board :board/threads
                            (find-thread (get-in app [:board :board/threads]) (:db/id thread))])
               :thread/title)]]
         [:div.right.item {:on-click (fn [_]
                                       (om/set-state! owner :open-menu? (not open-menu?)))}
          [:i.chevron.icon {:class (if open-menu? "up" "down")}]]
         [:div.ui.popup.bottom.right.transition {:class (if open-menu? "visible" "hidden")}
          [:div.ui.two.column.grid
          ;  [:div.row
          ;   [:div.column.left.aligned "curation"]
          ;   [:div.column
          ;    [:a.ui.curation {:href (str "#/articles/new?thread-id=" (:db/id thread))}
          ;     [:i.external.share.large.icon]]]]
           [:div.one.column.row [:div.ui.divider.column]]
           (let [threads (get-in app [:board :board/threads])
                 public? (get-in threads [(find-thread threads (:db/id thread)) :thread/public?])]
             [:div.row
              [:div.column.left.aligned "public"]
              [:div.ui.toggle.checkbox.column
               [:input (merge {:type "checkbox"
                               :on-click (fn [e]
                                           (.preventDefault e)
                                           (put! (om/get-shared owner :msgbox)
                                                 (if public?
                                                   [:close-thread {:thread/id (:db/id thread)
                                                                   :board/name (get-in app [:board :board/name])}]
                                                   [:open-thread {:thread/id (:db/id thread)
                                                                  :board/name (get-in app [:board :board/name])}])))}
                              (when public? {:checked "checked"})
                              (when-not (get-in app [:board :user/permissions :read-any-thread])
                                           {:disabled "disabled"}))]
               [:label]]])]]]
        [:div.scroll-pane
         [:div.ui.icon.reaction.buttons {:style {:top reaction-top}}
          [:button.ui.button {:on-click (fn [e]
                                          (om/set-state! owner :open-reactions? true))}
           [:i.smile.icon]]
          [:button.ui.button {:on-click (fn [e]
                                          (put! (om/get-shared owner :msgbox)
                                                [:delete-comment {:board/name (get-in app [:board :board/name])
                                                                  :thread/id (:db/id thread)
                                                                  :comment/no selected}]))}
           [:i.remove.icon]]]
         [:div.ui.reactions.raised.segment
          {:style (if open-reactions?
                    {:visibility 'visible
                     :top reaction-top}
                    {:visibility 'hidden})}

          [:div.ui.grid.container
           (for [reaction (:reactions app)]
             [:.four.wide.column {:key (str "reaction-" (:db/id reaction))}
              [:button.ui.tiny.basic.button
               {:on-click (fn [e]
                            (api/request (str "/api/board/" (get-in app [:board :board/name])
                                              "/thread/" (:db/id thread)
                                              "/comment/" selected)
                                         :POST
                                         (select-keys reaction [:reaction/name])
                                         {:handler (fn [e]
                                                     (om/set-state! owner :open-reactions? false))}))}
               (:reaction/label reaction)]])]]

         (for [comment (:thread/comments thread)]
           (om/build comment-view {:app app :comment comment :thread thread}
                     {:opts {:comment-attrs
                             {:on-mouse-enter
                              (fn [e]
                                (let [el (find-element (.-target e) "data-comment-no")
                                      btn (.querySelector (om/get-node owner) ".ui.reaction.buttons")]
                                  (when-not (om/get-state owner :open-reactions?)
                                    (om/set-state! owner
                                                   {:reaction-top
                                                    (.-offsetTop el)
                                                    :selected (:comment/no comment)}))))}
                             :show-reactions? true}
                      :react-key (str "comment-" (:comment/no comment))}))]
        (if (>= (count (:thread/comments thread)) 1000)
          [:div.ui.error.message
           [:div.header "Over 1000 comments. You can't add any comment to this thread."]]
          (om/build comment-new-view state))]))))

(defn toggle-sort-key [owner sort-key]
  (let [[col direction] (om/get-state owner :sort-key)]
    (om/set-state! owner :sort-key
                   [sort-key
                    (if (= col sort-key)
                      (case direction :asc :desc :desc :asc)
                      :asc)])))

(defn thread-watch-icon [thread owner {:keys [board-name]}]
  (reify
    om/IInitState
    (init-state [_]
      {:hover? false})

    om/IRenderState
    (render-state [_ {:keys [watching? hover? user]}]
      (html
       [:td.collapsing
        {:on-click (fn [_]
                     (if watching?
                       (unwatch-thread board-name thread user owner)
                       (watch-thread board-name thread user owner)))
         :on-mouse-over (fn [_] (om/set-state! owner :hover? true))
         :on-mouse-out  (fn [_] (om/set-state! owner :hover? false))}
        [:i.icon {:class (case [watching? hover?]
                           [true true]   "hide red"
                           [true false]  "unhide green"
                           [false true]  "unhide green"
                           [false false] "hide grey")}]]))))


(defn thread-list-view [board owner]
  (reify
    om/IInitState
    (init-state [_]
      {:sort-key [:thread/last-updated :desc]
       :filters {:watching? false :writing? false}
       :user {:user/name  (.. js/document (querySelector "meta[property='bc:user:name']") (getAttribute "content"))
              :user/email (.. js/document (querySelector "meta[property='bc:user:email']") (getAttribute "content"))}})

    om/IRenderState
    (render-state [_ {:keys [filters sort-key user]}]
      (html
       [:div.table.container
        [:div.tbody.container
         [:table.ui.unstackable.compact.table
          [:thead
           [:tr
            [:th {:on-click (fn [_]
                              (om/update-state! owner [:filters :watching?] not))}
             [:div
              [:i.large.icons
                [:i.icon.unhide {:class (if (:watching? filters) "green" "disabled")}]
                [:i.icon.corner.filter {:class (if (:watching? filters) "teal" "disabled")}]]]]
            [:th {:on-click (fn [_]
                              (om/update-state! owner [:filters :writing?] not))}
             [:div
              [:i.large.icons
               [:i.icon.comments.outline {:class (if (:writing? filters) "green" "disabled")}]
               [:i.icon.corner.filter {:class (if (:writing? filters) "teal" "disabled")}]]]]
            [:th {:on-click (fn [_] (toggle-sort-key owner :thread/title))}
             [:div "Title " (when (= (first sort-key) :thread/title)
                              (case (second sort-key)
                                :asc  [:i.caret.up.icon]
                                :desc [:i.caret.down.icon]))]]
            [:th {:on-click (fn [_] (toggle-sort-key owner :thread/resnum))}
             [:div "Res" (when (= (first sort-key) :thread/resnum)
                           (case (second sort-key)
                             :asc  [:i.caret.up.icon]
                             :desc [:i.caret.down.icon]))]]
            [:th {:on-click (fn [_] (toggle-sort-key owner :thread/last-updated))}
             [:div "Last updated"
              (when (= (first sort-key) :thread/last-updated)
                (case (second sort-key)
                  :asc  [:i.caret.up.icon]
                  :desc [:i.caret.down.icon]))]]
            [:th {:on-click (fn [_] (toggle-sort-key owner :thread/since))}
             [:div "Since"
              (when (= (first sort-key) :thread/since)
                (case (second sort-key)
                  :asc  [:i.caret.up.icon]
                  :desc [:i.caret.down.icon]))]]]]
          [:tbody
           (for [thread (->> (:board/threads board)
                             (filter #(and (or (:thread/public? %)
                                               (> (:thread/writenum %) 0)
                                               (when-let [permissions (:user/permissions board)]
                                                 (:read-any-thread permissions)))
                                           (if (:watching? filters) ((:thread/watchers %) user) true)
                                           (if (:writing? filters) (> (:thread/writenum %) 0) true)))
                             (map #(if (:thread/watchers %) % (assoc % :thread/watchers #{})))
                             (sort-by (first sort-key) (case (second sort-key)
                                                         :asc < :desc >)))]
             [:tr (merge {:key (str "t-" (:db/id thread))}
                         (when (> (:thread/resnum thread) (:thread/readnum thread))
                           {:class "unread"}))
              (om/build thread-watch-icon thread
                        {:init-state
                         {:watching? (boolean ((:thread/watchers thread) user))
                          :user user}
                         :opts {:board-name (:board/name board)}})

              [:td.collapsing
                [:i.icon.comments.outline
                {:class (if (> (:thread/writenum thread) 0) "green" "disabled")}


                ]
                ]

              [:td.selectable
               [:a {:href (str "#/board/" (:board/name board) "/" (:db/id thread))}
                (when-not (:thread/public? thread) [:i.icon.lock])
                (:thread/title thread)]]
              [:td (:thread/resnum thread)]
              [:td (.format date-format-m (:thread/last-updated thread))]
              [:td (.format date-format-m (:thread/since thread))]])]]
              ]
              ]
              ))))

(defn thread-new-view [board owner]
  (reify
    om/IInitState
    (init-state [_] {:thread {:thread/title ""
                              :comment/content ""
                              :comment/format "comment.format/plain"}})

    om/IRenderState
    (render-state [_ {:keys [thread error-map]}]
      (html
       [:form.ui.reply.form {:on-submit (fn [e] (.preventDefault e))}
        [:div.ui.equal.width.grid
         [:div.row
          [:div.column
           [:div.field (when (:thread/title error-map) {:class "error"})
            [:label "Title"]
            [:input {:type "text" :name "title" :value (:thread/title thread)
                     :on-change (fn [e] (om/set-state! owner [:thread :thread/title] (.. e -target -value)))}]]
           [:div.field (when (:comment/content error-map) {:class "error"})
            [:textarea {:name "comment"
                        :value (:comment/content thread)
                        :on-change (fn [e]
                                     (om/set-state! owner [:thread :comment/content] (.. e -target -value)))
                        :on-key-up (fn [e]
                                     (when (and (= (.-which e) 0x0d) (.-ctrlKey e))
                                       (let [btn (.. (om/get-node owner) (querySelector "button.submit.button"))]
                                         (.click btn))))}]]
           [:div.actions
            [:div.two.fields
             [:div.field
              [:select {:name "format"
                        :on-change (fn [e]
                                     (om/set-state! owner [:thread :comment/format] (.. e -target -value)))}
               [:option {:value "comment.format/plain"} "Plain"]
               [:option {:value "comment.format/markdown"} "Markdown"]]]
             [:div.field
              [:button.ui.blue.labeled.submit.icon.button
               {:on-click (fn [_]
                            (let [[result map] (b/validate thread
                                                           :thread/title v/required
                                                           :comment/content v/required)]
                              (if result
                                (om/set-state! owner :error-map (:bouncer.core/errors map))
                                (do (put! (om/get-shared owner :msgbox) [:save-thread {:thread thread :board board}])
                                    (om/update-state! owner [:thread]
                                                      #(assoc %
                                                              :comment/content ""
                                                              :thread/title ""))))))}
               [:i.icon.edit] "Create thread"]]]]]
          [:div.column
           [:div.preview
            [:div.ui.top.right.attached.label "Preview"]
            [:div
             (case (:comment/format thread)
               "comment.format/plain"
               (format-plain (:comment/content thread))

               "comment.format/markdown"
               {:dangerouslySetInnerHTML {:__html (.render js/md (:comment/content thread))}})]]]]]]))))


(defn sticky-thread-content-fn [owner]
  (let [thread-content (.. (om/get-node owner) (querySelector "div.thread.content"))]
    (fn [e]

      (if (< (.. thread-content getBoundingClientRect -top) 70)
        (om/set-state! owner :sticky-thread-content? true)
        (om/set-state! owner :sticky-thread-content? false)))))

(defn board-view [app owner]
  (reify
    om/IInitState
    (init-state [_]
      {:tabs []
       :sticky-thread-content? false})

    om/IDidMount
    (did-mount [_]
      (.addEventListener js/window "scroll"
                         (sticky-thread-content-fn owner)))

    om/IRenderState
    (render-state [_ {:keys [tabs sticky-thread-content?]}]
      (let [threads (:threads app)
            board (:board app)]
        (doseq [th (vals threads)]
          (when-not (not-empty (filter #(= (:db/id th) (:id %)) tabs))
            (om/update-state!
             owner :tabs
             (fn [tabs]
               (conj tabs {:id (:db/id th)
                           :name (->> (:board/threads board)
                                      (filter #(= (:db/id th) (:db/id %)))
                                      first
                                      :thread/title)})) )))

        (html
         [:div.main.content.full.height
          (om/build thread-list-view board)
          [:div.ui.top.attached.thread.content.segment
           [:div.ui.top.attached.tabular.sticky.menu
            (when sticky-thread-content? {:class "fixed"})
            [:a.item (merge {:on-click (fn [_]
                                         (set! (.-href js/location)
                                               (str "#/board/" (:board/name board))))}
                            (when-not (get-in app [:page :thread/id])
                              {:class "active"}))
             [:span.tab-name "New"]]
            (for [{thread-id :db/id comments :thread/comments} (vals threads)]
              [:a.item (merge {:key (str "tab-" thread-id)
                               :on-click (fn [_]
                                           (set! (.-href js/location)
                                                 (str "#/board/" (:board/name board) "/" thread-id))
                                           (put! (om/get-shared owner :msgbox) [:scroll-to-comment {:comment/no (count comments)}]))}
                              (when (= (get-in app [:page :thread/id]) thread-id)
                                {:class "active"}))
               [:span.tab-name
                (let [threads (get-in app [:board :board/threads])
                      thread (get threads (find-thread threads thread-id))]
                  [:p (when (< (:thread/readnum thread) (:thread/resnum thread))
                        [:i.icon.asterisk.green])
                      (:thread/title thread)])]
               [:span
                [:i.close.icon
                 {:on-click (fn [e]
                              (put! (om/get-shared owner :msgbox)
                                    [:remove-thread {:thread/id thread-id
                                                     :board/name (get-in app [:board :board/name])}])
                              (.stopPropagation e))}]]])]
           [:div.ui.bottom.attached.tab.full.height.segment
            (when-not (get-in app [:page :thread/id]) {:class "active"})
            (om/build thread-new-view board)]
           (for [{thread-id :db/id :as thread} (vals threads)]
             [:div.ui.bottom.attached.tab.full.height.segment
              (merge {:key (str "tab-content-" thread-id)}
                     (when (= (get-in app [:page :thread/id]) thread-id)
                       {:class "active"}))
              (om/build thread-view {:app app :thread thread})])]])))))

(defn boards-view [app owner]
  (reify
    om/IInitState
    (init-state [_] {:board {:board/name ""
                             :board/description ""}})

    om/IRenderState
    (render-state [_ {:keys [board error-map]}]
      (html
       [:div.main.content.full.height
        [:div.ui.cards
         (for [b (filter #(let [permissions (:user/permissions %)]
                            (or (nil? permissions) (:read-board permissions)))
                         (:boards app))]
           [:a.card.link
            {:key (str "b-" (:board/name b))
             :on-click (fn [e]
                         (set! (.-href js/location) (str "#/board/" (:board/name b))))}
            [:div.content
             [:div.header (:board/name b)]
             [:div.description
              [:p (:board/description b)]]]])]
        (when (or (nil? (get-in app [:identity :user/permissions]))
                  (:create-board (get-in app [:identity :user/permissions])))
          [:div.ui.content
           [:h4.ui.horizontal.divider.header [:i.icon.edit] "New"]
           [:form.ui.reply.form {:on-submit (fn [e] (.preventDefault e))}
            [:div.field  (when (:board/name error-map) {:class "error"})
             [:label "Board Name"]
             [:input {:type "text" :name "name" :value (:board/name board)
                      :on-change (fn [e] (om/set-state! owner [:board :board/name] (.. e -target -value)))}]]
            [:div.field
             [:label "Description"]
             [:textarea {:name "description"
                         :value (:board/description board)
                         :on-change (fn [e]
                                      (om/set-state! owner [:board :board/description] (.. e -target -value)))
                         :on-key-up (fn [e]
                                      (when (and (= (.-which e) 0x0d) (.-ctrlKey e))
                                        (let [btn (.. (om/get-node owner) (querySelector "button.submit.button"))]
                                          (.click btn))))}]]
            [:div.field
             [:button.ui.blue.labeled.submit.icon.button
              {:on-click (fn [_]
                           (let [board (om/get-state owner :board)
                                 [result map] (b/validate board
                                                          :board/name v/required)]
                             (if result
                               (om/set-state! owner :error-map (:bouncer.core/errors map))
                               (do (save-board board)
                                   (om/update-state! owner [:board]
                                                     #(assoc %
                                                             :board/name ""
                                                             :board/description ""))))))}
              [:i.icon.edit] "Create board"]]]])]))))
