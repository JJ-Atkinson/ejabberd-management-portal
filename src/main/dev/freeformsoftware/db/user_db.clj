(ns dev.freeformsoftware.db.user-db
  "Integrant component for managing persistent user database storage.
   Provides schema-validated file I/O with timestamped backups."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [dev.freeformsoftware.db.schema :as schema]
   [integrant.core :as ig]
   [taoensso.telemere :as tel]
   [zprint.core :as zp])
  (:import
    [java.security MessageDigest]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; SHA256 Utilities
;; =============================================================================

(defn- compute-sha256
  "Computes SHA256 hash of a file and returns it as a hex string."
  [file-path]
  (with-open [is (io/input-stream (fs/file file-path))]
    (let [digest (MessageDigest/getInstance "SHA-256")
          buffer (byte-array 8192)]
      (loop []
        (let [n (.read is buffer)]
          (when (pos? n)
            (.update digest buffer 0 n)
            (recur))))
      (let [hash-bytes (.digest digest)]
        (str/join (map #(format "%02x" %) hash-bytes))))))

;; =============================================================================
;; Backup Utilities
;; =============================================================================

(defn- create-backup
  "Creates a timestamped backup of the user database file.
   Backups are stored in db-folder/backup/userdb{timestamp}.edn"
  [db-folder userdb-file]
  (when (fs/exists? userdb-file)
    (let [backup-folder (fs/path db-folder "backup")
          timestamp     (System/currentTimeMillis)
          backup-file   (fs/path backup-folder (str "userdb" timestamp ".edn"))]
      (fs/create-dirs backup-folder)
      (fs/copy userdb-file backup-file {:replace-existing false})
      (tel/log! :debug ["Created backup" {:backup-file (str backup-file)}])
      backup-file)))

;; =============================================================================
;; File Operations
;; =============================================================================

(defn- ensure-folder-exists
  "Ensures the given folder exists, creating it recursively if necessary.
   Uses fs/create-dirs which is similar to mkdir -p."
  [folder-path]
  (tel/log! :debug ["Ensuring user-db folder exists at" folder-path])
  (fs/create-dirs folder-path)
  folder-path)

(defn- copy-default-userdb
  "Copies default-user-db.edn to the target folder as userdb.edn."
  [folder-path]
  (let [target-file  (fs/path folder-path "userdb.edn")
        default-file (io/resource "config/default-user-db.edn")]
    (when-not default-file
      (throw (ex-info "default-user-db.edn not found in resources/config"
                      {:type :missing-default-file})))
    (tel/log! :info ["Copying default-user-db.edn to" (str target-file)])
    (with-open [in (io/input-stream default-file)]
      (io/copy in (fs/file target-file)))
    target-file))

(defn- ensure-userdb-exists
  "Ensures userdb.edn exists in the folder, copying from default if needed."
  [folder-path]
  (let [userdb-file (fs/path folder-path "userdb.edn")]
    (if (fs/exists? userdb-file)
      (do
        (tel/log! :debug ["Found existing userdb.edn at" (str userdb-file)])
        userdb-file)
      (copy-default-userdb folder-path))))

(defn- atomic-move
  "Attempts to atomically move source to target. Falls back to copy+delete if atomic move fails."
  [source-file target-file]
  (try
    (fs/move source-file
             target-file
             {:replace-existing true
              :atomic-move      true})
    (catch Exception e
      (tel/log! :warn ["Atomic move failed, falling back to copy+delete" (ex-message e)])
      (fs/copy source-file target-file {:replace-existing true})
      (fs/delete source-file))))

;; =============================================================================
;; Public API Functions
;; =============================================================================

(defn clear-lock!
  [{:keys [db-folder] :as component}]
  (let [lock-file (fs/path db-folder "userdb.edn.lock")]
    (fs/delete-if-exists lock-file)))

(defn read-lock-state
  [{:keys [db-folder] :as component}]
  (let [lock-file (fs/path db-folder "userdb.edn.lock")]
    (if (fs/exists? lock-file)
      (let [[reason sys-time-ms human-readable-time]
            (str/split-lines (slurp (fs/file lock-file)))
            sys-time-ms (parse-long sys-time-ms)
            locked?     (< (System/currentTimeMillis) sys-time-ms)]
        (when-not locked? (clear-lock! component))
        {:locked?    locked?
         :reason     reason
         :expires-at human-readable-time})
      {:locked? false})))

(defn lock-state!
  [{:keys [db-folder] :as component} reason timeout-ms]
  (let [unlock-after-system-ms       (+ (System/currentTimeMillis) timeout-ms)
        unlock-after--human-readable (str (java.util.Date. (System/currentTimeMillis)))
        lock-file                    (fs/path db-folder "userdb.edn.lock")
        reason                       (str/replace reason "\n" "; ")]
    (spit (fs/file lock-file)
          (str/join "\r\n"
                    [reason unlock-after-system-ms unlock-after--human-readable]))))

(defn compute-current-sha
  [component]
  (let [db-folder   (:db-folder component)
        userdb-file (fs/path db-folder "userdb.edn")]
    (compute-sha256 userdb-file)))

(defn read-user-db
  "Reads the user database from disk, validates it, and attaches SHA256.
   
   Args:
     component - Component map returned from ig/init-key with :db-folder key
   
   Returns: 
     Config map with :_file-sha256 key added.
   
   Throws: 
     ex-info on validation failure or I/O errors."
  [component]
  (let [db-folder   (:db-folder component)
        userdb-file (fs/path db-folder "userdb.edn")]
    (tel/log! :debug ["Reading user-db from" (str userdb-file)])

    ;; Compute SHA before reading
    (let [sha256            (compute-sha256 userdb-file)
          ;; Read and parse EDN
          config            (edn/read-string (slurp (fs/file userdb-file)))
          ;; Validate against schema
          validation-result (schema/validate-user-db config)]

      (when-not (:valid? validation-result)
        (tel/log! :error ["User-db validation failed" validation-result])
        (throw (ex-info "User database validation failed"
                        {:type        :validation-error
                         :errors      (:errors validation-result)
                         :error-value (:error-value validation-result)})))

      (tel/log! :debug ["Successfully read and validated user-db" {:sha256 sha256}])
      (assoc config :_file-sha256 sha256))))

(defn write-user-db
  "Writes the user database to disk with automatic backup creation.
   Validates schema and uses atomic write.
   
   Args:
     component - Component map returned from ig/init-key with :db-folder key
     config - User database configuration map
   
   Returns: 
     Config map.
   
   Throws: 
     ex-info on validation failure."
  [component config]
  (let [db-folder   (:db-folder component)
        userdb-file (fs/path db-folder "userdb.edn")
        swp-file    (fs/path db-folder "userdb.swp.edn")]

    (tel/log! :debug ["Writing user-db to" (str userdb-file)])

    ;; Remove any internal keys before validation and writing
    (let [config-to-write   (dissoc config :_file-sha256)

          ;; Validate before writing
          validation-result (schema/validate-user-db config-to-write)]

      (when-not (:valid? validation-result)
        (tel/log! :error ["User-db validation failed before write" validation-result])
        (throw (ex-info "User database validation failed"
                        {:type        :validation-error
                         :errors      (:errors validation-result)
                         :error-value (:error-value validation-result)})))

      ;; Create timestamped backup before writing
      (create-backup db-folder userdb-file)

      ;; Write to swap file with zprint formatting
      (tel/log! :debug ["Writing to swap file" (str swp-file)])
      (spit (fs/file swp-file) (zp/zprint-str config-to-write {:map {:hang? false :force-nl? true}}))

      ;; Atomically move swap file to target
      (tel/log! :debug ["Atomically moving swap file to target"])
      (atomic-move swp-file userdb-file)

      (tel/log! :info ["Successfully wrote user-db"])
      config-to-write)))

;; =============================================================================
;; Integrant Lifecycle
;; =============================================================================

(defmethod ig/init-key ::user-db
  [_ {:keys [db-folder] :as config}]
  (tel/log! :info ["Initializing user-db component" config])

  (when (str/blank? db-folder)
    (throw (ex-info "db-folder configuration is required"
                    {:type   :missing-config
                     :config config})))

  ;; Ensure folder exists and userdb.edn is present
  (ensure-folder-exists db-folder)
  (ensure-userdb-exists db-folder)

  (tel/log! :info ["User-db component initialized successfully"])
  ;; Return plain map with db-folder
  (let [conf {:db-folder db-folder}]
    (def test-conf* conf)
    conf))

(defmethod ig/halt-key! ::user-db
  [_ component]
  (tel/log! :debug ["Halting user-db component"])
  ;; No cleanup needed currently
  nil)

;; =============================================================================
;; REPL Usage Examples
;; =============================================================================

(comment

  ;; Read database
  (def db (read-user-db test-conf*))

  ;; Modify and write back
  (def modified-db
    (assoc-in db [:members 0 :name] "kBC"))

  (write-user-db test-conf* db)
  (write-user-db test-conf* modified-db)
  (lock-state! test-conf* "test\nhello" 2000000)
  (read-lock-state test-conf*)
  (clear-lock! test-conf*)

  ;; Read again to verify
  (def db2 (fi/read-user-db component))

  ;; Halt component
  (ig/halt-key! :dev.freeformsoftware.db.user-db/user-db component))
