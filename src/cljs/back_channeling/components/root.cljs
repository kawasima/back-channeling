(ns back-channeling.components.root
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [back-channeling.events :as events]
            [back-channeling.components.avatar :refer [avatar]]
            [back-channeling.components.board :refer [board-view boards-view]]
            [back-channeling.components.curation :refer [article-page articles-list-view]]
            [back-channeling.component-helper :refer [make-click-outside-fn]]))

(defn root-view []
  (let [local (r/atom {:open-profile? false
                        :open-users? false})
        click-outside-fn (atom nil)
        root-ref (atom nil)]
    (r/create-class
     {:display-name "root-view"

      :component-did-mount
      (fn [_]
        (when-let [node @root-ref]
          (when-let [menu-el (.querySelector node "div.site.menu")]
            (reset! click-outside-fn
                    (make-click-outside-fn
                     menu-el
                     (fn [_]
                       (swap! local assoc
                              :open-profile? false
                              :open-users? false)
                       (rf/dispatch [::events/clear-search-result]))))
            (.addEventListener js/document "mousedown" @click-outside-fn)))
        (.addEventListener js/window "focus"
                           (fn [_] (rf/dispatch [::events/window-focused]))))

      :component-will-unmount
      (fn [_]
        (when @click-outside-fn
          (.removeEventListener js/document "mousedown" @click-outside-fn)))

      :reagent-render
      (fn []
        (let [{:keys [open-profile?]} @local
              user @(rf/subscribe [:local-user])
              search-result @(rf/subscribe [:search-result])
              page-type @(rf/subscribe [:page-type])
              board @(rf/subscribe [:board])
              socket @(rf/subscribe [:socket])
              prefix @(rf/subscribe [:prefix])]
          [:div.full.height {:ref (fn [el] (reset! root-ref el))}
           [:div.ui.fixed.site.menu
            [:div.item
             [:a {:href "#/"}
              [:img.ui.logo.image {:src (str prefix "/img/logo.png")
                                   :alt "Back Channeling"}]]]
            (when (= page-type :board)
              [:div.center.menu
               [:a.item {:href "#/"}
                [:span {:style {:font-size "1.1em" :font-weight "500" :color "#555"}}
                 [:i.comments.outline.icon] (:board/name board)]]
               (when (or (nil? (:user/permissions board))
                         (:search-thread (:user/permissions board)))
                 [:div.item
                  [:div.ui.search
                   [:div.ui.icon.input
                    [:input.prompt
                     {:type "text"
                      :placeholder "Keyword"
                      :on-key-up (fn [e]
                                   (let [query (.. e -target -value)]
                                     (if (and query (> (count query) 2))
                                       (rf/dispatch [::events/search-threads (:board/name board) query])
                                       (rf/dispatch [::events/clear-search-result]))))}]
                    [:i.search.icon]]
                   (when (not-empty search-result)
                     [:div.results.transition.visible
                      (for [res search-result]
                        ^{:key (str "sr-" (:db/id res) "-" (:comment/no res))}
                        [:a.result {:on-click
                                    (fn [_]
                                      (let [q (some-> (.querySelector js/document ".ui.search input.prompt") .-value)]
                                        (rf/dispatch [::events/clear-search-result])
                                        (rf/dispatch [::events/move-to-thread
                                                      {:db/id (:db/id res)
                                                       :board/name (:board/name res)
                                                       :comment/no (:comment/no res)
                                                       :search-query q}])))}
                         [:div.content
                          [:div.title (:thread/title res)]]])])]])])
            [:div.right.menu
             [:div.ui.dropdown.item
              [:div.ui.two.column.grid.text.center
               {:on-click (fn [_] (swap! local update :open-profile? not))}
               [:span
                [:i.icon.circle
                 {:class (case socket :connect "green" :disconnect "red")
                  :on-click (fn [_]
                              (when (= socket :disconnect)
                                (rf/dispatch [::events/connect-socket])))}]
                (:user/name user)]
               [avatar user]]
              [:div.menu.transition {:class (if open-profile? "visible" "hidden")}
               [:form.item {:action (str prefix "/logout")
                            :method :post
                            :name "logout"
                            :on-click (fn [e] (.. e -currentTarget submit))}
                [:i.icon.sign.out]
                "Logout"]]]]]
           (case page-type
             :boards   [boards-view]
             :board    [board-view]
             :articles [articles-list-view]
             :article  [article-page]
             ;; :initializing, :loading
             [:div.main.content.full.height
              [:div.ui.active.centered.inline.text.loader "Loading..."]])]))})))
