(ns dev.freeformsoftware.db.schema
  (:require
   [malli.core :as m]
   [malli.error :as me]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Helper schemas and validators
;; =============================================================================

(def non-blank-string
  [:and
   :string
   [:fn {:error/message "must not be blank"}
    (fn [s] (not (clojure.string/blank? s)))]])

(def sanitized-entity-id
  "Schema for entity IDs (user-id, room-id) that conform to ejabberd JID requirements.
   
   Valid entity IDs must:
   - Contain only lowercase letters (a-z), digits (0-9), and hyphens (-)
   - Not start or end with a hyphen
   
   This matches the output format of str->entity-id in sync-state."
  [:and
   :string
   [:fn {:error/message "must not be blank"}
    (fn [s] (not (clojure.string/blank? s)))]
   [:re {:error/message "must contain only lowercase letters, digits, and hyphens (no leading/trailing hyphens)"}
    #"^[a-z0-9]+([a-z0-9-]*[a-z0-9]+)?$"]])

(defn unique-by
  "Creates a validator for uniqueness of a collection by a given key function.
   f - function to extract the value to check for uniqueness
   error-msg - human readable error message"
  [f error-msg]
  [:fn {:error/message error-msg}
   (fn [coll]
     (= (count coll)
        (count (distinct (map f coll)))))])

(defn contains-all?
  "Creates a validator that checks if collection contains all required items.
   required - collection of items that must be present
   error-msg - human readable error message"
  [required error-msg]
  [:fn {:error/message error-msg}
   (fn [coll]
     (every? #(contains? (set coll) %) required))])

(def ^:dynamic *valid-groups*
  "Dynamic var containing the set of valid group keywords during validation.
   Used by room and member schemas to validate group references."
  nil)

(def ^:dynamic *duplicate-names*
  "Dynamic var containing the set of duplicate member names during validation.
   Used to flag non-unique member names."
  nil)

(def ^:dynamic *duplicate-user-ids*
  "Dynamic var containing the set of duplicate user-ids during validation.
   Used to flag non-unique member user-ids."
  nil)

(defn subset-of?
  "Creates a validator that checks if all items in a set are members of *valid-groups*.
   Uses the dynamic var *valid-groups* to access the allowed keys.
   error-msg - human readable error message"
  [error-msg]
  [:fn {:error/message error-msg}
   (fn [items]
     (if *valid-groups*
       (every? #(contains? *valid-groups* %) items)
       true))])

(defn not-duplicate?
  "Creates a validator that checks if a value is not in the duplicate set.
   duplicate-set-var - dynamic var containing duplicate values
   error-msg - human readable error message"
  [duplicate-set-var error-msg]
  [:fn {:error/message error-msg}
   (fn [value]
     (if (var-get duplicate-set-var)
       (not (contains? (var-get duplicate-set-var) value))
       true))])

;; =============================================================================
;; Group schema
;; =============================================================================

(def groups-schema
  "Schema for :groups map. Must contain :group/owner and :group/bot. All titles must be unique."
  [:and
   [:map-of {:closed true} :keyword non-blank-string]
   [:fn {:error/message "must contain :group/owner"}
    (fn [groups]
      (contains? groups :group/owner))]
   [:fn {:error/message "must contain :group/bot"}
    (fn [groups]
      (contains? groups :group/bot))]
   [:fn {:error/message "group titles must be unique"}
    (fn [groups]
      (= (count (vals groups))
         (count (distinct (vals groups)))))]])

;; =============================================================================
;; Room schema (can be validated independently)
;; =============================================================================

(def room-schema
  "Schema for a room. Uses dynamic var *valid-groups* for validation."
  [:map {:closed true}
   [:name non-blank-string]
   [:room-id {:optional true} sanitized-entity-id]
   [:members
    [:and
     [:set :keyword]
     [:fn {:error/message "must not be empty"}
      seq]
     (subset-of? "members must only reference groups defined in :groups")]]
   [:admins
    [:and
     [:set :keyword]
     [:fn {:error/message "must not be empty"}
      seq]
     (subset-of? "admins must only reference groups defined in :groups")]]
   [:only-admins-can-speak? :boolean]])

(def rooms-schema
  "Schema for the :rooms collection. Validates room names are unique."
  [:and
   [:vector room-schema]
   (unique-by :name "room names must be unique")])

;; =============================================================================
;; Member schema (can be validated independently)
;; =============================================================================

(def member-schema
  "Schema for a member. Uses dynamic vars for validation:
   - *valid-groups* for group references
   - *duplicate-names* for name uniqueness
   - *duplicate-user-ids* for user-id uniqueness"
  [:map {:closed true}
   [:name
    [:and
     non-blank-string
     (not-duplicate? #'*duplicate-names* "name is already used by another member")]]
   [:user-id
    [:and
     sanitized-entity-id
     (not-duplicate? #'*duplicate-user-ids* "user-id is already used by another member")]]
   [:groups
    [:and
     [:set :keyword]
     [:fn {:error/message "must not be empty"}
      seq]
     (subset-of? "groups must only reference groups defined in :groups")]]])

(def members-schema
  "Schema for the :members collection.
   Note: Uniqueness of names and user-ids is validated per-member via dynamic vars."
  [:vector member-schema])

;; =============================================================================
;; Do-not-edit-state schema (for tracking managed entities)
;; =============================================================================

(def do-not-edit-state-schema
  "Schema for :do-not-edit-state, which tracks entities under management.
   This allows detection of user deletions by diffing against current state.
   
   Optional :admin-credentials stores the admin bot's username and password."
  [:map {:closed true}
   [:managed-groups [:set :keyword]]
   [:managed-rooms [:set :string]]
   [:managed-members [:set :string]]
   [:admin-credentials {:optional true}
    [:map {:closed true}
     [:username :string]
     [:password :string]]]])

;; =============================================================================
;; Full database schema
;; =============================================================================

(def user-db-schema
  "Complete schema for the user database.
   Validates groups first, then uses dynamic binding to validate rooms and members.
   :do-not-edit-state is optional and maintained automatically by the file-interaction component.
   :!allow-insecure-signup-for-user is optional and if set, enables a /getting-started route
   that provides a pre-signed signup link for the specified user-id (INSECURE - for development only)."
  [:map {:closed true}
   [:groups groups-schema]
   [:rooms rooms-schema]
   [:members members-schema]
   [:do-not-edit-state {:optional true} do-not-edit-state-schema]
   [:!allow-insecure-signup-for-user {:optional true} non-blank-string]])

;; =============================================================================
;; Validation functions with human-readable errors
;; =============================================================================

(defn- remove-nil-values
  "Recursively removes nil values from maps and vectors to clean up error output."
  [data]
  (cond
    (map? data)
    (into {}
          (keep (fn [[k v]]
                  (let [cleaned (remove-nil-values v)]
                    (when (not (nil? cleaned))
                      [k cleaned])))
                data))

    (vector? data)
    (let [cleaned (into [] (keep remove-nil-values data))]
      (when (seq cleaned) cleaned))

    :else data))

(defn- find-duplicates
  "Returns a set of duplicate values from a collection."
  [coll]
  (let [freqs (frequencies coll)]
    (set (keep (fn [[v cnt]] (when (> cnt 1) v)) freqs))))

(defn validate-groups
  "Validates groups map and returns humanized errors if invalid."
  [groups]
  (if (m/validate groups-schema groups)
    {:valid? true}
    (let [explanation (m/explain groups-schema groups)]
      {:valid?      false
       :errors      (-> explanation
                        (me/with-spell-checking)
                        (me/humanize))
       :error-value (me/error-value explanation {::me/mask-valid-values '...})})))

(defn validate-room
  "Validates a single room against valid groups and returns humanized errors if invalid."
  [room valid-groups]
  (binding [*valid-groups* (set valid-groups)]
    (if (m/validate room-schema room)
      {:valid? true}
      (let [explanation (m/explain room-schema room)
            errors      (-> explanation
                            (me/with-spell-checking)
                            (me/humanize))
            error-value (me/error-value explanation {::me/mask-valid-values '...})]
        {:valid?      false
         :errors      (remove-nil-values errors)
         :error-value (remove-nil-values error-value)}))))

(defn validate-rooms
  "Validates rooms collection against valid groups and returns humanized errors if invalid."
  [rooms valid-groups]
  (binding [*valid-groups* (set valid-groups)]
    (if (m/validate rooms-schema rooms)
      {:valid? true}
      (let [explanation (m/explain rooms-schema rooms)
            errors      (-> explanation
                            (me/with-spell-checking)
                            (me/humanize))
            error-value (me/error-value explanation {::me/mask-valid-values '...})]
        {:valid?      false
         :errors      (remove-nil-values errors)
         :error-value (remove-nil-values error-value)}))))

(defn validate-member
  "Validates a single member against valid groups and returns humanized errors if invalid."
  [member valid-groups]
  (binding [*valid-groups* (set valid-groups)]
    (if (m/validate member-schema member)
      {:valid? true}
      (let [explanation (m/explain member-schema member)
            errors      (-> explanation
                            (me/with-spell-checking)
                            (me/humanize))
            error-value (me/error-value explanation {::me/mask-valid-values '...})]
        {:valid?      false
         :errors      (remove-nil-values errors)
         :error-value (remove-nil-values error-value)}))))

(defn validate-members
  "Validates members collection against valid groups and returns humanized errors if invalid.
   Uses dynamic binding to detect duplicate names and user-ids."
  [members valid-groups]
  (let [duplicate-names    (find-duplicates (map :name members))
        duplicate-user-ids (find-duplicates (map :user-id members))]
    (binding [*valid-groups*       (set valid-groups)
              *duplicate-names*    duplicate-names
              *duplicate-user-ids* duplicate-user-ids]
      (if (m/validate members-schema members)
        {:valid? true}
        (let [explanation (m/explain members-schema members)
              errors      (-> explanation
                              (me/with-spell-checking)
                              (me/humanize))
              error-value (me/error-value explanation {::me/mask-valid-values '...})]
          {:valid?      false
           :errors      (remove-nil-values errors)
           :error-value (remove-nil-values error-value)})))))

(defn validate-user-db
  "Validates entire user database and returns humanized errors if invalid.
   This is the main validation function to use for a complete user-db.edn file.
   Uses dynamic binding to provide *valid-groups* context during validation.
   
   The :_file-sha256 key is allowed but ignored during validation."
  [db]
  ;; Remove internal keys before validation
  (let [db-to-validate (dissoc db :_file-sha256)]
    ;; First validate groups to ensure they're valid
    (let [groups-result (validate-groups (:groups db-to-validate))]
      (if-not (:valid? groups-result)
        ;; If groups are invalid, return early with groups errors
        {:valid?      false
         :errors      {:groups (:errors groups-result)}
         :error-value {:groups (:error-value groups-result)}}
        ;; Groups are valid, now validate rooms and members separately
        (let [valid-groups   (keys (:groups db-to-validate))
              rooms-result   (validate-rooms (:rooms db-to-validate) valid-groups)
              members-result (validate-members (:members db-to-validate) valid-groups)]
          (if (and (:valid? rooms-result) (:valid? members-result))
            {:valid? true}
            {:valid?      false
             :errors      (remove-nil-values
                           (merge {}
                                  (when-not (:valid? rooms-result) {:rooms (:errors rooms-result)})
                                  (when-not (:valid? members-result) {:members (:errors members-result)})))
             :error-value (remove-nil-values
                           (merge {}
                                  (when-not (:valid? rooms-result) {:rooms (:error-value rooms-result)})
                                  (when-not (:valid? members-result) {:members (:error-value members-result)})))}))))))

;; =============================================================================
;; Spell checking
;; =============================================================================
;; Note: Spell checking is integrated into all validate-* functions via
;; me/with-spell-checking, which automatically detects misspelled keys in
;; closed maps and suggests corrections.

;; =============================================================================
;; REPL Usage Examples
;; =============================================================================
(comment
  ;; Validate a user-db.edn file from disk
  (require '[clojure.edn :as edn])
  (require '[clojure.pprint :as pp])

  ;; Load and validate the default user database
  (def db
    (edn/read-string
     (slurp
      "/home/jarrett/code/personal/ejabberd-management-portal/resources/config/default-user-db copy 2.edn")))
  (validate-user-db
   (edn/read-string
    (slurp
     "/home/jarrett/code/personal/ejabberd-management-portal/resources/config/default-user-db copy 2.edn")))

  ;; Pretty print the result
  (pp/pprint result)
  ;; => {:valid? true}

  ;; Or if there are errors: => {:valid? false
  ;;     :errors {:groups ["must contain :group/owner"]
  ;;              :rooms [{:name ["must not be blank"]}]
  ;;              :members [{:groups ["must not be empty"]}]}
  ;;     :error-value {...}}

  ;; Validate just groups
  (validate-groups (:groups db))

  ;; Validate a single room
  (validate-room (first (:rooms db)) (keys (:groups db)))

  ;; Validate all rooms
  (validate-rooms (:rooms db) (keys (:groups db)))

  ;; Validate a single member
  (validate-member (first (:members db)) (keys (:groups db)))

  ;; Validate all members
  (validate-members (:members db) (keys (:groups db))))
