(ns dev.freeformsoftware.db.schema-test
  (:require
   [clojure.edn :as edn]
   [fulcro-spec.core :refer [specification =check=> behavior assertions]]
   [fulcro-spec.check :as check :refer [checker]]
   [dev.freeformsoftware.db.schema :as schema]))

;; =============================================================================
;; Test data - Load from actual config file
;; =============================================================================

(def base-db
  "Load the actual default-user-db.edn as the base for all tests"
  (edn/read-string (slurp "resources/config/default-user-db.edn")))

(def valid-groups (:groups base-db))
(def valid-room (first (:rooms base-db)))
(def valid-member (first (:members base-db)))

;; =============================================================================
;; Custom checkers
;; =============================================================================

(def valid? (checker [result] (true? (:valid? result))))
(def invalid? (checker [result] (false? (:valid? result))))
(def has-errors?
  (checker [result]
           (and (false? (:valid? result))
                (some? (:errors result)))))

(defn has-error-at-path?
  [path]
  (checker [result]
           (some? (get-in result (concat [:errors] path)))))

(defn contains-error-message?
  [msg]
  (checker [result]
           (let [errors (:errors result)]
             (cond
               (string? errors) (clojure.string/includes? errors msg)
               (map? errors)    (some #(and (string? %) (clojure.string/includes? % msg))
                                      (tree-seq coll? seq errors))
               :else            false))))

;; =============================================================================
;; Groups validation tests
;; =============================================================================

(specification "Groups validation"
  (behavior "accepts valid groups from base-db"
    (assertions
     "base-db groups pass validation"
     (schema/validate-groups valid-groups)
     =check=>
     valid?))

  (behavior "requires :group/owner"
    (let [groups-without-owner (dissoc valid-groups :group/owner)]
      (assertions
       "missing :group/owner fails validation"
       (schema/validate-groups groups-without-owner)
       =check=>
       (check/and* invalid?
                   (contains-error-message? "must contain :group/owner")))))

  (behavior "requires unique titles"
    (let [groups-with-dup-titles (assoc valid-groups :group/duplicate "Owner")]
      (assertions
       "duplicate titles fail validation"
       (schema/validate-groups groups-with-dup-titles)
       =check=>
       (check/and* invalid?
                   (contains-error-message? "group titles must be unique")))))

  (behavior "requires non-blank string titles"
    (let [groups-with-blank (assoc valid-groups :group/blank "")]
      (assertions
       "blank string titles fail validation"
       (schema/validate-groups groups-with-blank)
       =check=>
       invalid?)))

  (behavior "enforces closed maps"
    (assertions
     "extra keys fail validation"
     (schema/validate-groups (assoc valid-groups :extra-key "value"))
     =check=>
     invalid?)))

;; =============================================================================
;; Room validation tests
;; =============================================================================

(specification "Room validation"
  (behavior "accepts valid rooms from base-db"
    (assertions
     "base-db room passes validation"
     (schema/validate-room valid-room (keys valid-groups))
     =check=>
     valid?

     "room with optional :room-id passes validation"
     (schema/validate-room (assoc valid-room :room-id "test-room-id")
                           (keys valid-groups))
     =check=>
     valid?))

  (behavior "validates room name"
    (let [room-with-blank-name (assoc valid-room :name "")]
      (assertions
       "blank name fails validation"
       (schema/validate-room room-with-blank-name (keys valid-groups))
       =check=>
       (check/and* invalid?
                   (has-error-at-path? [:name])))))

  (behavior "validates members"
    (let [room-with-invalid-members (assoc valid-room :members #{:group/invalid})]
      (assertions
       "members referencing invalid groups fail validation"
       (schema/validate-room room-with-invalid-members (keys valid-groups))
       =check=>
       (check/and* invalid?
                   (has-error-at-path? [:members]))))

    (let [room-with-empty-members (assoc valid-room :members #{})]
      (assertions
       "empty members set fails validation"
       (schema/validate-room room-with-empty-members (keys valid-groups))
       =check=>
       (check/and* invalid?
                   (has-error-at-path? [:members])))))

  (behavior "validates admins"
    (let [room-with-invalid-admins (assoc valid-room :admins #{:group/invalid})]
      (assertions
       "admins referencing invalid groups fail validation"
       (schema/validate-room room-with-invalid-admins (keys valid-groups))
       =check=>
       (check/and* invalid?
                   (has-error-at-path? [:admins]))))

    (let [room-with-empty-admins (assoc valid-room :admins #{})]
      (assertions
       "empty admins set fails validation"
       (schema/validate-room room-with-empty-admins (keys valid-groups))
       =check=>
       (check/and* invalid?
                   (has-error-at-path? [:admins])))))

  (behavior "validates only-admins-can-speak?"
    (let [room-with-invalid-boolean (assoc valid-room :only-admins-can-speak? "not-boolean")]
      (assertions
       "non-boolean value fails validation"
       (schema/validate-room room-with-invalid-boolean (keys valid-groups))
       =check=>
       invalid?)))

  (behavior "enforces closed maps"
    (let [room-with-extra-key (assoc valid-room :extra-key "value")]
      (assertions
       "extra keys fail validation"
       (schema/validate-room room-with-extra-key (keys valid-groups))
       =check=>
       invalid?))))

(specification "Rooms collection validation"
  (behavior "accepts valid rooms collections from base-db"
    (let [rooms (:rooms base-db)]
      (assertions
       "all base-db rooms pass validation"
       (schema/validate-rooms rooms (keys valid-groups))
       =check=>
       valid?))

    (let [multiple-rooms [valid-room (assoc valid-room :name "Another Room")]]
      (assertions
       "multiple unique rooms pass validation"
       (schema/validate-rooms multiple-rooms (keys valid-groups))
       =check=>
       valid?)))

  (behavior "requires unique room names"
    (let [duplicate-rooms [valid-room valid-room]]
      (assertions
       "duplicate room names fail validation"
       (schema/validate-rooms duplicate-rooms (keys valid-groups))
       =check=>
       (check/and* invalid?
                   (contains-error-message? "room names must be unique"))))))

;; =============================================================================
;; Member validation tests
;; =============================================================================

(specification "Member validation"
  (behavior "accepts valid members from base-db"
    (assertions
     "base-db member passes validation"
     (schema/validate-member valid-member (keys valid-groups))
     =check=>
     valid?))

  (behavior "validates member name"
    (let [member-with-blank-name (assoc valid-member :name "")]
      (assertions
       "blank name fails validation"
       (schema/validate-member member-with-blank-name (keys valid-groups))
       =check=>
       (check/and* invalid?
                   (has-error-at-path? [:name])))))

  (behavior "validates member user-id"
    (let [member-with-blank-userid (assoc valid-member :user-id "")]
      (assertions
       "blank user-id fails validation"
       (schema/validate-member member-with-blank-userid (keys valid-groups))
       =check=>
       (check/and* invalid?
                   (has-error-at-path? [:user-id])))))

  (behavior "validates member groups"
    (let [member-with-invalid-groups (assoc valid-member :groups #{:group/owner :group/invalid})]
      (assertions
       "groups must reference valid groups"
       (schema/validate-member member-with-invalid-groups (keys valid-groups))
       =check=>
       (check/and* invalid?
                   (has-error-at-path? [:groups]))))

    (let [member-with-empty-groups (assoc valid-member :groups #{})]
      (assertions
       "groups must not be empty"
       (schema/validate-member member-with-empty-groups (keys valid-groups))
       =check=>
       (check/and* invalid?
                   (has-error-at-path? [:groups])))))

  (behavior "enforces closed maps"
    (let [member-with-extra-key (assoc valid-member :extra-key "value")]
      (assertions
       "extra keys fail validation"
       (schema/validate-member member-with-extra-key (keys valid-groups))
       =check=>
       invalid?))))

(specification "Members collection validation"
  (behavior "accepts valid members collections from base-db"
    (let [members (:members base-db)]
      (assertions
       "all base-db members pass validation"
       (schema/validate-members members (keys valid-groups))
       =check=>
       valid?))

    (let [multiple-members [valid-member
                            (assoc valid-member :name "Another User" :user-id "anotheruser")]]
      (assertions
       "multiple unique members pass validation"
       (schema/validate-members multiple-members (keys valid-groups))
       =check=>
       valid?)))

  (behavior "requires unique member names"
    (let [members-with-dup-names [valid-member (assoc valid-member :user-id "different")]]
      (assertions
       "duplicate names fail validation"
       (schema/validate-members members-with-dup-names (keys valid-groups))
       =check=>
       (check/and* invalid?
                   (contains-error-message? "member names must be unique")))))

  (behavior "requires unique member user-ids"
    (let [members-with-dup-ids [valid-member (assoc valid-member :name "Different Name")]]
      (assertions
       "duplicate user-ids fail validation"
       (schema/validate-members members-with-dup-ids (keys valid-groups))
       =check=>
       (check/and* invalid?
                   (contains-error-message? "member user-ids must be unique"))))))

;; =============================================================================
;; Full database validation tests
;; =============================================================================

(specification "User database validation"
  (behavior "accepts the base-db as valid"
    (assertions
     "base-db passes validation"
     (schema/validate-user-db base-db)
     =check=>
     valid?))

  (behavior "accepts valid databases with modifications"
    (let [db-with-extra-room (update base-db
                                     :rooms
                                     conj
                                     (assoc valid-room :name "Extra Room"))]
      (assertions
       "database with additional room passes validation"
       (schema/validate-user-db db-with-extra-room)
       =check=>
       valid?))

    (let [db-with-extra-member (update base-db
                                       :members
                                       conj
                                       (assoc valid-member :name "Extra User" :user-id "extrauser"))]
      (assertions
       "database with additional member passes validation"
       (schema/validate-user-db db-with-extra-member)
       =check=>
       valid?)))

  (behavior "requires all top-level keys"
    (assertions
     "missing :groups fails validation"
     (schema/validate-user-db (dissoc base-db :groups))
     =check=>
     invalid?

     "missing :rooms fails validation"
     (schema/validate-user-db (dissoc base-db :rooms))
     =check=>
     invalid?

     "missing :members fails validation"
     (schema/validate-user-db (dissoc base-db :members))
     =check=>
     invalid?))

  (behavior "validates cross-references"
    (let [db-without-owner (assoc base-db :groups (dissoc valid-groups :group/owner))]
      (assertions
       "invalid groups propagate to validation failure"
       (schema/validate-user-db db-without-owner)
       =check=>
       invalid?))

    (let [db-with-bad-room (assoc base-db
                                  :rooms
                                  [{:name                   "Bad Room"
                                    :members                #{:group/nonexistent}
                                    :admins                 #{:group/owner}
                                    :only-admins-can-speak? false}])]
      (assertions
       "rooms referencing non-existent groups fail"
       (schema/validate-user-db db-with-bad-room)
       =check=>
       invalid?))

    (let [db-with-bad-member (assoc base-db
                                    :members
                                    [{:name    "Bad User"
                                      :user-id "baduser"
                                      :groups  #{:group/invalid}}])]
      (assertions
       "members referencing non-existent groups fail"
       (schema/validate-user-db db-with-bad-member)
       =check=>
       invalid?)))

  (behavior "enforces closed maps"
    (let [db-with-extra-key (assoc base-db :extra-key "value")]
      (assertions
       "extra top-level keys fail validation"
       (schema/validate-user-db db-with-extra-key)
       =check=>
       invalid?))))

;; =============================================================================
;; Spell checking tests
;; =============================================================================

(specification "Spell checking for rooms"
  (behavior "detects and suggests corrections for misspelled keys"
    (let [room-with-typo (-> valid-room
                             (dissoc :members)
                             (assoc :memebrs #{:group/member}))
          result         (schema/validate-room room-with-typo (keys valid-groups))]
      (assertions
       "detects typo in :members"
       result
       =check=>
       invalid?
       "provides error for typo"
       (:errors result)
       =check=>
       (check/exists?*)))

    (let [room-with-multiple-typos (-> valid-room
                                       (dissoc :name :members)
                                       (assoc :nam "Test")
                                       (assoc :memebrs #{:group/member}))
          result                   (schema/validate-room room-with-multiple-typos (keys valid-groups))]
      (assertions
       "detects multiple typos"
       result
       =check=>
       invalid?
       "provides errors for typos"
       (:errors result)
       =check=>
       (check/exists?*)))))

(specification "Spell checking for members"
  (behavior "detects and suggests corrections for misspelled keys"
    (let [member-with-typo (-> valid-member
                               (dissoc :groups)
                               (assoc :grup #{:group/owner}))
          result           (schema/validate-member member-with-typo (keys valid-groups))]
      (assertions
       "detects typo in :groups"
       result
       =check=>
       invalid?
       "provides error for typo"
       (:errors result)
       =check=>
       (check/exists?*)))

    (let [member-with-userid-typo (-> valid-member
                                      (dissoc :user-id)
                                      (assoc :userid "testuser"))
          result                  (schema/validate-member member-with-userid-typo (keys valid-groups))]
      (assertions
       "detects typo in :user-id"
       result
       =check=>
       invalid?
       "provides error for typo"
       (:errors result)
       =check=>
       (check/exists?*)))))

(specification "Spell checking for database"
  (behavior "detects and suggests corrections for misspelled keys"
    (let [db-with-typo (-> base-db
                           (dissoc :members)
                           (assoc :memers []))
          result       (schema/validate-user-db db-with-typo)]
      (assertions
       "detects typo in top-level key"
       result
       =check=>
       invalid?
       "provides error for typo"
       (:errors result)
       =check=>
       (check/exists?*)))

    (let [db-with-multiple-typos (-> base-db
                                     (dissoc :members :rooms)
                                     (assoc :memers [])
                                     (assoc :room []))
          result                 (schema/validate-user-db db-with-multiple-typos)]
      (assertions
       "detects multiple typos"
       result
       =check=>
       invalid?
       "provides errors for typos"
       (:errors result)
       =check=>
       (check/exists?*)))))

;; =============================================================================
;; Integration tests with realistic modifications to base-db
;; =============================================================================

(specification "Integration: Multiple errors across database"
  (behavior "detects and reports all errors together"
    (let [db-with-multiple-errors
          (-> base-db
              ;; Break groups: remove :group/owner and add duplicate title
              (assoc :groups
                     {:group/officer "Officer"
                      :group/member  "Member"
                      :group/staff   "Officer"}) ; duplicate title
              ;; Break rooms: duplicate name and invalid group ref
              (assoc :rooms
                     [{:name                   "Room One"
                       :members                #{:group/invalid}
                       :admins                 #{}
                       :only-admins-can-speak? false}
                      {:name                   "Room One" ; duplicate
                       :members                #{:group/member}
                       :admins                 #{:group/officer}
                       :only-admins-can-speak? true}])
              ;; Break members: blank fields and invalid groups
              (assoc :members
                     [{:name    ""
                       :user-id "user1"
                       :groups  #{:group/nonexistent}}
                      {:name    "User Two"
                       :user-id ""
                       :groups  #{:group/invalid}}]))
          result (schema/validate-user-db db-with-multiple-errors)]
      (assertions
       "detects database is invalid"
       result
       =check=>
       invalid?
       "reports errors in groups (validation stops at first failure)"
       (get-in result [:errors :groups])
       =check=>
       (check/exists?*)))))

(specification "Integration: Duplicate detection"
  (behavior "detects duplicate room names"
    (let [db-with-dup-rooms (update base-db
                                    :rooms
                                    (fn [rooms]
                                      (conj rooms (first rooms))))
          result            (schema/validate-user-db db-with-dup-rooms)]
      (assertions
       "detects duplicate rooms"
       result
       =check=>
       invalid?
       "error mentions room names"
       result
       =check=>
       (contains-error-message? "room names must be unique"))))

  (behavior "detects duplicate member names and user-ids"
    (let [db-with-dup-members (update base-db
                                      :members
                                      (fn [members]
                                        (conj members
                                              (assoc (first members) :user-id "newid")
                                              (assoc (first members) :name "New Name"))))
          result              (schema/validate-user-db db-with-dup-members)]
      (assertions
       "detects duplicate members"
       result
       =check=>
       invalid?))))

(specification "Integration: Cross-reference validation"
  (behavior "validates room members reference valid groups"
    (let [new-group-key       :group/special
          db-with-new-group   (assoc-in base-db [:groups new-group-key] "Special Group")
          room-with-new-group (assoc valid-room :members #{new-group-key})
          db-with-room        (update db-with-new-group :rooms conj room-with-new-group)
          result              (schema/validate-user-db db-with-room)]
      (assertions
       "accepts room with newly defined group"
       result
       =check=>
       valid?)))

  (behavior "rejects room members that don't reference valid groups"
    (let [room-with-bad-ref {:name                   "Bad Room"
                             :members                #{:group/undefined}
                             :admins                 #{:group/owner}
                             :only-admins-can-speak? false}
          db-with-bad-room  (update base-db :rooms conj room-with-bad-ref)
          result            (schema/validate-user-db db-with-bad-room)]
      (assertions
       "detects invalid group reference"
       result
       =check=>
       invalid?
       "mentions groups must be defined"
       result
       =check=>
       (contains-error-message? "must only reference groups defined in :groups")))))

;; =============================================================================
;; Error message quality tests
;; =============================================================================

(specification "Error messages are human-readable"
  (behavior "provides clear error messages for common mistakes"
    (let [groups-missing-owner (dissoc valid-groups :group/owner)
          result               (schema/validate-groups groups-missing-owner)]
      (assertions
       "groups missing :group/owner has readable error"
       (:errors result)
       =check=>
       (check/exists?*)
       "mentions :group/owner"
       result
       =check=>
       (contains-error-message? "must contain :group/owner")))

    (let [member-with-invalid-groups (assoc valid-member :groups #{:group/invalid})
          result (schema/validate-member member-with-invalid-groups (keys valid-groups))]
      (assertions
       "invalid member groups has readable error"
       (map? (:errors result))
       =>
       true
       "has error at :groups path"
       (some? (get-in result [:errors :groups]))
       =>
       true))

    (let [rooms-with-duplicates [valid-room valid-room]
          result                (schema/validate-rooms rooms-with-duplicates (keys valid-groups))]
      (assertions
       "duplicate room names has readable error"
       (:errors result)
       =check=>
       (check/exists?*)
       "mentions uniqueness"
       result
       =check=>
       (contains-error-message? "must be unique")))))
