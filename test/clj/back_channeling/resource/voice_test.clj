(ns back-channeling.resource.voice-test
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [back-channeling.test-helper :as th]
            [back-channeling.resource.voice :refer [voices-resource]])
  (:import [java.io ByteArrayInputStream]))

(def ^:dynamic *system* nil)

(defn system-fixture [f]
  (let [sys (th/test-system)]
    (binding [*system* sys]
      (try
        (f)
        (finally
          (th/teardown-system sys))))))

(use-fixtures :each system-fixture)

(deftest voices-resource-malformed-test
  (testing "rejects unsupported content type"
    (let [handler (voices-resource *system* 12345)
          request (th/make-authenticated-request :post
                    :identity {:user/name "alice"})
          request (assoc request
                         :headers (assoc (:headers request) "content-type" "text/plain")
                         :body (ByteArrayInputStream. (.getBytes "data")))
          response (handler request)]
      (is (= 400 (:status response)))))

  (testing "rejects oversized content-length"
    (let [handler (voices-resource *system* 12345)
          request (th/make-authenticated-request :post
                    :identity {:user/name "alice"})
          request (assoc request
                         :headers (assoc (:headers request)
                                         "content-type" "audio/ogg"
                                         "content-length" "99999999")
                         :body (ByteArrayInputStream. (.getBytes "data")))
          response (handler request)]
      (is (= 400 (:status response)))))

  (testing "accepts valid audio/ogg content type and creates file"
    (let [handler (voices-resource *system* 12345)
          audio-data (byte-array 100)
          request (th/make-authenticated-request :post
                    :identity {:user/name "alice"})
          request (assoc request
                         :headers (assoc (:headers request)
                                         "content-type" "audio/ogg"
                                         "content-length" "100")
                         :body (ByteArrayInputStream. audio-data))
          response (handler request)]
      (is (= 201 (:status response)))))

  (testing "POST without auth returns 401"
    (let [handler (voices-resource *system* 12345)
          audio-data (byte-array 100)
          request {:request-method :post
                   :headers {"accept" "application/edn"
                             "content-type" "audio/ogg"
                             "content-length" "100"}
                   :body (ByteArrayInputStream. audio-data)}
          response (handler request)]
      (is (= 401 (:status response))))))
