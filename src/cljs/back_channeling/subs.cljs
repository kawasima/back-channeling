(ns back-channeling.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub :page       (fn [db _] (:page db)))
(rf/reg-sub :boards     (fn [db _] (:boards db)))
(rf/reg-sub :board      (fn [db _] (:board db)))
(rf/reg-sub :threads    (fn [db _] (:threads db)))
(rf/reg-sub :socket     (fn [db _] (:socket db)))
(rf/reg-sub :users      (fn [db _] (:users db)))
(rf/reg-sub :identity   (fn [db _] (:identity db)))
(rf/reg-sub :reactions  (fn [db _] (:reactions db)))
(rf/reg-sub :articles   (fn [db _] (:articles db)))
(rf/reg-sub :article    (fn [db _] (:article db)))
(rf/reg-sub :target-thread (fn [db _] (:target-thread db)))

(rf/reg-sub
 :page-type
 :<- [:page]
 (fn [page _] (:type page)))

(rf/reg-sub
 :page-thread-id
 :<- [:page]
 (fn [page _] (:thread/id page)))

(rf/reg-sub
 :page-board-name
 :<- [:page]
 (fn [page _] (:board/name page)))

(rf/reg-sub
 :page-loading?
 :<- [:page]
 (fn [page _] (:loading? page)))

(rf/reg-sub
 :board-name
 :<- [:board]
 (fn [board _] (:board/name board)))

(rf/reg-sub
 :board-threads
 :<- [:board]
 (fn [board _] (:board/threads board)))

(rf/reg-sub
 :board-permissions
 :<- [:board]
 (fn [board _] (:user/permissions board)))

(rf/reg-sub
 :user-name
 :<- [:identity]
 (fn [identity _] (:user/name identity)))

(rf/reg-sub
 :thread-by-id
 :<- [:threads]
 (fn [threads [_ thread-id]]
   (get threads thread-id)))

(rf/reg-sub
 :prefix
 (fn [db _] (:prefix db)))
