(ns dev.freeformsoftware.db.confwatch
  "Integrant component for watching user-db.edn and auto-syncing changes.
   
   This component uses beholder to watch for file system changes to userdb.edn.
   When changes are detected, it automatically triggers a sync-state operation."
  (:require
   [babashka.fs :as fs]
   [nextjournal.beholder :as beholder]
   [dev.freeformsoftware.ejabberd.sync-state :as sync-state]
   [dev.freeformsoftware.db.user-db :as user-db]
   [integrant.core :as ig]
   [taoensso.telemere :as tel]
   [dev.freeformsoftware.db.user-db :as file-db]))

(set! *warn-on-reflection* true)

(defn- handle-file-change
  "Handles file change events by triggering sync-state.
   Only processes changes to userdb.edn (ignores temp/swap files).
   Uses SHA256 to detect actual content changes - skips sync if SHA unchanged."
  [sync-state-component user-db-component {:keys [path type]}]
  (let [filename (str (fs/file-name path))]
    (when (and (= filename "userdb.edn")
               (#{:modify :create} type))
      (try
        (when-not (:locked? (file-db/read-lock-state user-db-component))
          ;; Read the file and get its SHA
          (let [current-sha (user-db/compute-current-sha user-db-component)
                last-sha    (sync-state/get-last-written-sync-state-sha sync-state-component)]

            (if (= current-sha last-sha)
              (tel/log! :debug ["File change detected but SHA unchanged, skipping sync" {:sha current-sha}])
              (do
                (tel/log! :info
                          ["Detected content change to userdb.edn, triggering sync"
                           {:type type :old-sha last-sha :new-sha current-sha}])
                (let [result (sync-state/swap-state!
                              sync-state-component
                              {:reason "File system change detected - reloading from disk"}
                              identity)]
                  (if (:success? result)
                    (tel/log! :info ["Auto-sync completed successfully"])
                    (tel/log! :warn ["Auto-sync failed" {:errors (:errors result)}])))))))
        (catch Exception e
          (tel/log! :error ["Auto-sync failed with exception" {:error (ex-message e)}]))))))

(defmethod ig/init-key ::confwatch
  [_ {:keys [sync-state user-db]}]
  (tel/log! :info ["Initializing confwatch component"])

  (let [db-folder (:db-folder user-db)
        ;; Read initial SHA to track changes
        watcher   (beholder/watch
                   (fn [event]
                     (handle-file-change sync-state user-db event))
                   db-folder)]

    (tel/log! :info ["Started watching" db-folder "for changes"])
    {:watcher    watcher
     :sync-state sync-state
     :user-db    user-db
     :db-folder  db-folder}))

(defmethod ig/halt-key! ::confwatch
  [_ {:keys [watcher db-folder]}]
  (tel/log! :info ["Halting confwatch component"])
  (when watcher
    (beholder/stop watcher)
    (tel/log! :info ["Stopped watching" db-folder])))
