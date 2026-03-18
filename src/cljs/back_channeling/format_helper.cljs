(ns back-channeling.format-helper
  (:require [goog.i18n.DateTimeSymbols_ja]
            [goog.object :as gobj])
  (:import [goog.i18n DateTimeFormat]))

(defn- locale-symbols []
  (let [goog-i18n (.-i18n js/goog)
        lang (some-> js/navigator .-language (.replace "-" "_"))]
    (or (gobj/get goog-i18n (str "DateTimeSymbols_" lang))
        ;; Fall back to base language (e.g. "ja" from "ja_JP")
        (when-let [base (some-> lang (.split "_") first)]
          (gobj/get goog-i18n (str "DateTimeSymbols_" base))))))

(def date-format-medium
  (if-let [symbols (locale-symbols)]
    (DateTimeFormat. goog.i18n.DateTimeFormat.Format.MEDIUM_DATETIME symbols)
    (DateTimeFormat. goog.i18n.DateTimeFormat.Format.MEDIUM_DATETIME)))
