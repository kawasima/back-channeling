(ns back-channeling.migrator.schema-test
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [back-channeling.migrator.schema :as schema]))

(deftest extract-version-test
  (testing "extracts version number from migration file URL"
    (let [url (java.net.URL. "file:///some/path/back_channeling/migration/v001_create_board.edn")]
      (is (= 1 (schema/extract-version url)))))

  (testing "extracts multi-digit version"
    (let [url (java.net.URL. "file:///path/v042_some_migration.edn")]
      (is (= 42 (schema/extract-version url)))))

  (testing "handles version with leading zeros"
    (let [url (java.net.URL. "file:///path/v009_add_indexes.edn")]
      (is (= 9 (schema/extract-version url))))))

(deftest find-migration-files-test
  (testing "finds migration files on classpath"
    (let [files (schema/find-migration-files)]
      (is (seq files))
      ;; Should find the migration files we know exist
      (is (>= (count files) 9))
      ;; Should be sorted
      (let [versions (map schema/extract-version files)]
        (is (= versions (sort versions)))))))

(deftest find-or-create-version-test
  (let [uri (str "datomic:mem://test-schema-" (java.util.UUID/randomUUID))
        _ (d/create-database uri)
        connection (d/connect uri)]
    (try
      (testing "returns nil on fresh database (creates schema-version attrs)"
        (let [version (schema/find-or-create-version connection)]
          (is (nil? version))))

      (testing "returns version after migration data is transacted"
        @(d/transact connection
           [{:db/id (d/tempid :db.part/user)
             :schema-version/version 5
             :schema-version/installed-at (java.util.Date.)}])
        (let [version (schema/find-or-create-version connection)]
          (is (= 5 version))))

      (testing "returns max version when multiple exist"
        @(d/transact connection
           [{:db/id (d/tempid :db.part/user)
             :schema-version/version 10
             :schema-version/installed-at (java.util.Date.)}])
        (let [version (schema/find-or-create-version connection)]
          (is (= 10 version))))
      (finally
        (d/release connection)
        (d/delete-database uri)))))
