(ns dev.freeformsoftware.ejabberd.sync-state
  "Integrant component for synchronizing ejabberd state with user-db configuration.
   
   This namespace provides the core synchronization engine that reconciles the
   declarative configuration in user-db.edn with the live ejabberd server state.
   
   Key features:
   - Returns updated state with :room-id values assigned
   - Tracks managed entities in :do-not-edit-state
   - Idempotent - safe to run multiple times
   - Handles users, rooms, rosters, and affiliations"
  (:require
   [better-cond.core :as b]
   [dev.freeformsoftware.ejabberd.ejabberd-api :as api]
   [dev.freeformsoftware.db.file-interaction :as file-db]
   [dev.freeformsoftware.db.schema :as schema]
   [dev.freeformsoftware.db.util :as db-util]
   [dev.freeformsoftware.ejabberd.admin-bot :as ejabberd.admin-bot]
   [dev.freeformsoftware.ejabberd.room-membership :as room-membership]
   [clojure.set :as set]
   [clojure.string :as str]
   [camel-snake-kebab.core :as csk]
   [integrant.core :as ig]
   [taoensso.telemere :as tel]
   [dev.freeformsoftware.ejabberd.admin-bot :as admin-bot])
  (:import
   [org.jxmpp.jid Jid]
   [org.jxmpp.jid.impl JidCreate]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- generate-room-id
  "Generates a random 10-character alphanumeric room ID.
   Uses lowercase letters only to avoid confusion."
  []
  (let [chars (vec "abcdefghijklmnopqrstuvwxyz")]
    (apply str (repeatedly 10 #(rand-nth chars)))))

(defn- compute-diffs
  "Computes differences between current state and last tracked state.
   
   Returns map with:
   - :users-to-add - users in :members but not in :managed-members
   - :users-to-delete - users in :managed-members but not in :members
   - :rooms-to-create - rooms without :room-id
   - :rooms-to-delete - room-ids in :managed-rooms but not in current :rooms"
  [current-state old-tracking]
  (let [current-members (set (map :user-id (:members current-state)))
        current-rooms (set (keep :room-id (:rooms current-state)))
        old-members (get-in old-tracking [:managed-members] #{})
        old-rooms (get-in old-tracking [:managed-rooms] #{})]
    {:users-to-add (set/difference current-members old-members)
     :users-to-delete (set/difference old-members current-members)
     :rooms-to-create (filterv #(nil? (:room-id %)) (:rooms current-state))
     :rooms-to-delete (set/difference old-rooms current-rooms)}))

(defn- delete-users
  "Removes users from all rosters and room affiliations, then unregisters them.
   
   Performs complete user deletion:
   1. Remove from all managed users' rosters
   2. Remove from all managed rooms
   3. Unregister (delete) the user account from ejabberd"
  [ejabberd-api users-to-delete managed-rooms managed-users xmpp-domain]
  (let [report (atom [])]
    (doseq [user-id users-to-delete]
      (tel/log! :info ["Deleting managed user" {:user-id user-id}])

      ;; Remove from all managed users' rosters
      (doseq [other-user managed-users
              :when (not= other-user user-id)]
        (try
          (api/delete-rosteritem ejabberd-api
                                 other-user
                                 xmpp-domain
                                 user-id
                                 xmpp-domain)
          (swap! report conj
                 {:action :roster-deleted
                  :from other-user
                  :target user-id})
          (catch Exception e
            (tel/log! :warn
                      ["Failed to remove roster item"
                       {:user other-user :target user-id :error (ex-message e)}]))))

      ;; Remove from all managed rooms
      (doseq [room-id managed-rooms]
        (try
          (api/set-room-affiliation ejabberd-api
                                    room-id
                                    user-id
                                    xmpp-domain
                                    "none")
          (swap! report conj
                 {:action :affiliation-removed
                  :room room-id
                  :user user-id})
          (catch Exception e
            (tel/log! :warn
                      ["Failed to remove room affiliation"
                       {:room room-id :user user-id :error (ex-message e)}]))))

      ;; Unregister (delete) the user account
      (try
        (api/unregister ejabberd-api user-id)
        (swap! report conj
               {:action :user-unregistered
                :user-id user-id})
        (tel/log! :info ["User unregistered successfully" {:user-id user-id}])
        (catch Exception e
          (tel/log! :error
                    ["Failed to unregister user"
                     {:user-id user-id :error (ex-message e)}])
          (swap! report conj
                 {:action :user-unregister-failed
                  :user-id user-id
                  :error (ex-message e)}))))
    @report))

(defn- delete-rooms
  "Deletes managed rooms from ejabberd.
   
   Removes all managed users' affiliations and then destroys the room."
  [ejabberd-api rooms-to-delete managed-users xmpp-domain muc-service]
  (let [report (atom [])]
    (doseq [room-id rooms-to-delete]
      (tel/log! :info ["Deleting managed room" {:room-id room-id}])

      ;; Remove all managed users from this room
      (doseq [user-id managed-users]
        (try
          (api/set-room-affiliation ejabberd-api
                                    room-id
                                    user-id
                                    xmpp-domain
                                    "none")
          (swap! report conj
                 {:action :affiliation-removed
                  :room room-id
                  :user user-id})
          (catch Exception e
            (tel/log! :warn
                      ["Failed to remove room affiliation during room deletion"
                       {:room room-id :user user-id :error (ex-message e)}]))))

      ;; Destroy the room
      (try
        (api/destroy-room ejabberd-api room-id muc-service)
        (swap! report conj
               {:action :room-destroyed
                :room-id room-id})
        (tel/log! :info ["Room destroyed successfully" {:room-id room-id}])
        (catch Exception e
          (tel/log! :error
                    ["Failed to destroy room"
                     {:room-id room-id :error (ex-message e)}])
          (swap! report conj
                 {:action :room-destroy-failed
                  :room-id room-id
                  :error (ex-message e)}))))
    @report))

(defn- register-users
  "Registers new users in ejabberd with temporary passwords.
   
   In production, uses randomly generated base64 passwords.
   In dev/test, uses the configured default-test-password for easier testing.
   
   Users will set their real password via the signup flow."
  [ejabberd-api users-to-add env default-test-password]
  (let [report (atom [])]
    (doseq [user-id users-to-add]
      (tel/log! :info ["Registering new user" {:user-id user-id}])

      (b/cond
        :let [existing-users (try
                               (set (api/registered-users ejabberd-api))
                               (catch Exception e
                                 (tel/log! :error ["Failed to get registered users" {:error (ex-message e)}])
                                 #{}))]

        (contains? existing-users user-id)
        (do
          (tel/log! :info ["User already exists, skipping registration" {:user-id user-id}])
          (swap! report conj
                 {:action :user-already-exists
                  :user-id user-id}))

        :let [temp-password (case env
                              :prod (db-util/generate-random-password)
                              (:dev :test) default-test-password)]

        :else
        (try
          (api/register ejabberd-api user-id temp-password)
          (swap! report conj
                 {:action :user-registered
                  :user-id user-id})
          (catch Exception e
            (tel/log! :error
                      ["Failed to register user"
                       {:user-id user-id :error (ex-message e)}])
            (swap! report conj
                   {:action :user-registration-failed
                    :user-id user-id
                    :error (ex-message e)})))))
    @report))

(defn- create-rooms
  "Creates rooms that don't have :room-id assigned yet.
   
   Room names are converted to kebab-case for the actual room ID.
   After creating each room, the admin bot is instructed to join it.
   Returns updated rooms vector with :room-id populated for all rooms."
  [ejabberd-api rooms managed-muc-options muc-service admin-bot]
  (let [report (atom [])
        updated-rooms (atom [])]

    (doseq [room rooms]
      (if (:room-id room)
        ;; Room already has ID, keep as-is
        (swap! updated-rooms conj room)

        ;; Room needs creation - use kebab-case for room-id
        (let [room-id (csk/->kebab-case (:name room))
              room-opts (merge managed-muc-options
                               (if (:only-admins-can-speak? room)
                                 {:moderated "true"
                                  :members_by_default "false"}
                                 {:moderated "false"}))]
          (tel/log! :info ["Creating room" {:name (:name room) :room-id room-id}])

          (try
            (api/create-room-with-opts ejabberd-api room-id room-opts muc-service)
            (let [updated-room (assoc room :room-id room-id)]
              (swap! updated-rooms conj updated-room)
              (swap! report conj
                     {:action :room-created
                      :name (:name room)
                      :room-id room-id})

              ;; Join the newly created room with the admin bot
              (when admin-bot
                (ejabberd.admin-bot/join-room-if-new! admin-bot room-id)))
            (catch Exception e
              (tel/log! :error
                        ["Failed to create room"
                         {:name (:name room) :room-id room-id :error (ex-message e)}])
              ;; Still add room to list without :room-id on failure
              (swap! updated-rooms conj room)
              (swap! report conj
                     {:action :room-creation-failed
                      :name (:name room)
                      :room-id room-id
                      :error (ex-message e)}))))))

    {:updated-rooms @updated-rooms
     :report @report}))

(defn- build-roster-groups
  "Builds the list of roster group strings for a member based on their :groups.
   
   Example: If member has #{:group/owner :group/officer} and groups map is
   {:group/owner \"Owner\" :group/officer \"Officer\"}, returns [\"Owner\" \"Officer\"]"
  [member-groups groups-map]
  (vec (keep #(get groups-map %) member-groups)))

(defn- sync-rosters
  "Synchronizes rosters for all managed users.
   
   Creates a fully-connected mesh where all managed users are in each other's
   rosters with subscription 'both'. Roster groups are assigned based on the
   user's :groups membership.
   
   OPTIMIZATION: Only updates roster items that have changed (different groups or nick)."
  [ejabberd-api members groups xmpp-domain]
  (let [report (atom [])]
    (doseq [member-a members]
      (let [user-a (:user-id member-a)]
        (tel/log! :debug ["Syncing roster for" {:user user-a}])

        ;; Get current roster
        (let [current-roster (try
                               (api/get-roster ejabberd-api user-a)
                               (catch Exception e
                                 (tel/log! :warn
                                           ["Failed to get roster"
                                            {:user user-a :error (ex-message e)}])
                                 []))

              ;; Build lookup map: jid -> roster item
              roster-lookup (into {} (map (fn [item] [(:jid item) item]) current-roster))]

          ;; Build target roster - all other managed users
          (doseq [member-b members
                  :when (not= (:user-id member-b) user-a)]
            (let [user-b (:user-id member-b)
                  jid-b (str user-b "@" xmpp-domain)
                  nick-b (:name member-b)
                  groups-b (vec (build-roster-groups (:groups member-b) groups))

                  ;; Check if roster item exists with correct values
                  existing-item (get roster-lookup jid-b)
                  existing-groups (set (:groups existing-item))
                  target-groups (set groups-b)

                  needs-update? (or (nil? existing-item)
                                    (not= existing-groups target-groups)
                                    (not= (:nick existing-item) nick-b))]

              ;; Only update if something changed
              (when needs-update?
                (try
                  (api/add-rosteritem ejabberd-api
                                      user-a
                                      xmpp-domain
                                      user-b
                                      xmpp-domain
                                      nick-b
                                      groups-b
                                      "both")
                  (swap! report conj
                         {:action :roster-updated
                          :user user-a
                          :contact user-b
                          :groups groups-b
                          :was-new? (nil? existing-item)})
                  (catch Exception e
                    (tel/log! :warn
                              ["Failed to update roster item"
                               {:user user-a :target user-b :groups groups-b :error (ex-message e)}])))))))))
    @report))

;; compute-affiliation moved to room-membership namespace

(defn- notify-room-change
  "Sends a notification to a user about their room affiliation change."
  [admin-bot user-id room-name room-id muc-service current-affiliation new-affiliation]
  (b/cond
    (not admin-bot) nil
    (not (:connection admin-bot)) nil
    (= current-affiliation new-affiliation) nil

    :let [room-url (str room-id "@" muc-service)
          message (case new-affiliation
                    "owner" (str "You have been added to room '" room-name "' as an owner.\nJoin at: " room-url)
                    "admin" (str "You have been added to room '" room-name "' as an admin.\nJoin at: " room-url)
                    "member" (str "You have been added to room '" room-name "' as a member.\nJoin at: " room-url)
                    "none" (str "You have been removed from room '" room-name "'.")
                    nil)]

    (not message) nil

    :else
    (try
      (ejabberd.admin-bot/send-message! admin-bot {:local-part user-id :service :dm} message)
      (tel/log! :info ["Sent room notification" {:user user-id :room room-name :affiliation new-affiliation}])
      (catch Exception e
        (tel/log! :warn ["Failed to send room notification" {:user user-id :room room-name :error (ex-message e)}])))))

(defn- sync-affiliations
  "Synchronizes room affiliations for all managed users.
   
   ONLY touches managed users - unmanaged users are left alone.
   
   Affiliations are computed based on group membership:
   - Admin: User's :groups intersect with room's :admins
   - Member: User's :groups intersect with room's :members (but not :admins)
   - None: No intersection
   
   Sends notifications via admin-bot when affiliations change.
   
   OPTIMIZATION: Only calls set-room-affiliation when affiliation needs to change.
   Accepts pre-fetched room-affiliations-map to avoid redundant API calls."
  [ejabberd-api rooms members xmpp-domain muc-service admin-bot room-affiliations-map]
  (let [report (atom [])]
    (doseq [room rooms
            :when (:room-id room)]
      (let [room-id (:room-id room)
            room-name (:name room)]
        (tel/log! :debug ["Syncing affiliations for room" {:room-id room-id}])

        ;; Use pre-fetched affiliations
        (let [current-affiliations (get room-affiliations-map room-id {})]

          ;; For each managed member, compute and set affiliation if changed
          (doseq [member members]
            (let [user-id (:user-id member)
                  user-groups (:groups member)
                  target-affiliation (room-membership/compute-room-affiliation user-groups
                                                                               (:admins room)
                                                                               (:members room))
                  current-affiliation (get current-affiliations user-id "none")
                  needs-update? (not= current-affiliation target-affiliation)]

              ;; Only make API call if affiliation needs to change
              (if needs-update?
                (try
                  (api/set-room-affiliation ejabberd-api
                                            room-id
                                            user-id
                                            xmpp-domain
                                            target-affiliation
                                            muc-service)

                  ;; Notify user if it's not the admin
                  (when (not= user-id (:user-id admin-bot/member-admin-bot))
                    (notify-room-change admin-bot
                                        user-id
                                        room-name
                                        room-id
                                        muc-service
                                        current-affiliation
                                        target-affiliation))

                  (swap! report conj
                         {:action :affiliation-updated
                          :room room-id
                          :user user-id
                          :old-affiliation current-affiliation
                          :new-affiliation target-affiliation})
                  (catch Exception e
                    (tel/log! :warn
                              ["Failed to set room affiliation"
                               {:room room-id
                                :user user-id
                                :affiliation target-affiliation
                                :error (ex-message e)}])))

                ;; Affiliation already correct, no API call needed
                (swap! report conj
                       {:action :affiliation-unchanged
                        :room room-id
                        :user user-id
                        :affiliation current-affiliation})))))))
    @report))

(defn- sync-bookmarks
  "Synchronizes bookmarks for all managed users based on room affiliations.
   
   For each user, creates bookmarks for all rooms where they have
   member, admin, or owner affiliation. Rooms with 'none' or 'outcast'
   affiliation are not bookmarked.
   
   Bookmarks use the user's user-id as the nickname and default to autojoin=true.
   
   OPTIMIZATION: Only calls set-user-bookmarks when bookmark list has changed."
  [ejabberd-api members rooms xmpp-domain muc-service room-affiliations-map]
  (let [report (atom [])]
    (doseq [member members]
      (let [user-id (:user-id member)]
        (tel/log! :debug ["Syncing bookmarks for" {:user user-id}])

        (try
          ;; Build target bookmark list
          (let [target-bookmarks
                (vec
                 (for [[room-id affiliations] room-affiliations-map
                       :let [room (some #(when (= (:room-id %) room-id) %) rooms)
                             user-aff (some #(when (str/includes? (:jid %) user-id)
                                               (:affiliation %))
                                            affiliations)]
                       :when (and room
                                  user-aff
                                  (not= user-aff "none")
                                  (not= user-aff "outcast"))]
                   {:jid (str room-id "@" muc-service)
                    :name (:name room)
                    :autojoin true
                    :nick user-id}))

                ;; Get current bookmarks to compare
                current-bookmarks (try
                                    (vec (api/get-user-bookmarks ejabberd-api user-id))
                                    (catch Exception e
                                      (tel/log! :debug ["No existing bookmarks or error fetching"
                                                        {:user user-id :error (ex-message e)}])
                                      []))

                ;; Normalize for comparison (sort by jid, keep only relevant fields)
                normalize-bookmarks (fn [bms]
                                      (sort-by :jid (map (fn [bm]
                                                           (-> bm
                                                               (select-keys [:jid :name :autojoin :nick])
                                                               (update :autojoin #(if (= % "false") false true))))
                                                         bms)))

                current-normalized (normalize-bookmarks current-bookmarks)
                target-normalized (normalize-bookmarks target-bookmarks)

                needs-update? (not= current-normalized target-normalized)]

            ;; Only update if bookmarks changed
            (if needs-update?
              (do
                (api/set-user-bookmarks ejabberd-api user-id target-bookmarks)
                (swap! report conj
                       {:action :bookmarks-updated
                        :user user-id
                        :bookmark-count (count target-bookmarks)
                        :was-empty? (empty? current-bookmarks)}))

              (swap! report conj
                     {:action :bookmarks-unchanged
                      :user user-id
                      :bookmark-count (count target-bookmarks)})))

          (catch Exception e
            (tel/log! :warn
                      ["Failed to sync bookmarks"
                       {:user user-id :error (ex-message e)}])
            (swap! report conj
                   {:action :bookmark-sync-failed
                    :user user-id
                    :error (ex-message e)})))))
    @report))

(defn- update-tracking-state
  "Updates :do-not-edit-state in the state map to reflect current managed entities."
  [state]
  (let [managed-members (set (map :user-id (:members state)))
        managed-rooms (set (keep :room-id (:rooms state)))
        managed-groups (set (keys (:groups state)))]
    (assoc state
           :do-not-edit-state
           {:managed-members managed-members
            :managed-rooms managed-rooms
            :managed-groups managed-groups})))

(defn sync-state!
  "Synchronizes ejabberd state with the declarative configuration.
   
   Takes a db state map, computes differences, applies changes to ejabberd,
   and returns updated state with all stateful entities populated.
   
   The admin bot is ghost-included during sync (added at start, removed at end)
   so it gets synced into all rooms and rosters, but doesn't appear in the returned state.
   
   Parameters:
   - component: sync-state component (contains ejabberd-api, managed-muc-options)
   - db: user-db state map (from file-db/read-user-db)
   
   Returns: map with keys:
   - :state - Updated user-db state with :room-id values assigned and :do-not-edit-state updated
   - :report - Detailed change report
     {:users-added [...]
      :users-deleted [...]
      :rooms-created [{:name \"...\" :room-id \"...\"}]
      :rooms-deleted [...]
      :roster-changes [...]
      :affiliation-changes [...]}
   
   Usage:
   (let [current-db (file-db/read-user-db user-db-component)
         {:keys [state report]} (sync-state sync-component current-db)]
     ;; Save updated state
     (file-db/write-user-db user-db-component state)
     ;; Log report
     (tel/log! :info [\"Sync complete\" report]))"
  [component db]
  (tel/log! :info ["Starting state synchronization"])

  (let [ejabberd-api (:ejabberd-api component)
        managed-muc-options (:managed-muc-options component)
        xmpp-domain (:xmpp-domain ejabberd-api)
        muc-service (:muc-service ejabberd-api)

        ;; Phase 0: Ghost-include admin bot with only :group/bot
        admin-bot-member (assoc ejabberd.admin-bot/member-admin-bot
                                :groups
                                #{:group/bot})
        current-state (update db :members (fnil conj []) admin-bot-member)
        old-tracking (:do-not-edit-state current-state)

        ;; Phase 1: Compute diffs
        diffs (compute-diffs current-state old-tracking)

        ;; Track all changes
        all-reports (atom [])

        ;; Phase 2: Deletions
        _ (when (seq (:users-to-delete diffs))
            (let [managed-rooms (get-in old-tracking [:managed-rooms] #{})
                  managed-users (get-in old-tracking [:managed-members] #{})]
              (swap! all-reports concat
                     (delete-users ejabberd-api
                                   (:users-to-delete diffs)
                                   managed-rooms
                                   managed-users
                                   xmpp-domain))))

        _ (when (seq (:rooms-to-delete diffs))
            (let [managed-users (get-in old-tracking [:managed-members] #{})]
              (swap! all-reports concat
                     (delete-rooms ejabberd-api
                                   (:rooms-to-delete diffs)
                                   managed-users
                                   xmpp-domain
                                   muc-service))))

        ;; Phase 3: Register users
        _ (when (seq (:users-to-add diffs))
            (swap! all-reports concat
                   (register-users ejabberd-api
                                   (:users-to-add diffs)
                                   (:env component)
                                   (:default-test-password component))))

        ;; Phase 4: Create rooms (returns updated state)
        rooms-result (create-rooms ejabberd-api
                                   (:rooms current-state)
                                   managed-muc-options
                                   muc-service
                                   (:admin-bot component))
        working-state (assoc current-state :rooms (:updated-rooms rooms-result))
        _ (swap! all-reports concat (:report rooms-result))

        ;; Phase 5: Sync rosters
        _ (swap! all-reports concat
                 (sync-rosters ejabberd-api
                               (:members working-state)
                               (:groups working-state)
                               xmpp-domain))

        ;; Phase 5.5: Fetch room affiliations ONCE for both phases 6 and 7
        ;; Store in two formats to avoid redundant API calls
        room-affiliations-raw
        (into {}
              (for [room (:rooms working-state)
                    :when (:room-id room)
                    :let [room-id (:room-id room)
                          affs (try
                                 (api/get-room-affiliations ejabberd-api room-id muc-service)
                                 (catch Exception e
                                   (tel/log! :warn ["Failed to get room affiliations"
                                                    {:room room-id :error (ex-message e)}])
                                   []))]]
                [room-id affs]))

        ;; Transform for sync-affiliations: room-id -> {username -> affiliation-string}
        room-affiliations-map
        (into {}
              (for [[room-id affs] room-affiliations-raw]
                [room-id
                 (into {}
                       (map (fn [aff]
                              (let [jid-str (:jid aff)
                                    ^Jid jid (JidCreate/from ^CharSequence jid-str)
                                    username (str (.getLocalpartOrNull jid))]
                                [username (:affiliation aff)]))
                            affs))]))

        ;; Phase 6: Sync affiliations
        _ (swap! all-reports concat
                 (sync-affiliations ejabberd-api
                                    (:rooms working-state)
                                    (:members working-state)
                                    xmpp-domain
                                    muc-service
                                    (:admin-bot component)
                                    room-affiliations-map))

        ;; Phase 7: Sync bookmarks (uses raw affiliations list)
        _ (swap! all-reports concat
                 (sync-bookmarks ejabberd-api
                                 (:members working-state)
                                 (:rooms working-state)
                                 xmpp-domain
                                 muc-service
                                 room-affiliations-raw))

        ;; Phase 8: Update tracking state
        final-state-with-bot (update-tracking-state working-state)

        ;; Phase 9: Remove admin bot from final state (ghost removal)
        final-state (update final-state-with-bot
                            :members
                            (fn [members]
                              (vec (remove #(= (:user-id %) "admin") members))))]

    (tel/log! :info ["State synchronization complete" {:changes (count @all-reports)}])

    {:state final-state
     :report @all-reports}))

;; =============================================================================
;; Public API
;; =============================================================================

(defn read-lock-state
  [{:keys [user-db]}]
  (file-db/read-lock-state user-db))

(defn swap-state!
  "Atomically updates the user database by applying a function, validating, and syncing.
   
   Similar to clojure.core/swap! - takes the current state, applies the function,
   validates the result, syncs with ejabberd, then writes to disk.
   
   Parameters:
   - component: sync-state component (contains user-db, ejabberd-api, etc.)
   - f: Function that takes the current user-db state and returns the modified state
   - args: Additional arguments to pass to f (optional)
   
   Returns: map with keys:
   - :success? - true if successful, false if validation failed
   - :state - The final state (with sync updates applied) if successful
   - :errors - Validation errors if unsuccessful
   - :error-value - Error value from validation if unsuccessful
   - :report - Sync report if successful
   
   Usage:
   (swap-state! sync-component
                (fn [db]
                  (update db :members conj new-member)))
   
   (swap-state! sync-component
                update-in
                [:members 0 :name]
                \"New Name\")"
  [{:keys [user-db sync-timeout-s] :as component} f & args]
  (b/cond
    :let [{:keys [locked? reason expires-at]} (read-lock-state component)]

    locked?
    {:success? false
     :errors [(str "State is currently locked for " reason ". This will expire at the latest by " expires-at)]}

    :else
    (try
      (b/cond
        :do (tel/log! :info ["Starting swap-state!"])
        :let [current-db (file-db/read-user-db user-db)]

        :do (tel/log! :debug ["Applying swap function"])
        :let [new-db (apply f current-db args)]

        :do (tel/log! :debug ["Validating new state"])
        :let [validation-result (schema/validate-user-db new-db)]

        (not (:valid? validation-result))
        (do
          (tel/log! :warn ["Validation failed in swap-state!" validation-result])
          {:success? false
           :errors (:errors validation-result)
           :error-value (:error-value validation-result)})

        :do (file-db/lock-state! user-db "UI update in progress" (* sync-timeout-s 1000))
        :do (tel/log! :debug ["Validation passed, starting sync"])
        :let [sync-result (sync-state! component new-db)
              synced-state (:state sync-result)]

        :do (tel/log! :debug ["Sync complete, writing to disk"])
        :let [written-db (file-db/write-user-db user-db synced-state)]

        :do (tel/log! :info ["swap-state! completed successfully"])

        :else
        (assoc sync-result :success? true :state written-db))

      (catch Exception e
        (tel/log! :error
                  ["swap-state! failed with exception"
                   {:exception (ex-message e)
                    :data (ex-data e)}])
        {:success? false
         :errors [(ex-message e)]
         :error-value (or (ex-data e) e)})

      (finally
        (file-db/clear-lock! user-db)))))

(defn update-password
  "Updates a managed user's password in ejabberd.
   
   Does NOT modify user-db state (passwords not stored in config).
   
   Parameters:
   - component: sync-state component
   - user-id: User identifier (string)
   - new-password: New password (string)
   
   Returns: map with keys:
   - :success? - Boolean indicating success
   - :message - Human-readable result message
   
   Throws: ex-info if user is not managed or API call fails"
  [component user-id new-password]
  (tel/log! :info ["Updating password" {:user-id user-id}])

  (let [user-db (:user-db component)
        current-state (file-db/read-user-db user-db)
        managed-users (set (map :user-id (:members current-state)))]

    ;; Verify user is managed
    (when-not (contains? managed-users user-id)
      (throw (ex-info "User is not managed"
                      {:type :user-not-managed
                       :user-id user-id})))

    ;; Update password via API
    (try
      (api/change-password (:ejabberd-api component) user-id new-password)
      (tel/log! :info ["Password updated successfully" {:user-id user-id}])
      {:success? true
       :message "Password updated successfully"}
      (catch Exception e
        (tel/log! :error ["Password update failed" {:user-id user-id :error (ex-message e)}])
        {:success? false
         :message (str "Password update failed: " (ex-message e))}))))

;; =============================================================================
;; Integrant Component
;; =============================================================================

(defmethod ig/init-key ::sync-state
  [_ {:keys [ejabberd-api user-db admin-bot managed-muc-options env default-test-password sync-timeout-s]}]
  (tel/log! :info ["Initializing sync-state component"])
  (let [conf {:ejabberd-api ejabberd-api
              :user-db user-db
              :admin-bot admin-bot
              :managed-muc-options managed-muc-options
              :sync-timeout-s sync-timeout-s
              :env env
              :default-test-password default-test-password}]
    (def testing-conf* conf)
    conf))

(defmethod ig/halt-key! ::sync-state
  [_ _component]
  (tel/log! :debug ["Halting sync-state component"])
  nil)

(comment
  (def db (file-db/read-user-db (:user-db testing-conf*)))

  (def synced-db (sync-state! testing-conf* db))
  (file-db/write-user-db (:user-db testing-conf*) (:state synced-db)))

