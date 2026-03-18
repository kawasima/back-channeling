(ns back-channeling.database.cache
  (:require [integrant.core :as ig]
            [clojure.core.cache :as cache])
  (:import [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

(defrecord Boundary [cache scheduler])

(defn- evict-expired!
  "Force eviction of expired entries by touching every key via has?."
  [cache-atom]
  (doseq [k (keys @cache-atom)]
    (swap! cache-atom #(if (cache/has? % k) % (cache/evict % k)))))

(defmethod ig/init-key :back-channeling.database/cache [_ {:keys [ttl] :or {ttl (* 30 60 1000)}}]
  (let [cache-atom (atom (cache/ttl-cache-factory {} :ttl ttl))
        scheduler (Executors/newSingleThreadScheduledExecutor)]
    (.scheduleAtFixedRate scheduler
      ^Runnable (fn [] (evict-expired! cache-atom))
      (long 5) (long 5) TimeUnit/MINUTES)
    (->Boundary cache-atom scheduler)))

(defmethod ig/halt-key! :back-channeling.database/cache [_ {:keys [^ScheduledExecutorService scheduler]}]
  (when scheduler
    (.shutdown scheduler)))
