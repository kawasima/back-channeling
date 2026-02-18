(ns back-channeling.components.board
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [clojure.string :as string]
            [back-channeling.api :as api]
            [back-channeling.notification :as notification]
            [back-channeling.audio :as audio]
            [back-channeling.components.avatar :refer [avatar]]
            [back-channeling.components.comment :refer [comment-view]]
            [back-channeling.helper :refer [find-thread]]
            [back-channeling.comment-helper :refer [format-plain]]
            [back-channeling.component-helper :refer [make-click-outside-fn]]
            [back-channeling.events :as events]
            [goog.i18n.DateTimeSymbols_ja])
  (:import [goog.i18n DateTimeFormat]))

(def date-format-m (DateTimeFormat. goog.i18n.DateTimeFormat.Format.MEDIUM_DATETIME
                                    (aget goog.i18n (str "DateTimeSymbols_" (.-language js/navigator)))))

;; -- helpers ----------------------------------------------------------------

(defn save-board [board]
  (api/request "/api/boards"
               :POST
               board
               {:handler (fn [_]
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

(defn watch-thread-api [board-name thread user on-done]
  (api/request (str "/api/board/" board-name "/thread/" (:db/id thread))
               :PUT
               {:add-watcher user}
               {:handler (fn [_]
                           (rf/dispatch [::events/watch-thread {:thread thread :board/name board-name}])
                           (on-done true))})
  (notification/initialize))

(defn unwatch-thread-api [board-name thread user on-done]
  (api/request (str "/api/board/" board-name "/thread/" (:db/id thread))
               :PUT
               {:remove-watcher user}
               {:handler (fn [_]
                           (rf/dispatch [::events/unwatch-thread {:thread thread :board/name board-name}])
                           (on-done false))}))

(defn- find-element [orig-el attr-name]
  (loop [el orig-el]
    (if (.hasAttribute el attr-name)
      el
      (when-let [parent (.-parentNode el)]
        (recur parent)))))

;; -- comment-new-view -------------------------------------------------------

(defn comment-new-view [{:keys [app thread]}]
  (let [state (r/atom {:comment {:comment/content ""
                                 :comment/format "comment.format/plain"
                                 :thread/id (when thread (:db/id thread))}
                        :focus? false
                        :saving? false
                        :recording-status :none
                        :error-map nil})
        form-ref (r/atom nil)
        click-outside-fn (atom nil)]
    (r/create-class
     {:display-name "comment-new-view"

      :component-did-mount
      (fn [_]
        (when-let [node @form-ref]
          (reset! click-outside-fn
                  (make-click-outside-fn node
                                         (fn [_] (swap! state assoc :focus? false))))
          (.addEventListener js/document "mousedown" @click-outside-fn)))

      :component-will-unmount
      (fn [_]
        (when @click-outside-fn
          (.removeEventListener js/document "mousedown" @click-outside-fn)))

      :component-did-update
      (fn [_ _]
        (when-let [textarea (some-> @form-ref (.querySelector "textarea"))]
          (when (:focus? @state)
            (.focus textarea))))

      :reagent-render
      (fn [{:keys [app thread]}]
        (let [{:keys [comment focus? saving? error-map recording-status]} @state
              board-name (get-in app [:board :board/name])]
          [:form.ui.reply.form {:ref (fn [el] (reset! form-ref el))
                                :on-submit (fn [e] (.preventDefault e))}
           [:div.ui.equal.width.grid
            (if focus?
              [:div.row
               [:div.column
                [:div.field (when (:comment/content error-map) {:class "error"})
                 (when (= (:comment/format comment) "comment.format/voice")
                   (case recording-status
                     :recording
                     [:button.ui.large.red.circular.button
                      {:on-click
                       (fn [_]
                         (audio/stop-recording
                          (fn [blob]
                            (swap! state assoc :recording-status :encoding)
                            ;; Modern MediaRecorder already outputs webm/ogg, no conversion needed
                            (swap! state #(-> %
                                              (assoc :recording-status :none)
                                              (assoc-in [:comment :comment/content] blob))))))}
                      [:i.large.stop.icon] "Stop"]

                     :encoding
                     [:button.ui.large.red.circular.disable.button
                      [:i.large.mute.icon] "Stopping..."]

                     :none
                     [:button.ui.large.basic.circular.red.button
                      {:on-click (fn [_]
                                   (audio/start-recording)
                                   (swap! state assoc :recording-status :recording))}
                      [:i.large.unmute.icon] "Record"]))
                 [:textarea
                  (merge {:name "comment"
                          :value (:comment/content comment)
                          :on-change (fn [e]
                                       (when-not (= (get-in @state [:comment :comment/format]) "comment.format/voice")
                                         (swap! state assoc-in [:comment :comment/content] (.. e -target -value))))
                          :on-key-up (fn [e]
                                       (when (and (= (.-which e) 0x0d) (.-ctrlKey e))
                                         (when-let [btn (some-> @form-ref (.querySelector "button.submit.button"))]
                                           (.click btn))))}
                         (when (= (:comment/format comment) "comment.format/voice")
                           {:style {:display "none"}}))]]
                [:div.actions
                 [:div.two.fields
                  [:div.field
                   [:select {:name "format"
                             :value (:comment/format comment)
                             :on-change (fn [e]
                                          (swap! state assoc-in [:comment :comment/format] (.. e -target -value)))}
                    [:option {:value "comment.format/plain"} "Plain"]
                    [:option {:value "comment.format/markdown"} "Markdown"]
                    (when (audio/audio-available?)
                      [:option {:value "comment.format/voice"} "Voice"])]]
                  [:div.field
                   [:button.ui.blue.labeled.submit.icon.button
                    (merge {:on-click
                            (fn [_]
                              (let [c (:comment @state)]
                                (if (string/blank? (:comment/content c))
                                  (swap! state assoc :error-map {:comment/content ["Content is required"]})
                                  (do
                                    (swap! state assoc :saving? true)
                                    (save-comment board-name
                                                  (update-in c [:comment/format] keyword)
                                                  (fn [_]
                                                    (swap! state #(-> %
                                                                      (assoc-in [:comment :comment/content] "")
                                                                      (assoc :saving? false)))))))))}
                           (when saving? {:class "loading"}))
                    [:i.icon.edit] "New comment"]]]]]
               [:div.column
                [:div.preview
                 [:div.ui.top.right.attached.label "Preview"]
                 (case (:comment/format comment)
                   "comment.format/plain"
                   [:div.attached (format-plain (:comment/content comment)
                                                :board-name board-name
                                                :thread-id (:db/id thread))]

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
                 [:input {:type "text"
                          :value (:comment/content comment)
                          :on-focus (fn [_] (swap! state assoc :focus? true))}]]]])]]))
      })))

;; -- thread-view ------------------------------------------------------------

(defn thread-view [{:keys [app thread]}]
  (let [local (r/atom {:reaction-top 0
                        :open-reactions? false
                        :selected 0
                        :open-menu? false})
        reactions-ref (r/atom nil)
        click-outside-fn (atom nil)]
    (r/create-class
     {:display-name "thread-view"

      :component-did-mount
      (fn [_]
        (let [comment-no (or (get-in app [:page :comment/no])
                             (count (:thread/comments thread)))]
          (rf/dispatch [::events/scroll-to-comment comment-no]))
        (when-let [seg @reactions-ref]
          (reset! click-outside-fn
                  (make-click-outside-fn seg (fn [_] (swap! local assoc :open-reactions? false))))
          (.addEventListener js/document "mousedown" @click-outside-fn)))

      :component-will-unmount
      (fn [_]
        (when @click-outside-fn
          (.removeEventListener js/document "mousedown" @click-outside-fn)))

      :reagent-render
      (fn [{:keys [app thread]}]
        (let [{:keys [reaction-top selected open-reactions? open-menu?]} @local
              board-name (get-in app [:board :board/name])
              reactions @(rf/subscribe [:reactions])]
          [:div.ui.full.height.thread.comments
           [:div.ui.secondary.pointing.menu
            [:div.item
             [:h3.ui.header
              (-> (get-in app [:board :board/threads
                               (find-thread (get-in app [:board :board/threads]) (:db/id thread))])
                  :thread/title)]]
            [:div.right.item {:on-click (fn [_] (swap! local update :open-menu? not))}
             [:i.chevron.icon {:class (if open-menu? "up" "down")}]]
            [:div.ui.popup.bottom.right.transition {:class (if open-menu? "visible" "hidden")}
             [:div.ui.two.column.grid
              [:div.one.column.row [:div.ui.divider.column]]
              (let [threads (get-in app [:board :board/threads])
                    public? (get-in threads [(find-thread threads (:db/id thread)) :thread/public?])]
                [:div.row
                 [:div.column.left.aligned "public"]
                 [:div.ui.toggle.checkbox.column
                  [:input (merge {:type "checkbox"
                                  :on-click (fn [e]
                                              (.preventDefault e)
                                              (rf/dispatch
                                               (if public?
                                                 [::events/close-thread {:thread/id (:db/id thread)
                                                                         :board/name board-name}]
                                                 [::events/open-thread {:thread/id (:db/id thread)
                                                                        :board/name board-name}])))}
                                 (when public? {:checked "checked"})
                                 (when-not (get-in app [:board :user/permissions :read-any-thread])
                                   {:disabled "disabled"}))]
                  [:label]]])]]]
           [:div.scroll-pane
            [:div.ui.icon.reaction.buttons {:style {:top reaction-top}}
             [:button.ui.button {:on-click (fn [_] (swap! local assoc :open-reactions? true))}
              [:i.smile.icon]]
             [:button.ui.button {:on-click (fn [_]
                                             (rf/dispatch [::events/delete-comment
                                                           {:board/name board-name
                                                            :thread/id (:db/id thread)
                                                            :comment/no selected}]))}
              [:i.remove.icon]]]
            [:div.ui.reactions.raised.segment
             {:ref (fn [el] (reset! reactions-ref el))
              :style (if open-reactions?
                       {:visibility "visible" :top reaction-top}
                       {:visibility "hidden"})}
             [:div.ui.grid.container
              (for [reaction reactions]
                ^{:key (str "reaction-" (:db/id reaction))}
                [:div.four.wide.column
                 [:button.ui.tiny.basic.button
                  {:on-click (fn [_]
                               (api/request (str "/api/board/" board-name
                                                 "/thread/" (:db/id thread)
                                                 "/comment/" selected)
                                            :POST
                                            (select-keys reaction [:reaction/name])
                                            {:handler (fn [_]
                                                        (swap! local assoc :open-reactions? false))}))}
                  (:reaction/label reaction)]])]]
            (for [c (:thread/comments thread)]
              ^{:key (str "comment-" (:comment/no c))}
              [comment-view {:app app
                             :comment c
                             :thread thread
                             :show-reactions? true
                             :comment-attrs
                             {:on-mouse-enter
                              (fn [e]
                                (let [el (find-element (.-target e) "data-comment-no")]
                                  (when-not (:open-reactions? @local)
                                    (swap! local assoc
                                           :reaction-top (.-offsetTop el)
                                           :selected (:comment/no c)))))}}])]
           (if (>= (count (:thread/comments thread)) 1000)
             [:div.ui.error.message
              [:div.header "Over 1000 comments. You can't add any comment to this thread."]]
             [comment-new-view {:app app :thread thread}])]))
      })))

;; -- thread-watch-icon ------------------------------------------------------

(defn thread-watch-icon [{:keys [thread board-name user initial-watching?]}]
  (let [hover? (r/atom false)
        watching? (r/atom initial-watching?)]
    (fn [{:keys [thread board-name user]}]
      [:td.collapsing
       {:on-click (fn [_]
                    (if @watching?
                      (unwatch-thread-api board-name thread user #(reset! watching? %))
                      (watch-thread-api board-name thread user #(reset! watching? %))))
        :on-mouse-over (fn [_] (reset! hover? true))
        :on-mouse-out  (fn [_] (reset! hover? false))}
       [:i.icon {:class (case [@watching? @hover?]
                          [true true]   "hide red"
                          [true false]  "unhide green"
                          [false true]  "unhide green"
                          [false false] "hide grey")}]])))

;; -- thread-list-view -------------------------------------------------------

(defn thread-list-view [board]
  (let [local (r/atom {:sort-key [:thread/last-updated :desc]
                        :filters {:watching? false :writing? false}
                        :user {:user/name  (some-> js/document (.querySelector "meta[property='bc:user:name']") (.getAttribute "content"))
                               :user/email (some-> js/document (.querySelector "meta[property='bc:user:email']") (.getAttribute "content"))}})]
    (fn [board]
      (let [{:keys [filters sort-key user]} @local
            toggle-sort (fn [k]
                          (swap! local update :sort-key
                                 (fn [[col dir]]
                                   [k (if (= col k)
                                        (case dir :asc :desc :desc :asc)
                                        :asc)])))]
        [:div.table.container
         [:div.tbody.container
          [:table.ui.unstackable.compact.table
           [:thead
            [:tr
             [:th {:on-click (fn [_] (swap! local update-in [:filters :watching?] not))}
              [:div
               [:i.large.icons
                [:i.icon.unhide {:class (if (:watching? filters) "green" "disabled")}]
                [:i.icon.corner.filter {:class (if (:watching? filters) "teal" "disabled")}]]]]
             [:th {:on-click (fn [_] (swap! local update-in [:filters :writing?] not))}
              [:div
               [:i.large.icons
                [:i.icon.comments.outline {:class (if (:writing? filters) "green" "disabled")}]
                [:i.icon.corner.filter {:class (if (:writing? filters) "teal" "disabled")}]]]]
             [:th {:on-click (fn [_] (toggle-sort :thread/title))}
              [:div "Title " (when (= (first sort-key) :thread/title)
                               (case (second sort-key)
                                 :asc  [:i.caret.up.icon]
                                 :desc [:i.caret.down.icon]))]]
             [:th {:on-click (fn [_] (toggle-sort :thread/resnum))}
              [:div "Res" (when (= (first sort-key) :thread/resnum)
                            (case (second sort-key)
                              :asc  [:i.caret.up.icon]
                              :desc [:i.caret.down.icon]))]]
             [:th {:on-click (fn [_] (toggle-sort :thread/last-updated))}
              [:div "Last updated"
               (when (= (first sort-key) :thread/last-updated)
                 (case (second sort-key)
                   :asc  [:i.caret.up.icon]
                   :desc [:i.caret.down.icon]))]]
             [:th {:on-click (fn [_] (toggle-sort :thread/since))}
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
              ^{:key (str "t-" (:db/id thread))}
              [:tr (when (> (:thread/resnum thread) (:thread/readnum thread))
                     {:class "unread"})
               [thread-watch-icon {:thread thread
                                   :board-name (:board/name board)
                                   :user user
                                   :initial-watching? (boolean ((:thread/watchers thread) user))}]
               [:td.collapsing
                [:i.icon.comments.outline
                 {:class (if (> (:thread/writenum thread) 0) "green" "disabled")}]]
               [:td.selectable
                [:a {:href (str "#/board/" (:board/name board) "/" (:db/id thread))}
                 (when-not (:thread/public? thread) [:i.icon.lock])
                 (:thread/title thread)]]
               [:td (:thread/resnum thread)]
               [:td (.format date-format-m (:thread/last-updated thread))]
               [:td (.format date-format-m (:thread/since thread))]])]]]]))))

;; -- thread-new-view --------------------------------------------------------

(defn thread-new-view [board]
  (let [local (r/atom {:thread {:thread/title ""
                                :comment/content ""
                                :comment/format "comment.format/plain"}
                        :error-map nil})
        form-ref (r/atom nil)]
    (fn [board]
      (let [{:keys [thread error-map]} @local]
        [:form.ui.reply.form {:ref (fn [el] (reset! form-ref el))
                              :on-submit (fn [e] (.preventDefault e))}
         [:div.ui.equal.width.grid
          [:div.row
           [:div.column
            [:div.field (when (:thread/title error-map) {:class "error"})
             [:label "Title"]
             [:input {:type "text" :name "title" :value (:thread/title thread)
                      :on-change (fn [e] (swap! local assoc-in [:thread :thread/title] (.. e -target -value)))}]]
            [:div.field (when (:comment/content error-map) {:class "error"})
             [:textarea {:name "comment"
                         :value (:comment/content thread)
                         :on-change (fn [e]
                                      (swap! local assoc-in [:thread :comment/content] (.. e -target -value)))
                         :on-key-up (fn [e]
                                      (when (and (= (.-which e) 0x0d) (.-ctrlKey e))
                                        (when-let [btn (some-> @form-ref (.querySelector "button.submit.button"))]
                                          (.click btn))))}]]
            [:div.actions
             [:div.two.fields
              [:div.field
               [:select {:name "format"
                         :on-change (fn [e]
                                      (swap! local assoc-in [:thread :comment/format] (.. e -target -value)))}
                [:option {:value "comment.format/plain"} "Plain"]
                [:option {:value "comment.format/markdown"} "Markdown"]]]
              [:div.field
               [:button.ui.blue.labeled.submit.icon.button
                {:on-click (fn [_]
                             (let [t (:thread @local)]
                               (if (or (string/blank? (:thread/title t))
                                       (string/blank? (:comment/content t)))
                                 (swap! local assoc :error-map
                                        (cond-> {}
                                          (string/blank? (:thread/title t))
                                          (assoc :thread/title ["Title is required"])
                                          (string/blank? (:comment/content t))
                                          (assoc :comment/content ["Content is required"])))
                                 (do
                                   (rf/dispatch [::events/save-thread {:thread t :board board}])
                                   (swap! local update :thread assoc
                                          :comment/content ""
                                          :thread/title "")))))}
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

;; -- board-view -------------------------------------------------------------

(defn board-view []
  (let [sticky? (r/atom false)
        scroll-fn (atom nil)
        root-ref (atom nil)
        content-ref (r/atom nil)]
    (r/create-class
     {:display-name "board-view"

      :component-did-mount
      (fn [_]
        (reset! scroll-fn
                (fn [_]
                  (when-let [node @root-ref]
                    (when-let [tc (.querySelector node "div.thread.content")]
                      (reset! sticky? (< (.. tc getBoundingClientRect -top) 70))))))
        (.addEventListener js/window "scroll" @scroll-fn))

      :component-will-unmount
      (fn [_]
        (when @scroll-fn
          (.removeEventListener js/window "scroll" @scroll-fn)))

      :reagent-render
      (fn []
        (let [app {:board @(rf/subscribe [:board])
                   :threads @(rf/subscribe [:threads])
                   :page @(rf/subscribe [:page])
                   :identity @(rf/subscribe [:identity])
                   :reactions @(rf/subscribe [:reactions])}
              threads (:threads app)
              board (:board app)]
          [:div.main.content.full.height {:ref (fn [el] (reset! root-ref el))}
           [thread-list-view board]
           [:div.ui.top.attached.thread.content.segment
            {:ref (fn [el] (reset! content-ref el))}
            [:div.ui.top.attached.tabular.sticky.menu
             (when @sticky? {:class "fixed"})
             [:a.item (merge {:on-click (fn [_]
                                          (set! (.-href js/location)
                                                (str "#/board/" (:board/name board))))}
                             (when-not (get-in app [:page :thread/id])
                               {:class "active"}))
              [:span.tab-name "New"]]
             (for [{thread-id :db/id comments :thread/comments} (vals threads)]
               ^{:key (str "tab-" thread-id)}
               [:a.item (merge {:on-click (fn [_]
                                            (set! (.-href js/location)
                                                  (str "#/board/" (:board/name board) "/" thread-id))
                                            (rf/dispatch [::events/scroll-to-comment (count comments)]))}
                               (when (= (get-in app [:page :thread/id]) thread-id)
                                 {:class "active"}))
                [:span.tab-name
                 (let [bthreads (get-in app [:board :board/threads])
                       bt (get bthreads (find-thread bthreads thread-id))]
                   [:p (when (and bt (< (:thread/readnum bt) (:thread/resnum bt)))
                         [:i.icon.asterisk.green])
                    (when bt (:thread/title bt))])]
                [:span
                 [:i.close.icon
                  {:on-click (fn [e]
                               (rf/dispatch [::events/remove-thread
                                             {:thread/id thread-id
                                              :board/name (get-in app [:board :board/name])}])
                               (.stopPropagation e))}]]])]
            [:div.ui.bottom.attached.tab.full.height.segment
             (when-not (get-in app [:page :thread/id]) {:class "active"})
             [thread-new-view board]]
            (for [{thread-id :db/id :as thread} (vals threads)]
              ^{:key (str "tab-content-" thread-id)}
              [:div.ui.bottom.attached.tab.full.height.segment
               (when (= (get-in app [:page :thread/id]) thread-id)
                 {:class "active"})
               [thread-view {:app app :thread thread}]])]]))
      })))

;; -- boards-view ------------------------------------------------------------

(defn boards-view []
  (let [local (r/atom {:board {:board/name ""
                                :board/description ""}
                        :error-map nil})]
    (fn []
      (let [app {:boards @(rf/subscribe [:boards])
                 :identity @(rf/subscribe [:identity])}
            {:keys [board error-map]} @local]
        [:div.main.content.full.height
         [:div.ui.cards
          (for [b (filter #(let [permissions (:user/permissions %)]
                             (or (nil? permissions) (:read-board permissions)))
                          (:boards app))]
            ^{:key (str "b-" (:board/name b))}
            [:a.card.link
             {:on-click (fn [_]
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
             [:div.field (when (:board/name error-map) {:class "error"})
              [:label "Board Name"]
              [:input {:type "text" :name "name" :value (:board/name board)
                       :on-change (fn [e] (swap! local assoc-in [:board :board/name] (.. e -target -value)))}]]
             [:div.field
              [:label "Description"]
              [:textarea {:name "description"
                          :value (:board/description board)
                          :on-change (fn [e]
                                       (swap! local assoc-in [:board :board/description] (.. e -target -value)))}]]
             [:div.field
              [:button.ui.blue.labeled.submit.icon.button
               {:on-click (fn [_]
                            (let [b (:board @local)]
                              (if (string/blank? (:board/name b))
                                (swap! local assoc :error-map {:board/name ["Board name is required"]})
                                (do (save-board b)
                                    (swap! local update :board assoc
                                           :board/name ""
                                           :board/description "")))))}
               [:i.icon.edit] "Create board"]]]])]))))
