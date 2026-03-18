(ns back-channeling.mention)

(defn extract-mentions
  "Extracts @username mentions from comment text.
   Returns a set of usernames (without the @ prefix)."
  [content]
  (into #{} (map second) (re-seq #"@([A-Za-z0-9_\-]{3,20})" (or content ""))))
