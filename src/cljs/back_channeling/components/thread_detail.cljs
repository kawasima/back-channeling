(ns back-channeling.components.thread-detail
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [clojure.string :as string]
            [back-channeling.audio :as audio]
            [back-channeling.components.comment :refer [comment-view]]
            [back-channeling.helper :refer [find-thread]]
            [back-channeling.comment-helper :refer [format-plain]]
            [back-channeling.component-helper :refer [make-click-outside-fn]]
            [back-channeling.events :as events]))

;; -- helpers ----------------------------------------------------------------

(defn- save-comment [board-name comment on-success]
  (rf/dispatch [::events/save-comment {:board-name board-name
                                       :comment comment
                                       :on-success on-success}]))

(defn- find-element [orig-el attr-name]
  (loop [el orig-el]
    (if (.hasAttribute el attr-name)
      el
      (when-let [parent (.-parentNode el)]
        (recur parent)))))

;; -- comment-new-view -------------------------------------------------------

(defn comment-new-view [{:keys [thread]}]
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
              reactions @(rf/subscribe [:reactions])
              search-highlight @(rf/subscribe [:search-highlight])]
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
              [:div.row
               [:div.column.left.aligned "curation"]
               [:div.column
                [:a {:href (str "#/articles/new?thread-id=" (:db/id thread))}
                 [:i.external.share.large.icon]]]]
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
                               (rf/dispatch [::events/add-reaction
                                             {:board-name board-name
                                              :thread-id (:db/id thread)
                                              :comment-no selected
                                              :reaction reaction}])
                               (swap! local assoc :open-reactions? false))}
                  (:reaction/label reaction)]])]]
            (for [c (:thread/comments thread)]
              ^{:key (str "comment-" (:comment/no c))}
              [comment-view {:app app
                             :comment c
                             :thread thread
                             :show-reactions? true
                             :search-highlight search-highlight
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
