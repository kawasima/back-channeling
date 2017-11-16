(ns back-channeling.helper)

(defn find-thread [threads id]
  (->> (map-indexed vector threads)
       (filter #(= (:db/id (second %)) id))
       (map first)
       first))
