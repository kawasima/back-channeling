(ns back-channeling.auth.backend.session-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [buddy.auth.protocols :as proto]
            [back-channeling.auth.backend.session]))

(deftest session-backend-test
  (let [backend (ig/init-key :back-channeling.auth.backend/session {})]

    (testing "creates a backend"
      (is (some? backend))
      (is (satisfies? proto/IAuthentication backend))
      (is (satisfies? proto/IAuthorization backend)))

    (testing "parses session identity from request"
      (let [request {:session {:identity {:user/name "alice"}}}
            data (proto/-parse backend request)]
        (is (= {:user/name "alice"} data))))

    (testing "parse returns nil when no session identity"
      (let [request {:session {}}
            data (proto/-parse backend request)]
        (is (nil? data))))

    (testing "unauthorized handler returns 401 for API request"
      (let [request {:headers {"accept" "application/json"} :uri "/api/boards"}
            response (proto/-handle-unauthorized backend request {})]
        (is (= 401 (:status response)))))

    (testing "unauthorized handler redirects for non-API request"
      (let [request {:headers {"accept" "text/html"} :uri "/some-page"}
            response (proto/-handle-unauthorized backend request {})]
        (is (= 302 (:status response)))))))
