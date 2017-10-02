(ns back-channeling.endpoint.api-test
  (:require [back-channeling.handler.api :as sut]
            [liberator.dev :refer [wrap-trace]]
            [clojure.pprint :refer [pprint]]
            [clojure.test :refer :all]))

#_(def test-config
  {:datomic {:recreate? true}})

#_(def config
  (meta-merge config/defaults
              config/environ
              test-config))

#_(defn new-system [config]
  (-> (component/system-map
       :datomic (datomic-connection (:datomic config))
       :migration (migration-model)
       :socketapp (spy (reify ISendMessage
                         (broadcast-message [_ message] "broadcast")
                         (multicast-message [_ message users] "multicast"))))
      (component/system-using
       {:migration [:datomic]})
      (component/start-system)))

#_(deftest board
  (let [system (new-system config)
        handler (-> (sut/board-resource system "gege")
                    (wrap-trace :header))]
    (testing "a board is not found"
      (let [request {:request-method :get}]
        (is (= 404 (:status (handler request))))))

    (testing "create board"
      (let [request {:request-method :post}]
        (is (= 201 (:status (handler request))))))
    (testing "a board is found"
      (let [request {:request-method :get}
            response (handler request)]
        ;(pprint response)
        (is (= 200 (:status response)))))))

#_(deftest threads
  (let [system (new-system config)
        handler (-> (sut/threads-resource system "default")
                    (wrap-trace :header))]
    (d/transact (:datomic system)
                [{:db/id #db/id[:db.part/user]
                  :user/name  "rightuser"
                  :user/email "rightuser@example.com"}])
    (testing "create a thread by unauthorized user"
      (let [request {:request-method :post
                     :content-type "application/edn"
                     :body (pr-str {:thread/title "new thread"
                                    :comment/content "first comment"})}]
        (is (= 401 (:status (handler request))))))

    (testing "create a thread successfully"
      (let [request {:request-method :post
                     :content-type "application/edn"
                     :identity {:user/name "rightuser"}
                     :body (pr-str {:thread/title "new thread"
                                    :comment/content "first comment"})}
            response (handler request)]
        (is (= 201 (:status response)) "Create a new thread successfully!")
        (is (= 1 (call-count (:socketapp system) socketapp/broadcast-message))
            "Broadcast message of thread creation to all users")))))

#_(deftest reations
  (let [system (new-system config)
        handler (-> (sut/reactions-resource system)
                    (wrap-trace :header))]
    (testing "fetch all reactions"
      (let [request {:request-method :get}]
        (handler request)))))
