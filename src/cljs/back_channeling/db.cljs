(ns back-channeling.db)

(def default-db
  {:boards []
   :board {}
   :threads {}
   :socket :disconnect
   :users #{}
   :identity nil
   :reactions []
   :articles []
   :article nil
   :target-thread nil
   :page {:type :initializing}})
