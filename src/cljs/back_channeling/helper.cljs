(ns back-channeling.helper)

(defn find-thread [threads id]
  (->> (map-indexed vector threads)
       (filter #(= (:db/id (second %)) id))
       (map first)
       first))

(defn find-board [boards name]
  (->> (map-indexed vector boards)
       (filter #(= (:board/name (second %)) name))
       (map first)
       first))
