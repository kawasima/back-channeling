(ns back-channeling.components.avatar
  (:require [om.core :as om :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :as html :refer-macros [html]]
            [goog.crypt :as crypt]
            [goog.crypt.Md5])
  (:import [goog.crypt]))

(def md5digester (goog.crypt.Md5.))

(defn md5 [text]
  (doto md5digester
    (.reset)
    (.update text))
  (crypt/byteArrayToHex (.digest md5digester)))

(defn avatar-url [user]
  (str "https://www.gravatar.com/avatar/" (md5 (:user/email user)) "?d=mm"))

(defcomponent avatar [user owner]
  (render [_]
    (html
     [:a.avatar {:title (:user/name user)}
      [:img.ui.avatar.image {:src (str "https://www.gravatar.com/avatar/"
                       (md5 (:user/email user))
                       "?d=mm")}]])) )
