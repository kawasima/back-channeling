(ns back-channeling.components.curation
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [clojure.string :as string]
            [back-channeling.events :as events]
            [back-channeling.components.comment :refer [comment-view]]
            [back-channeling.format-helper :refer [date-format-medium]]))

(defn- open-thread [thread-id]
  (when thread-id
    (rf/dispatch [::events/fetch-thread-comments thread-id])))

(defn editorial-space-view [{:keys [content]}]
  (let [local (r/atom {:editing? true
                        :content content
                        :error-map nil})]
    (fn [{:keys [save-fn]}]
      (let [{:keys [editing? content error-map]} @local]
        (if editing?
          [:div.ui.form
           [:div.field (when (:content error-map) {:class "error"})
            [:textarea
             {:value content
              :placeholder "Input content and press Ctrl+Enter to save."
              :on-change (fn [e]
                           (swap! local assoc :content (.. e -target -value)))
              :on-key-up (fn [e]
                           (when (and (= (.-which e) 0x0d) (.-ctrlKey e))
                             (if (string/blank? content)
                               (swap! local assoc :error-map {:content ["Content is required"]})
                               (do
                                 (save-fn content)
                                 (swap! local assoc :editing? false)))))}]]]
          [:div {:on-click (fn [_] (swap! local assoc :editing? true))} content])))))

(defn generate-markdown [curating-blocks]
  (->> curating-blocks
       (map #(case (get-in % [:curating-block/format :db/ident])
               :curating-block.format/markdown
               (:curating-block/content %)

               :curating-block.format/voice
               (str "\n[" (get-in % [:curating-block/posted-by :user/name]) " said](" (:curating-block/content %) ")\n")

               (str "```\n" (:curating-block/content %) "\n```\n")))
       (clojure.string/join "\n\n")))

(defn article-page []
  (let [user @(rf/subscribe [:local-user])
        local (r/atom {:selected-thread-comments #{}
                        :curated-comment-ids #{}
                        :editing-article nil
                        :editorial-space {:db/id 0
                                          :comment/format {:db/ident :comment.format/plain}
                                          :comment/content ""
                                          :comment/posted-by user}
                        :thread nil
                        :error-map nil})
        initialized? (atom false)
        root-ref (atom nil)]
    (r/create-class
     {:display-name "article-page"

      :component-did-mount
      (fn [_]
        (let [article @(rf/subscribe [:article])
              target-thread @(rf/subscribe [:target-thread])]
          (let [editing-article (or article {:article/name nil :article/blocks []})]
            (swap! local assoc
                   :editing-article editing-article
                   :curated-comment-ids (into #{} (keep :curating-block/id) (:article/blocks editing-article))
                   :thread {:db/id target-thread}))
          (when target-thread
            (open-thread target-thread))
          (reset! initialized? true)
          (when-let [markdown-btn (some-> @root-ref (.querySelector "button.markdown.button"))]
            (set! (.-onclick markdown-btn)
                  (fn [_]
                    (let [blocks (get-in @local [:editing-article :article/blocks])
                          text (generate-markdown blocks)]
                      (-> (.writeText js/navigator.clipboard text)
                          (.then #(.log js/console "Copied markdown to clipboard"))
                          (.catch #(.error js/console "Failed to copy:" %)))))))))

      :reagent-render
      (fn []
        (let [{:keys [selected-thread-comments curated-comment-ids editorial-space thread editing-article error-map]} @local
              curation-thread @(rf/subscribe [:curation-thread])
              thread (merge thread curation-thread)]
          [:div.curation.full.height.content {:ref (fn [el] (reset! root-ref el))}
           [:div.ui.full.height.grid
            [:div.full.height.row
             [:div.seven.wide.full.height.column
              [:div.scroll-pane
               [:div.ui.thread.comments
                [:h3.ui.dividing.header (:thread/title thread)]
                [:div.comment (when-not (curated-comment-ids 0)
                               {:on-click (fn [_]
                                            (swap! local update :selected-thread-comments
                                                   #(if (% 0) (disj % 0) (conj % 0))))})
                 [:div.content
                  [:div.ui.message (cond
                                     (curated-comment-ids 0) {:class "disabled"}
                                     (selected-thread-comments 0) {:class "red"})
                   "Editorial space"]]]
                (for [comment (:thread/comments thread)]
                  ^{:key (str "comment-" (:comment/no comment))}
                  [comment-view {:comment comment
                                 :thread thread
                                 :selected? (boolean (selected-thread-comments (:db/id comment)))
                                 :comment-attrs
                                 (if (curated-comment-ids (:db/id comment))
                                   {:class "disabled"}
                                   {:on-click (fn [_]
                                                (swap! local update :selected-thread-comments
                                                       (fn [s]
                                                         (if (s (:db/id comment))
                                                           (disj s (:db/id comment))
                                                           (conj s (:db/id comment))))))
                                    :class (if (selected-thread-comments (:db/id comment)) "selected" "")})}])]]]

             [:div.one.wide.column {:style {:display "flex" :align-items "center" :justify-content "center"}}
              (when (not-empty selected-thread-comments)
                [:button.ui.icon.button {:on-click (fn [_]
                              (swap! local update-in [:editing-article :article/blocks]
                                     (fn [blocks]
                                       (into (vec blocks)
                                             (->> selected-thread-comments
                                                  (map (fn [comment-id]
                                                         (->> (conj (:thread/comments thread) editorial-space)
                                                              (filter #(= (:db/id %) comment-id))
                                                              first)))
                                                  (map (fn [comment]
                                                         (into {} (for [[k v] comment]
                                                                    [(keyword "curating-block" (name k)) v]))))
                                                  (map (fn [block]
                                                         (update-in block [:curating-block/format :db/ident]
                                                                    #(keyword "curating-block.format" (name %)))))))))
                              (swap! local (fn [s]
                                             (-> s
                                                 (update :curated-comment-ids into selected-thread-comments)
                                                 (assoc :selected-thread-comments #{})))))}
                 [:i.arrow.right.icon]])]

             [:div.eight.wide.full.height.column
              [:div.ui.input (merge (when (:article/name error-map) {:class "error"})
                                    (when (= (count (:article/blocks editing-article)) 0)
                                      {:style {:visibility "hidden"}}))
               [:input {:type "text" :name "article-name"
                        :placeholder "Article name"
                        :value (:article/name editing-article)
                        :on-change (fn [e]
                                     (swap! local
                                            (fn [s]
                                              (-> s
                                                  (assoc-in [:editing-article :article/name] (.. e -target -value))
                                                  (update :error-map dissoc :article/name)))))}]
               [:button.ui.olive.basic.markdown.button
                [:i.paste.icon]
                "Markdown"]
               [:button.ui.primary.button
                {:on-click (fn [_]
                             (let [article (:editing-article @local)]
                               (cond
                                 (string/blank? (:article/name article))
                                 (swap! local assoc :error-map {:article/name ["Article name is required"]})

                                 (some #(string/blank? (:curating-block/content %)) (:article/blocks article))
                                 (swap! local assoc :error-map {:article/blocks ["All editable spaces must be saved."]})

                                 :else
                                 (rf/dispatch
                                  [::events/save-article
                                   {:article article
                                    :user user
                                    :thread-id (:db/id thread)
                                    :on-success (fn [id]
                                                  (if (:db/id article)
                                                    (swap! local assoc-in [:editing-article :db/id] id)
                                                    (do (set! (.-href js/location) (str "#/article/" id))
                                                        (.reload js/location))))
                                    :on-error (fn [xhrio]
                                                (let [message
                                                      (condp == (.getStatus xhrio)
                                                        409 "Specified artifact name is already used."
                                                        "Unknown error")]
                                                  (swap! local assoc :error-map {:article/name [message]})))}]))))}
                [:i.save.icon] "Save"]]
              (when-not (empty? error-map)
                [:div.ui.error.message
                 (for [[k messages] error-map]
                   (for [message messages]
                     ^{:key (str "err-" k "-" message)}
                     [:div.header message]))])
              [:div.scroll-pane
               [:div.ui.comments
                (doall
                 (map-indexed
                  (fn [index curating-block]
                    ^{:key (str "cb-" index)}
                    [:<>
                     [:div.ui.divider]
                     [:div.comment.curating-block
                      [:div.ui.mini.basic.icon.buttons
                       [:button.ui.button
                        {:on-click (fn [_]
                                     (when (> index 0)
                                       (swap! local update-in [:editing-article :article/blocks]
                                              (fn [blocks]
                                                (let [blocks (vec blocks)]
                                                  (assoc blocks
                                                         (dec index) (get blocks index)
                                                         index (get blocks (dec index))))))))}
                        [:i.caret.up.icon]]
                       [:button.ui.button
                        {:on-click (fn [_]
                                     (when (< index (dec (count (get-in @local [:editing-article :article/blocks]))))
                                       (swap! local update-in [:editing-article :article/blocks]
                                              (fn [blocks]
                                                (let [blocks (vec blocks)]
                                                  (assoc blocks
                                                         (inc index) (get blocks index)
                                                         index (get blocks (inc index))))))))}
                        [:i.caret.down.icon]]
                       [:button.ui.button
                        {:on-click (fn [_]
                                     (let [removed-id (:curating-block/id curating-block)]
                                       (swap! local (fn [s]
                                                      (-> s
                                                          (update-in [:editing-article :article/blocks]
                                                                     (fn [blocks]
                                                                       (vec (concat (take index blocks)
                                                                                    (drop (inc index) blocks)))))
                                                          (update :curated-comment-ids disj removed-id))))))}
                        [:i.close.icon]]]
                      [:div.metadata
                       [:span (get-in curating-block [:curating-block/posted-by :user/name])
                        "(" (.format date-format-medium (get-in curating-block [:curating-block/posted-at] (js/Date.))) ")"]]
                      [:div.text
                       (if (= (:curating-block/id curating-block) 0)
                         [editorial-space-view
                          {:content (:curating-block/content curating-block)
                           :save-fn (fn [content]
                                      (swap! local
                                             (fn [s]
                                               (-> s
                                                   (update-in [:editing-article :article/blocks index]
                                                              assoc
                                                              :curating-block/content content
                                                              :curating-block/posted-at (js/Date.))
                                                   (update :error-map dissoc :article/blocks)))))}]
                         (case (get-in curating-block [:curating-block/format :db/ident])
                           :curating-block.format/markdown
                           {:dangerouslySetInnerHTML {:__html (.render js/md (:curating-block/content curating-block))}}
                           :curating-block.format/voice
                           [:audio {:controls true
                                    :src (str "/voice/" (:curating-block/content curating-block))}]
                           (:curating-block/content curating-block)))]]])
                  (:article/blocks editing-article)))]]]]]]))
      })))

(defn articles-list-view []
  (let [articles @(rf/subscribe [:articles])]
    [:div.main.content.full.height
     [:h2.ui.header
      [:i.file.text.outline.icon]
      [:div.content "Articles"]]
     [:div.ui.cards
      (doall
       (for [article articles]
         ^{:key (str "article-" (:db/id article))}
         [:a.card.link
          {:on-click (fn [_]
                       (set! (.-href js/location) (str "#/article/" (:db/id article))))}
          [:div.content
           [:div.header (:article/name article)]
           [:div.meta
            (when-let [curator-name (get-in article [:article/curator :user/name])]
              [:span [:i.user.icon] curator-name])
            (when-let [thread-title (get-in article [:article/thread :thread/title])]
              [:span {:style {:margin-left "1em"}} [:i.comments.icon] thread-title])]]]))]
     (when (empty? articles)
       [:div.ui.message
        [:p "No articles yet."]])]))
