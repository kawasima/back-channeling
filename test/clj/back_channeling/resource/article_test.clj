(ns back-channeling.resource.article-test
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [datomic.api :as d]
            [back-channeling.test-helper :as th]
            [back-channeling.boundary.threads :as threads]
            [back-channeling.resource.article :refer [articles-resource article-resource]]))

(def ^:dynamic *system* nil)

(defn system-fixture [f]
  (let [sys (th/test-system)]
    (binding [*system* sys]
      (try
        (f)
        (finally
          (th/teardown-system sys))))))

(use-fixtures :each system-fixture)

(defn- parse-body [response]
  (when-let [body (:body response)]
    (cond
      (string? body) (edn/read-string body)
      :else body)))

(defn- create-thread [board-name title content user-name]
  (let [user-ref [:user/name user-name]
        [tempids thread-tempid] (threads/save (:datomic *system*) board-name
                                              {:thread/title title
                                               :comment/content content}
                                              user-ref)]
    (d/resolve-tempid (d/db (:connection *system*)) tempids thread-tempid)))

(deftest articles-resource-auth-test
  (testing "GET articles without auth returns 401"
    (let [handler (articles-resource *system*)
          request {:request-method :get
                   :headers {"accept" "application/edn"}}
          response (handler request)]
      (is (= 401 (:status response)))))

  (testing "GET articles with auth returns 200"
    (let [handler (articles-resource *system*)
          request (th/make-authenticated-request :get
                    :identity {:user/name "testuser"})
          response (handler request)]
      (is (= 200 (:status response)))))

  (testing "POST without auth returns 401"
    (let [handler (articles-resource *system*)
          request {:request-method :post
                   :headers {"accept" "application/edn"}
                   :content-type "application/edn"
                   :body (pr-str {:article/name "test"})}
          response (handler request)]
      (is (= 401 (:status response))))))

(deftest articles-resource-post-test
  (let [_ (th/create-test-user *system* "curator" "curator@test.com")
        _ (th/create-test-board *system* "default2" "Another board")
        thread-id (create-thread "default2" "T1" "Content" "curator")]

    (testing "POST article creates article"
      (let [handler (articles-resource *system*)
            request (th/make-authenticated-request :post
                      :identity {:user/name "curator"}
                      :body {:article/name "my-article"
                             :article/curator {:user/name "curator"}
                             :article/thread thread-id
                             :article/blocks [{:curating-block/content "Block 1"
                                               :curating-block/format :curating-block.format/markdown
                                               :curating-block/posted-at (java.util.Date.)
                                               :curating-block/posted-by {:user/name "curator"}}]})
            response (handler request)]
        (is (= 201 (:status response)))
        (let [body (parse-body response)]
          (is (some? (:db/id body))))))

    (testing "POST duplicate article name returns 409"
      (let [handler (articles-resource *system*)
            request (th/make-authenticated-request :post
                      :identity {:user/name "curator"}
                      :body {:article/name "my-article"})
            response (handler request)]
        (is (= 409 (:status response)))))))

(deftest article-resource-test
  (let [_ (th/create-test-user *system* "curator" "curator@test.com")
        _ (th/create-test-board *system* "aboard" "Board")
        thread-id (create-thread "aboard" "T1" "Content" "curator")
        ;; Create an article via resource
        create-handler (articles-resource *system*)
        create-resp (create-handler
                     (th/make-authenticated-request :post
                       :identity {:user/name "curator"}
                       :body {:article/name "test-article"
                              :article/curator {:user/name "curator"}
                              :article/thread thread-id
                              :article/blocks [{:curating-block/content "Original"
                                                :curating-block/format :curating-block.format/markdown
                                                :curating-block/posted-at (java.util.Date.)
                                                :curating-block/posted-by {:user/name "curator"}}]}))
        article-id (:db/id (parse-body create-resp))]

    (testing "GET article returns article data"
      (let [handler (article-resource *system* article-id)
            request (th/make-authenticated-request :get
                      :identity {:user/name "curator"})
            response (handler request)
            body (parse-body response)]
        (is (= 200 (:status response)))
        (is (= "test-article" (:article/name body)))
        (is (= 1 (count (:article/blocks body))))))

    (testing "GET article without auth returns 401"
      (let [handler (article-resource *system* article-id)
            request {:request-method :get
                     :headers {"accept" "application/edn"}}
            response (handler request)]
        (is (= 401 (:status response)))))))
