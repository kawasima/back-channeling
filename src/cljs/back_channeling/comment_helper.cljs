(ns back-channeling.comment-helper
  (:require [clojure.string :as string])
  (:use [clojure.walk :only [walk]]))

(defn link-to-url [text]
  (let [pattern #"(?:https?|ftp)://[^\s/$.?#]\.[^\s]*"]
    (interleave
     (string/split text pattern)
     (concat (->> (re-seq pattern text)
                  (map (fn [url]
                         [:a {:href url} url])))
             (repeat "")))))

(defn reference-res [text]
  (let [pattern #">>\d+"]
    (interleave
     (string/split text pattern)
     (concat (->> (re-seq pattern text)
                  (map (fn [res]
                         [:a {:href (str "#comments-" (subs res 2))} res])))
             (repeat "")))))

(defn decorate-comment [f html]
  (walk (fn [x] (if (map? x) x (decorate-comment f x)))
        (fn [x] (if (string? x) (f x) x))
        html))

(defn format-plain [text]
  (->> (list text)
       (decorate-comment link-to-url)
       (decorate-comment reference-res)))


