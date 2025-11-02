(ns dev.freeformsoftware.ejabberd.ejabberd-api
  "Ejabberd API client component for managing users, MUC rooms, and rosters.
   
   This component wraps the ejabberd REST API and provides functions for:
   - User management (register, change_password, registered_users)
   - MUC room management (create_room, muc_online_rooms, set_room_affiliation, get_room_affiliations, get_room_options)
   - Roster management (get_roster, add_rosteritem, delete_rosteritem)"
  (:require
   [clj-http.client :as http]
   [cheshire.core :as json]
   [integrant.core :as ig]
   [taoensso.telemere :as tel]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; HTTP Client
;; =============================================================================

(defn- make-request
  "Make an HTTP request to the ejabberd API.
   Returns the parsed response body on success, or throws on error."
  [component endpoint payload]
  (let [url      (str (:admin-api-url component) "/" endpoint)
        opts     {:method           :post
                  :url              url
                  :headers          {"Content-Type" "application/json"}
                  :body             (json/generate-string payload)
                  :as               :json
                  :throw-exceptions false}
        response (http/request opts)]
    (tel/log! :debug
              ["API request"
               {:endpoint endpoint
                :payload  payload
                :status   (:status response)}])

    (tap> {:req opts :resp response})
    (if (= 200 (:status response))
      (:body response)
      (do
        (tel/log! :error
                  ["API request failed"
                   {:endpoint endpoint
                    :status   (:status response)
                    :body     (:body response)}])
        (throw (ex-info "API request failed"
                        {:endpoint endpoint
                         :status   (:status response)
                         :response (:body response)}))))))

;; =============================================================================
;; User Management
;; =============================================================================

(defn register
  "Register a new user with the given password.
   
   See: https://docs.ejabberd.im/developer/ejabberd-api/admin-api/#register
   
   Parameters:
   - component: ejabberd-api component
   - user: username (without @host)
   - password: user password
   
   Returns: API response"
  [component user password]
  (make-request component
                "register"
                {:user     user
                 :host     (:xmpp-domain component)
                 :password password}))

(defn change-password
  "Change a user's password.
   
   See: https://docs.ejabberd.im/developer/ejabberd-api/admin-api/#change_password
   
   Parameters:
   - component: ejabberd-api component
   - user: username (without @host)
   - newpass: new password
   
   Returns: API response"
  [component user newpass]
  (make-request component
                "change_password"
                {:user    user
                 :host    (:xmpp-domain component)
                 :newpass newpass}))

(defn registered-users
  "List all registered users for the host.
   
   See: https://docs.ejabberd.im/developer/ejabberd-api/admin-api/#registered_users
   
   Parameters:
   - component: ejabberd-api component
   
   Returns: vector of usernames"
  [component]
  (make-request component
                "registered_users"
                {:host (:xmpp-domain component)}))

(defn unregister
  "Unregister (delete) a user account.
   
   See: https://docs.ejabberd.im/developer/ejabberd-api/admin-api/#unregister
   
   Parameters:
   - component: ejabberd-api component
   - user: username (without @host)
   
   Returns: API response"
  [component user]
  (make-request component
                "unregister"
                {:user user
                 :host (:xmpp-domain component)}))

;; =============================================================================
;; MUC Room Management
;; =============================================================================

(defn create-room
  "Create a new MUC room.
   
   See: https://docs.ejabberd.im/developer/ejabberd-api/admin-api/#create_room
   See: https://docs.ejabberd.im/admin/configuration/modules/#mod_muc
   
   Parameters:
   - component: ejabberd-api component
   - name: room name (without @service)
   - service: (optional) MUC service, defaults to component :service
   
   Returns: API response"
  ([component name]
   (create-room component name (:muc-service component)))
  ([component name service]
   (make-request component
                 "create_room"
                 {:name    name
                  :service service
                  :host    (:xmpp-domain component)})))

(defn create-room-with-opts
  "Create a MUC room with configuration options.
   
   See: https://docs.ejabberd.im/developer/ejabberd-api/admin-api/#create_room_with_opts
   See: https://docs.ejabberd.im/admin/configuration/modules/#mod_muc (for all option descriptions)
   
   Parameters:
   - component: ejabberd-api component
   - name: room name (without @service)
   - opts: map of room options (e.g., {:members_only \"true\", :persistent \"true\"})
   - service: (optional) MUC service, defaults to component :service
   
   Available options (see mod_muc docs for full details):
   - allow_change_subj, allow_query_users, allow_subscription, allow_user_invites
   - allow_visitor_nickchange, allow_visitor_status, allow_voice_requests
   - allow_private_messages_from_visitors (\"anyone\" | \"moderators\" | \"nobody\")
   - allowpm (\"anyone\" | \"participants\" | \"moderators\" | \"none\")
   - anonymous, captcha_protected, description, enable_hats, lang, logging, mam
   - max_users, members_by_default, members_only, moderated
   - password, password_protected, persistent
   - presence_broadcast (e.g., \"[moderator,participant,visitor]\")
   - public, public_list, pubsub, title, vcard, vcard_xupdate
   - voice_request_min_interval
   - affiliations (e.g., \"owner=user1@host;member=user2@host\")
   - subscribers (e.g., \"user3@host=User3=messages=subject\")
   
   Returns: API response (0 on success, 1 otherwise)"
  ([component name opts]
   (create-room-with-opts component name opts (:muc-service component)))
  ([component name opts service]
   (let [options (map (fn [[k v]] {:name (clojure.core/name k) :value (str v)}) opts)]
     (make-request component
                   "create_room_with_opts"
                   {:name    name
                    :service service
                    :host    (:xmpp-domain component)
                    :options options}))))

(defn muc-online-rooms
  "List all online MUC rooms.
   
   See: https://docs.ejabberd.im/developer/ejabberd-api/admin-api/#muc_online_rooms
   
   Parameters:
   - component: ejabberd-api component
   - service: (optional) MUC service, defaults to component :service
   
   Returns: list of room information"
  ([component]
   (muc-online-rooms component (:muc-service component)))
  ([component service]
   (make-request component
                 "muc_online_rooms"
                 {:service service})))

(defn set-room-affiliation
  "Set affiliation for a user in a MUC room.
   
   See: https://docs.ejabberd.im/developer/ejabberd-api/admin-api/#set_room_affiliation
   
   Parameters:
   - component: ejabberd-api component
   - name: room name (without @service)
   - user: username (without @host)
   - host: user's host domain
   - affiliation: 'owner', 'admin', 'member', 'outcast', or 'none'
   - service: (optional) MUC service, defaults to component :service
   
   Returns: API response"
  ([component name user host affiliation]
   (set-room-affiliation component name user host affiliation (:muc-service component)))
  ([component name user host affiliation service]
   (make-request component
                 "set_room_affiliation"
                 {:room        name
                  :service     service
                  :user        user
                  :host        host
                  :affiliation affiliation})))

(defn get-room-affiliations
  "Get all user affiliations for a MUC room.
   
   See: https://docs.ejabberd.im/developer/ejabberd-api/admin-api/#get_room_affiliations
   
   Parameters:
   - component: ejabberd-api component
   - name: room name (without @service)
   - service: (optional) MUC service, defaults to component :service
   
   Returns: list of affiliations with JIDs and roles"
  ([component name]
   (get-room-affiliations component name (:muc-service component)))
  ([component name service]
   (make-request component
                 "get_room_affiliations"
                 {:room    name
                  :service service})))

(defn get-room-options
  "Get configuration options for a MUC room.
   
   See: https://docs.ejabberd.im/developer/ejabberd-api/admin-api/#get_room_options
   See: https://docs.ejabberd.im/admin/configuration/modules/#mod_muc
   
   Parameters:
   - component: ejabberd-api component
   - name: room name (without @service)
   - service: (optional) MUC service, defaults to component :service
   
   Returns: list of option maps with :name and :value keys"
  ([component name]
   (get-room-options component name (:muc-service component)))
  ([component name service]
   (make-request component
                 "get_room_options"
                 {:name    name
                  :service service})))

(defn destroy-room
  "Destroy a MUC room.
   
   See: https://docs.ejabberd.im/developer/ejabberd-api/admin-api/#destroy_room
   
   Parameters:
   - component: ejabberd-api component
   - name: room name (without @service)
   - service: (optional) MUC service, defaults to component :service
   
   Returns: API response"
  ([component name]
   (destroy-room component name (:muc-service component)))
  ([component name service]
   (make-request component
                 "destroy_room"
                 {:name    name
                  :service service})))

;; =============================================================================
;; Roster Management
;; =============================================================================

(defn get-roster
  "Get the roster (contact list) for a user.
   
   See: https://docs.ejabberd.im/developer/ejabberd-api/admin-api/#get_roster
   
   Parameters:
   - component: ejabberd-api component
   - user: username (without @host)
   
   Returns: list of roster items"
  [component user]
  (make-request component
                "get_roster"
                {:user user
                 :host (:xmpp-domain component)}))

(defn add-rosteritem
  "Add a contact to a user's roster (contact list).
   
   The roster is the XMPP contact list. Adding a roster item will send an IQ notification
   to the user's connected clients informing them of the new contact.
   
   See: https://docs.ejabberd.im/developer/ejabberd-api/admin-api/#add_rosteritem
   See: https://xmpp.org/rfcs/rfc6121.html (XMPP Roster specification)
   
   Parameters:
   - component: ejabberd-api component
   - localuser: local username (without @host) whose roster will be modified
   - localhost: local host domain (the user's server)
   - user: contact's username (without @host) to add
   - host: contact's host domain (the contact's server)
   - nick: display name/nickname for the contact
   - groups: vector/list of roster group names for organizing contacts (e.g., ['Friends', 'Work'])
   - subs: subscription type, controls presence visibility:
     - 'both': mutual presence sharing (both users see each other's presence)
     - 'to': you see their presence, they don't see yours
     - 'from': they see your presence, you don't see theirs
     - 'none': no presence sharing in either direction
   
   Returns: API response (0 on success, 1 on failure)"
  [component localuser localhost user host nick groups subs]
  (make-request component
                "add_rosteritem"
                {:localuser localuser
                 :localhost localhost
                 :user      user
                 :host      host
                 :nick      nick
                 :groups    groups
                 :subs      subs}))

(defn delete-rosteritem
  "Delete a contact from a user's roster (contact list).
   
   Removing a roster item will send an IQ notification to the user's connected clients
   informing them of the removed contact.
   
   See: https://docs.ejabberd.im/developer/ejabberd-api/admin-api/#delete_rosteritem
   See: https://xmpp.org/rfcs/rfc6121.html (XMPP Roster specification)
   
   Parameters:
   - component: ejabberd-api component
   - localuser: local username (without @host) whose roster will be modified
   - localhost: local host domain (the user's server)
   - user: contact's username (without @host) to remove
   - host: contact's host domain (the contact's server)
   
   Returns: API response (0 on success, 1 on failure)"
  [component localuser localhost user host]
  (make-request component
                "delete_rosteritem"
                {:localuser localuser
                 :localhost localhost
                 :user      user
                 :host      host}))

;; =============================================================================
;; Bookmark Management (XEP-0048)
;; =============================================================================

(defn- escape-xml-attr
  "Escapes XML attribute values (quotes, ampersands, etc.)"
  [s]
  (-> s
      (clojure.string/replace "&" "&amp;")
      (clojure.string/replace "\"" "&quot;")
      (clojure.string/replace "<" "&lt;")
      (clojure.string/replace ">" "&gt;")))

(defn- build-bookmark-xml
  "Builds XEP-0048 bookmark XML storage element.
   
   Parameters:
   - bookmarks: sequence of bookmark maps with keys:
     - :jid - Full room JID (e.g., 'room@conference.domain.com')
     - :name - Display name for the bookmark
     - :autojoin - Boolean, whether to auto-join on login (default: true)
     - :nick - Optional nickname to use in the room
   
   Returns: XML string for storage element
   
   Example:
   (build-bookmark-xml [{:jid \"announcements@conference.example.org\"
                         :name \"Announcements\"
                         :autojoin true}
                        {:jid \"dev@conference.example.org\"
                         :name \"Dev Team\"
                         :autojoin true
                         :nick \"Alice\"}])"
  [bookmarks]
  (let [conferences
        (for [bm bookmarks]
          (let [jid      (escape-xml-attr (:jid bm))
                name     (escape-xml-attr (:name bm))
                autojoin (if (false? (:autojoin bm)) "false" "true")
                nick     (:nick bm)]
            (if nick
              (str "<conference jid=\""
                   jid
                   "\" "
                   "autojoin=\""
                   autojoin
                   "\" "
                   "name=\""
                   name
                   "\">"
                   "<nick>" (escape-xml-attr nick)
                   "</nick>"
                   "</conference>")
              (str "<conference jid=\""
                   jid
                   "\" "
                   "autojoin=\""
                   autojoin
                   "\" "
                   "name=\""
                   name
                   "\"/>"))))]
    (str "<storage xmlns=\"storage:bookmarks\">"
         (apply str conferences)
         "</storage>")))

(defn get-user-bookmarks
  "Get all MUC bookmarks for a user.
   
   See: XEP-0048 (Bookmark Storage)
   
   Parameters:
   - component: ejabberd-api component
   - user: username (without @host)
   
   Returns: list of bookmark tuples [jid, name, autojoin, nick]
   Example: [[\"test-1@conference.example.org\" \"test-1\" \"true\" \"jj\"]
             [\"announcements@conference.example.org\" \"announcements\" \"true\" \"\"]]"
  [component user]
  (make-request component
                "get_user_bookmarks"
                {:user user
                 :host (:xmpp-domain component)}))

(defn set-user-bookmarks
  "Set all MUC bookmarks for a user (replaces existing bookmarks).
   
   IMPORTANT: This operation replaces ALL bookmarks. To add to existing bookmarks,
   first retrieve them with get-user-bookmarks, modify the list, then set.
   
   See: XEP-0048 (Bookmark Storage)
   
   Parameters:
   - component: ejabberd-api component
   - user: username (without @host)
   - bookmarks: sequence of bookmark maps with keys:
     - :jid - Full room JID (required)
     - :name - Display name (required)
     - :autojoin - Boolean (optional, defaults to true)
     - :nick - Nickname (optional)
   
   Returns: API response
   
   Example:
   (set-user-bookmarks component \"alice\"
                       [{:jid \"announcements@conference.example.org\"
                         :name \"Announcements\"
                         :autojoin true}
                        {:jid \"dev-team@conference.example.org\"
                         :name \"Dev Team\"
                         :autojoin true
                         :nick \"Alice\"}])"
  [component user bookmarks]
  (let [bookmarks-xml (build-bookmark-xml bookmarks)]
    (tel/log! :debug
              ["Setting user bookmarks"
               {:user           user
                :bookmark-count (count bookmarks)
                :xml            bookmarks-xml}])
    (make-request component
                  "set_user_bookmarks"
                  {:user          user
                   :host          (:xmpp-domain component)
                   :bookmarks_xml bookmarks-xml})))

;; =============================================================================
;; Component
;; =============================================================================

(defmethod ig/init-key ::ejabberd-api
  [_ {:keys [admin-api-url xmpp-domain muc-service]}]
  (tel/log! :info
            ["Initializing ejabberd-api component"
             {:admin-api-url admin-api-url
              :xmpp-domain   xmpp-domain
              :muc-service   muc-service}])
  (let [conf {:admin-api-url admin-api-url
              :xmpp-domain   xmpp-domain
              :muc-service   muc-service}]
    (def testing-conf* conf)
    conf))

(defmethod ig/halt-key! ::ejabberd-api
  [_ _component]
  (tel/log! :info ["Halting ejabberd-api component"]))

;; =============================================================================
;; Testing / REPL Usage
;; =============================================================================

(comment
  ;; Initialize a test component manually
  ;; =============================================================================. User Management Tests
  ;; =============================================================================

  ;; List all registered users
  (registered-users testing-conf*)

  ;; Register a new user
  (register testing-conf* "testuser" "testpass123")

  ;; Change a user's password
  (change-password testing-conf* "testuser" "newpass456")

  ;; =============================================================================
  ;; MUC Room Management Tests =============================================================================

  ;; List all MUC rooms (using default service)
  (muc-online-rooms testing-conf*)

  ;; List rooms for specific service
  (muc-online-rooms testing-conf* "conference.yourserverhere.org")

  ;; Create a new room
  (create-room testing-conf* "testroom")

  ;; Set user as owner (using default service)
  (set-room-affiliation testing-conf*
                        "testroom"
                        "testuser@yourserverhere.org"
                        "owner")

  ;; Set user as member
  (set-room-affiliation testing-conf*
                        "testroom"
                        "alice@yourserverhere.org"
                        "member")

  ;; Get room affiliations
  (get-room-affiliations testing-conf* "zomux")

  ;; Get room options
  (get-room-options testing-conf* "zomux")

  ;; Remove user from room (set affiliation to none)
  (set-room-affiliation testing-conf*
                        "testroom"
                        "alice@yourserverhere.org"
                        "none")

  ;; =============================================================================. Roster Management Tests
  ;; =============================================================================

  ;; Get roster for a user
  (get-roster testing-conf* "trey")

  ;; Add a contact to roster
  (add-rosteritem testing-conf*
                  "testuser"
                  "yourserverhere.org"
                  "alice"
                  "yourserverhere.org"
                  "Alice"
                  "Friends"
                  "both")

  ;; Remove a contact from roster
  (delete-rosteritem testing-conf*
                     "testuser"
                     "yourserverhere.org"
                     "alice"
                     "yourserverhere.org")

  ;; =============================================================================. Full Workflow Example
  ;; =============================================================================

  ;; Complete workflow: create users, room, and add users to room
  (do
    ;; Create users
    (register testing-conf* "alice" "password123")
    (register testing-conf* "bob" "password456")

    ;; Create a room
    (create-room testing-conf* "team-chat")

    ;; Set alice as owner
    (set-room-affiliation testing-conf*
                          "team-chat"
                          "alice@yourserverhere.org"
                          "owner")

    ;; Set bob as member
    (set-room-affiliation testing-conf*
                          "team-chat"
                          "bob@yourserverhere.org"
                          "member")

    ;; Add them to each other's rosters
    (add-rosteritem testing-conf*
                    "alice"
                    "yourserverhere.org"
                    "bob"
                    "yourserverhere.org"
                    "Bob"
                    "Team"
                    "both")

    (add-rosteritem testing-conf*
                    "bob"
                    "yourserverhere.org"
                    "alice"
                    "yourserverhere.org"
                    "Alice"
                    "Team"
                    "both")

    ;; Check the results
    {:room-affiliations (get-room-affiliations testing-conf* "team-chat")
     :alice-roster      (get-roster testing-conf* "alice")
     :bob-roster        (get-roster testing-conf* "bob")})

  ;; =============================================================================. Utility: Delete All Rooms
  ;; =============================================================================

  ;; Delete all discovered rooms from the server
  (let [all-rooms  (muc-online-rooms testing-conf*)
        room-names (map #(first (clojure.string/split % #"@")) all-rooms)]
    (doseq [room-name room-names]
      (println "Deleting room:" room-name)
      (try
        (destroy-room testing-conf* room-name)
        (println "  ✓ Deleted successfully")
        (catch Exception e
          (println "  ✗ Failed:" (ex-message e)))))
    (println "\nRooms remaining:" (count (muc-online-rooms testing-conf*)))))
