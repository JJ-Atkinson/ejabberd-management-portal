(ns dev.freeformsoftware.db.file-interaction-test
  "Comprehensive test suite for file-interaction component."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [fulcro-spec.core :refer [specification =check=> behavior assertions]]
   [fulcro-spec.check :as check :refer [checker]]
   [dev.freeformsoftware.db.file-interaction :as sut]
   [dev.freeformsoftware.db.schema :as schema]
   [integrant.core :as ig]
   [taoensso.telemere :as tel]))

;; =============================================================================
;; Test Fixtures and Helpers
;; =============================================================================

(def ^:dynamic *test-dir* nil)

(comment
  (tel/with-handler :silence
    (constantly nil)
    (tel/log! :error "hi")))

(defn create-temp-dir
  "Creates a unique temporary directory for testing."
  []
  (let [tmp-dir (System/getProperty "java.io.tmpdir")
        test-dir (io/file tmp-dir (str "userdb-test-" (System/currentTimeMillis)))]
    (.mkdirs test-dir)
    (.getAbsolutePath test-dir)))

(defn delete-recursively
  "Recursively deletes a directory and all its contents."
  [file]
  (when (.isDirectory file)
    (doseq [child (.listFiles file)]
      (delete-recursively child)))
  (.delete file))

(defn temp-dir-fixture
  "Test fixture that creates a temp directory before each test and cleans it up after."
  [f]
  (let [test-dir (create-temp-dir)]
    (binding [*test-dir* test-dir]
      (tel/with-handler :silence
        (constantly nil)
        (try (f)
             (finally (delete-recursively (io/file test-dir))))))))

(clojure.test/use-fixtures :each temp-dir-fixture)

(defn write-test-file
  "Writes EDN data to the userdb.edn file in the test directory."
  [data]
  (let [userdb-file (io/file *test-dir* "userdb.edn")]
    (spit userdb-file (pr-str data))))

(defn read-test-file
  "Reads EDN data from the userdb.edn file in the test directory."
  []
  (let [userdb-file (io/file *test-dir* "userdb.edn")]
    (when (.exists userdb-file)
      (edn/read-string (slurp userdb-file)))))

(defn corrupt-file
  "Writes invalid EDN to the userdb.edn file to test error handling."
  []
  (let [userdb-file (io/file *test-dir* "userdb.edn")]
    (spit userdb-file "{:invalid edn structure")))

(defn init-test-component
  "Initializes a file-interaction component pointing to the test directory."
  []
  (ig/init-key ::sut/user-db {:db-folder *test-dir*}))

;; =============================================================================
;; Test Data
;; =============================================================================

(def valid-config
  {:groups {:group/owner "Owner"
            :group/member "Member"}
   :rooms [{:name "Test Room"
            :members #{:group/member}
            :admins #{:group/owner}
            :only-admins-can-speak? false}]
   :members [{:name "Test User"
              :user-id "testuser"
              :groups #{:group/member}}]})

;; =============================================================================
;; Custom Checkers
;; =============================================================================

(def file-exists?
  (checker [path]
           (.exists (io/file path))))

(def has-sha256?
  (checker [result]
           (and (map? result)
                (string? (:_file-sha256 result)))))

(def valid-db?
  (checker [result]
           (true? (:valid? (schema/validate-user-db result)))))

;; =============================================================================
;; Component Initialization Tests
;; =============================================================================

(specification "Component initialization"
               (behavior "creates db folder if it doesn't exist"
                         (let [component (init-test-component)]
                           (assertions
                            "folder is created"
                            *test-dir*
                            =check=>
                            file-exists?
                            "component contains db-folder"
                            (:db-folder component)
                            =>
                            *test-dir*)))

               (behavior "copies default-user-db.edn when userdb.edn doesn't exist"
                         (let [component (init-test-component)
                               userdb-path (str *test-dir* "/userdb.edn")]
                           (assertions
                            "userdb.edn is created"
                            userdb-path
                            =check=>
                            file-exists?
                            "file contains valid data"
                            (read-test-file)
                            =check=>
                            valid-db?)))

               (behavior "doesn't overwrite existing userdb.edn"
                         (write-test-file valid-config)
                         (let [before-content (read-test-file)
                               _ (init-test-component)
                               after-content (read-test-file)]
                           (assertions
                            "file content is unchanged"
                            after-content
                            =>
                            before-content)))

               (behavior "logs error and continues if userdb.edn is corrupted on init"
                         (corrupt-file)
                         (let [component (init-test-component)]
                           (assertions
                            "component is still created"
                            (:db-folder component)
                            =>
                            *test-dir*
                            "userdb.edn still exists (corrupted)"
                            (str *test-dir* "/userdb.edn")
                            =check=>
                            file-exists?))))

;; =============================================================================
;; Read Operation Tests
;; =============================================================================

(specification "Reading user database"
               (behavior "reads valid file and includes SHA256"
                         (write-test-file valid-config)
                         (let [component (init-test-component)
                               result (sut/read-user-db component)]
                           (assertions
                            "returns map with SHA256"
                            result
                            =check=>
                            has-sha256?
                            "contains all groups"
                            (:groups result)
                            =>
                            (:groups valid-config)
                            "contains all rooms"
                            (:rooms result)
                            =>
                            (:rooms valid-config)
                            "contains all members"
                            (:members result)
                            =>
                            (:members valid-config))))

               (behavior "throws on invalid schema"
                         (write-test-file {:groups {} :rooms [] :members []})
                         (let [component (init-test-component)]
                           (assertions
                            "throws exception"
                            (try
                              (sut/read-user-db component)
                              false
                              (catch Exception e true))
                            =>
                            true)))

               (behavior "throws on malformed EDN"
                         (corrupt-file)
                         (let [component (init-test-component)]
                           (assertions
                            "throws exception"
                            (try
                              (sut/read-user-db component)
                              false
                              (catch Exception e true))
                            =>
                            true)))

               (behavior "throws if userdb.edn doesn't exist"
                         (let [component (init-test-component)
                               _ (.delete (io/file *test-dir* "userdb.edn"))]
                           (assertions
                            "throws exception"
                            (try
                              (sut/read-user-db component)
                              false
                              (catch Exception e true))
                            =>
                            true))))

;; =============================================================================
;; SHA256 Tests
;; =============================================================================

(specification "SHA256 checksums"
               (behavior "produces consistent checksums for same content"
                         (write-test-file valid-config)
                         (let [component (init-test-component)
                               sha1 (:_file-sha256 (sut/read-user-db component))
                               sha2 (:_file-sha256 (sut/read-user-db component))]
                           (assertions
                            "SHA256 is consistent across reads"
                            sha1
                            =>
                            sha2)))

               (behavior "produces different checksums for different content"
                         (write-test-file valid-config)
                         (let [component (init-test-component)
                               sha1 (:_file-sha256 (sut/read-user-db component))
                               modified-config (assoc-in valid-config [:groups :group/owner] "Modified Owner")
                               _ (write-test-file modified-config)
                               sha2 (:_file-sha256 (sut/read-user-db component))]
                           (assertions
                            "SHA256 changes when content changes"
                            (not= sha1 sha2)
                            =>
                            true))))

;; =============================================================================
;; Write Operation Tests
;; =============================================================================

(specification "Writing user database"
               (behavior "writes valid config to disk"
                         (let [component (init-test-component)
                               current (sut/read-user-db component)
                               config-to-write (merge valid-config {:_file-sha256 (:_file-sha256 current)})
                               _ (sut/write-user-db component config-to-write)
                               written-data (read-test-file)]
                           (assertions
                            "file is written"
                            (str *test-dir* "/userdb.edn")
                            =check=>
                            file-exists?
                            "groups are preserved"
                            (:groups written-data)
                            =>
                            (:groups valid-config)
                            "rooms are preserved"
                            (:rooms written-data)
                            =>
                            (:rooms valid-config)
                            "members are preserved"
                            (:members written-data)
                            =>
                            (:members valid-config)
                            "_file-sha256 is removed from written file"
                            (:_file-sha256 written-data)
                            =>
                            nil)))

               (behavior "throws on invalid schema"
                         (write-test-file valid-config)
                         (let [component (init-test-component)
                               result (sut/read-user-db component)
                               invalid-config (assoc result :groups {})]
                           (assertions
                            "throws exception"
                            (try
                              (sut/write-user-db component invalid-config)
                              false
                              (catch Exception e true))
                            =>
                            true)))

               (behavior "detects concurrent modifications"
                         (write-test-file valid-config)
                         (let [component (init-test-component)
                               config1 (sut/read-user-db component)
          ;; Simulate concurrent modification
                               modified-config (assoc-in valid-config [:groups :group/owner] "Modified")
                               _ (write-test-file modified-config)]
                           (assertions
                            "throws exception on SHA mismatch"
                            (try
                              (sut/write-user-db component config1)
                              false
                              (catch Exception e true))
                            =>
                            true))))

;; =============================================================================
;; Managed State Tests
;; =============================================================================

;; =============================================================================
;; Round-trip Tests
;; =============================================================================

(specification "Round-trip operations"
               (behavior "read-after-write returns same data"
                         (let [component (init-test-component)
                               current (sut/read-user-db component)
                               config-to-write (merge valid-config {:_file-sha256 (:_file-sha256 current)})
                               _ (sut/write-user-db component config-to-write)
                               read-result (sut/read-user-db component)]
                           (assertions
                            "groups match"
                            (:groups read-result)
                            =>
                            (:groups valid-config)
                            "rooms match"
                            (:rooms read-result)
                            =>
                            (:rooms valid-config)
                            "members match"
                            (:members read-result)
                            =>
                            (:members valid-config)
                            "has SHA256"
                            read-result
                            =check=>
                            has-sha256?)))

               (behavior "write-read-verify preserves all data"
                         (write-test-file valid-config)
                         (let [component (init-test-component)
                               original (sut/read-user-db component)
                               modified (assoc-in original [:groups :group/new] "New Group")
                               _ (sut/write-user-db component modified)
                               final (sut/read-user-db component)]
                           (assertions
                            "new group is present"
                            (get-in final [:groups :group/new])
                            =>
                            "New Group"
                            "original groups are preserved"
                            (:group/owner (:groups final))
                            =>
                            "Owner"))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(specification "Edge cases"
               (behavior "handles rooms without :room-id"
                         (let [component (init-test-component)
                               current (sut/read-user-db component)
                               config-with-roomid (assoc-in valid-config [:rooms 0 :room-id] "test-room-123")
                               config-to-write (merge config-with-roomid {:_file-sha256 (:_file-sha256 current)})
                               _ (sut/write-user-db component config-to-write)
                               result (sut/read-user-db component)]
                           (assertions
                            "room-id is preserved"
                            (get-in result [:rooms 0 :room-id])
                            =>
                            "test-room-123")))

               (behavior "handles empty collections"
                         (let [component (init-test-component)
                               current (sut/read-user-db component)
                               minimal-config {:groups {:group/owner "Owner"}
                                               :rooms []
                                               :members []
                                               :_file-sha256 (:_file-sha256 current)}
                               _ (sut/write-user-db component minimal-config)
                               result (sut/read-user-db component)]
                           (assertions
                            "empty rooms is valid"
                            (:rooms result)
                            =>
                            []
                            "empty members is valid"
                            (:members result)
                            =>
                            [])))

               (behavior "handles multiple writes sequentially"
                         (let [component (init-test-component)
                               current (sut/read-user-db component)
                               config-to-write (merge valid-config {:_file-sha256 (:_file-sha256 current)})
                               _ (sut/write-user-db component config-to-write)
                               read1 (sut/read-user-db component)
                               modified1 (assoc-in read1 [:groups :group/new1] "New1")
                               _ (sut/write-user-db component modified1)
                               read2 (sut/read-user-db component)
                               modified2 (assoc-in read2 [:groups :group/new2] "New2")
                               _ (sut/write-user-db component modified2)
                               final (sut/read-user-db component)]
                           (assertions
                            "both modifications are present"
                            (get-in final [:groups :group/new1])
                            =>
                            "New1"
                            "second modification is present"
                            (get-in final [:groups :group/new2])
                            =>
                            "New2"))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(specification "Full Integrant lifecycle"
               (behavior "init and halt lifecycle works correctly"
                         (let [component (ig/init-key ::sut/user-db {:db-folder *test-dir*})
                               _ (ig/halt-key! ::sut/user-db component)]
                           (assertions
                            "component initializes"
                            (:db-folder component)
                            =>
                            *test-dir*
                            "userdb.edn exists after init"
                            (str *test-dir* "/userdb.edn")
                            =check=>
                            file-exists?))))
