(ns back-channeling.util-test
  (:require [clojure.test :refer :all]
            [back-channeling.util :as util]))

(deftest parse-request-test
  (testing "parses EDN body on POST"
    (let [ctx {:request {:request-method :post
                         :content-type "application/edn"
                         :body (pr-str {:name "test"})}}
          result (util/parse-request ctx)]
      (is (vector? result))
      (is (false? (first result)))
      (is (= {:name "test"} (:edn (second result))))))

  (testing "parses JSON body on POST"
    (let [ctx {:request {:request-method :post
                         :content-type "application/json"
                         :body "{\"name\":\"test\"}"}}
          result (util/parse-request ctx)]
      (is (vector? result))
      (is (false? (first result)))
      (is (= {:name "test"} (:edn (second result))))))

  (testing "parses EDN body on PUT"
    (let [ctx {:request {:request-method :put
                         :content-type "application/edn"
                         :body (pr-str {:updated true})}}
          result (util/parse-request ctx)]
      (is (vector? result))
      (is (= {:updated true} (:edn (second result))))))

  (testing "returns nil for GET request"
    (let [ctx {:request {:request-method :get
                         :content-type "application/edn"
                         :body (pr-str {:data 1})}}]
      (is (nil? (util/parse-request ctx)))))

  (testing "returns error for unknown content type"
    (let [ctx {:request {:request-method :post
                         :content-type "text/plain"
                         :body "hello"}}
          result (util/parse-request ctx)]
      (is (map? result))
      (is (some? (:message result)))))

  (testing "returns false when body is nil"
    (let [ctx {:request {:request-method :post
                         :content-type "application/edn"
                         :body nil}}]
      (is (false? (util/parse-request ctx)))))

  (testing "validates with malli schema"
    (let [schema [:map [:name :string]]
          ctx {:request {:request-method :post
                         :content-type "application/edn"
                         :body (pr-str {:name "valid"})}}
          result (util/parse-request ctx schema)]
      (is (vector? result))
      (is (false? (first result)))
      (is (= {:name "valid"} (:edn (second result))))))

  (testing "returns error for invalid data against schema"
    (let [schema [:map [:name :string]]
          ctx {:request {:request-method :post
                         :content-type "application/edn"
                         :body (pr-str {:name 123})}}
          result (util/parse-request ctx schema)]
      (is (map? result))
      (is (some? (:message result))))))
