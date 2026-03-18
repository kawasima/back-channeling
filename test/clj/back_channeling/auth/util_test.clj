(ns back-channeling.auth.util-test
  (:require [clojure.test :refer :all]
            [back-channeling.auth.util :refer [api-access?]]))

(deftest api-access?-test
  (testing "JSON accept header"
    (is (api-access? {:headers {"accept" "application/json"}})))

  (testing "EDN accept header"
    (is (api-access? {:headers {"accept" "application/edn"}})))

  (testing "HTML accept header returns falsy"
    (is (not (api-access? {:headers {"accept" "text/html"}}))))

  (testing "no accept header returns nil"
    (is (nil? (api-access? {:headers {}}))))

  (testing "mixed accept with json"
    (is (api-access? {:headers {"accept" "text/html, application/json"}}))))
