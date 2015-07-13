(ns back-channeling.comment-helper
  (:require [clojure.string :as string])
  (:use [clojure.walk :only [walk]]))

(defn link-to-url [text _]
  (let [pattern #"(?:https?|ftp)://[^\s/$.?#]\.[^\s]*"]
    (interleave
     (string/split text pattern)
     (concat (->> (re-seq pattern text)
                  (map (fn [url]
                         [:a {:href url} url])))
             (repeat "")))))

(defn reference-res [text {:keys [board-name thread-id]}]
  (if (and board-name thread-id)
    (let [pattern #">>\d+"]
      (interleave
       (string/split text pattern)
       (concat (->> (re-seq pattern text)
                    (map (fn [res]
                           [:a {:href (str "#/board/" board-name "/" thread-id "/" (subs res 2))} res])))
               (repeat ""))))
    text))

(defn decorate-comment [html f options]
  (walk (fn [x] (if (map? x) x (decorate-comment x f options)))
        (fn [x] (if (string? x) (f x options) x))
        html))

(defn format-plain [text & options]
  (-> (list text)
      (decorate-comment link-to-url options)
      (decorate-comment reference-res options)))


