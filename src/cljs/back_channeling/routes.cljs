(ns back-channeling.routes
  (:require [re-frame.core :as rf]
            [reitit.frontend :as reitit]
            [reitit.frontend.easy :as rfe]
            [back-channeling.events :as events]))

(def routes
  [["/"
    {:name ::boards
     :dispatch (fn [_] (rf/dispatch [::events/move-to-boards]))}]

   ["/board/:board-name"
    {:name ::board
     :dispatch (fn [{:keys [path-params]}]
                 (rf/dispatch [::events/move-to-board {:board/name (:board-name path-params)}]))}]

   ["/board/:board-name/:thread-id"
    {:name ::board-thread
     :dispatch (fn [{:keys [path-params]}]
                 (rf/dispatch [::events/move-to-thread
                               {:db/id (js/parseInt (:thread-id path-params))
                                :board/name (:board-name path-params)}]))}]

   ["/board/:board-name/:thread-id/:comment-no"
    {:name ::board-thread-comment
     :dispatch (fn [{:keys [path-params]}]
                 (rf/dispatch [::events/move-to-thread
                               {:db/id (js/parseInt (:thread-id path-params))
                                :board/name (:board-name path-params)
                                :comment/no (js/parseInt (:comment-no path-params))}]))}]

   ["/articles/new"
    {:name ::new-article
     :dispatch (fn [{:keys [query-params]}]
                 (rf/dispatch [::events/new-article (:thread-id query-params)]))}]

   ["/articles"
    {:name ::articles
     :dispatch (fn [_] (rf/dispatch [::events/fetch-articles]))}]

   ["/article/:id"
    {:name ::article
     :dispatch (fn [{:keys [path-params]}]
                 (rf/dispatch [::events/fetch-article (:id path-params)]))}]])

(defn on-navigate [match _]
  (when match
    (when-let [dispatch-fn (get-in match [:data :dispatch])]
      (dispatch-fn match))))

(defn init! []
  (rfe/start!
   (reitit/router routes)
   on-navigate
   {:use-fragment true}))
