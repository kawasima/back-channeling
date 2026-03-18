(ns back-channeling.db)

(def max-open-threads 8)

(def default-db
  {:boards []
   :board {}
   :threads {}
   :thread-order []
   :socket :disconnect
   :users #{}
   :identity nil
   :reactions []
   :articles []
   :article nil
   :target-thread nil
   :page {:type :initializing}})
