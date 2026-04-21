(ns back-channeling.components.board
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [clojure.string :as string]
            [back-channeling.notification :as notification]
            [back-channeling.helper :refer [find-thread]]
            [back-channeling.comment-helper :refer [format-plain]]
            [back-channeling.events :as events]
            [back-channeling.format-helper :refer [date-format-medium]]
            [back-channeling.components.thread-detail :refer [thread-view]]))

;; -- helpers ----------------------------------------------------------------

(defn- save-board [board {:keys [on-success on-error]}]
  (rf/dispatch [::events/save-board {:board board
                                     :on-success on-success
                                     :on-error on-error}]))

(defn- watch-thread-api [board-name thread user on-done]
  (rf/dispatch [::events/watch-thread-api {:board-name board-name :thread thread
                                           :user user :on-done on-done}])
  (notification/initialize))

(defn- unwatch-thread-api [board-name thread user on-done]
  (rf/dispatch [::events/unwatch-thread-api {:board-name board-name :thread thread
                                             :user user :on-done on-done}]))

;; -- thread-watch-icon ------------------------------------------------------

(defn thread-watch-icon [{:keys [initial-watching?]}]
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

(defn thread-list-view [_board]
  (let [local (r/atom {:sort-key [:thread/last-updated :desc]
                        :filters {:watching? false :writing? false}})]
    (fn [board]
      (let [{:keys [filters sort-key]} @local
            user @(rf/subscribe [:local-user])
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
               [:td (.format date-format-medium (:thread/last-updated thread))]
               [:td (.format date-format-medium (:thread/since thread))]])]]]]))))

;; -- thread-new-view --------------------------------------------------------

(defn thread-new-view [_board]
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
          (doall
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
                [:p (:board/description b)]]]]))]
         (when (or (nil? (get-in app [:identity :user/permissions]))
                   (:create-board (get-in app [:identity :user/permissions])))
           [:div.ui.content
            [:h4.ui.horizontal.divider.header [:i.icon.edit] "New"]
            [:form.ui.reply.form (merge {:on-submit (fn [e] (.preventDefault e))}
                                       (when error-map {:class "error"}))
             (when error-map
               [:div.ui.error.message
                (for [[k messages] error-map
                      msg messages]
                  ^{:key (str "err-" (name k) "-" msg)}
                  [:p msg])])
             [:div.field (when (:board/name error-map) {:class "error"})
              [:label "Board Name"]
              [:input {:type "text" :name "name" :value (:board/name board)
                       :on-change (fn [e]
                                    (swap! local assoc-in [:board :board/name] (.. e -target -value))
                                    (swap! local assoc :error-map nil))}]]
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
                                (save-board b
                                  {:on-success (fn []
                                                 (swap! local assoc
                                                        :board {:board/name "" :board/description ""}
                                                        :error-map nil))
                                   :on-error (fn [xhrio]
                                               (let [status (.getStatus xhrio)
                                                     message (condp = status
                                                               409 "Board name is already taken."
                                                               422 "Invalid board name."
                                                               (str "Failed to create board (status " status ")"))]
                                                 (swap! local assoc :error-map {:board/name [message]})))}))))}
               [:i.icon.edit] "Create board"]]]])]))))
