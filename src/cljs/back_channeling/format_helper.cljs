(ns back-channeling.format-helper
  (:require [goog.i18n.DateTimeSymbols_ja])
  (:import [goog.i18n DateTimeFormat]))

(def date-format-medium
  (DateTimeFormat. goog.i18n.DateTimeFormat.Format.MEDIUM_DATETIME
                   (aget goog.i18n (str "DateTimeSymbols_" (.-language js/navigator)))))
