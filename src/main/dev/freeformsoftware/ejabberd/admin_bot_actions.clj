(ns dev.freeformsoftware.ejabberd.admin-bot-actions
  "Actions that the admin bot can perform in response to user commands.
   
   All commands are case-insensitive and must start with 'bot'.
   Each action function receives conf (from admin-bot) with :send-message added."
  (:require
   [better-cond.core :as b]
   [camel-snake-kebab.core :as csk]
   [dev.freeformsoftware.db.user-db :as file-db]
   [dev.freeformsoftware.ejabberd.room-membership :as room-membership]
   [dev.freeformsoftware.link-provider :as link-provider]
   [taoensso.telemere :as tel]))

;; =============================================================================
;; Action Implementations
;; =============================================================================

(defn- user-has-group?
  "Checks if a user has a specific group membership.
   
   Parameters:
   - user-db: User database component
   - user-id: The user's ID (local part of JID)
   - group: Group keyword to check (e.g., :group/owner)
   
   Returns true if user is a member of the group, false otherwise."
  [user-db user-id group]
  (let [db      (file-db/read-user-db user-db)
        members (:members db)
        user    (first (filter #(= (:user-id %) user-id) members))]
    (if user
      (contains? (:groups user) group)
      false)))

(defn send-jitsi-invitations!
  "Sends Jitsi meeting invitation links to a list of members via XMPP DM.
   
   Parameters:
   - send-message-fn: Function to send messages (should accept jid-params and message-str)
   - link-provider: Link provider component for creating Jitsi links
   - meeting-name: Name of the meeting
   - members: Collection of maps with :user-id, :name, :jid (or xmpp-domain to construct jid)
   - is-moderator-fn: Function that takes a member and returns true if they should be moderator
   - xmpp-domain: XMPP domain for constructing JIDs (optional if members have :jid)
   
   Options:
   - :duration-hours - JWT validity for meeting links (default 3)
   - :message-prefix - Custom prefix for invitation message (default 'Meeting invitation')
   
   Returns: Count of successfully sent invitations"
  [send-message-fn link-provider meeting-name members is-moderator-fn xmpp-domain
   &
   {:keys [duration-hours message-prefix]
    :or   {duration-hours 3
           message-prefix "Meeting invitation"}}]
  (let [sent-count (atom 0)]
    (doseq [member members]
      (try
        (let [is-moderator? (is-moderator-fn member)
              user-jid      (or (:jid member)
                                (str (:user-id member) "@" xmpp-domain))
              jitsi-link    (link-provider/create-jitsi-meet-link
                             link-provider
                             (:name member)
                             user-jid
                             meeting-name
                             :moderator?     is-moderator?
                             :duration-hours duration-hours)
              message       (str message-prefix
                                 ": "
                                 meeting-name
                                 "\nJoin here: "
                                 jitsi-link
                                 (when is-moderator? "\n(You have moderator privileges)"))]

          (send-message-fn {:local-part (:user-id member) :service :dm} message)
          (swap! sent-count inc)
          (tel/log! :info
                    ["Sent meeting invitation"
                     {:user      (:user-id member)
                      :meeting   meeting-name
                      :moderator is-moderator?}]))
        (catch Exception e
          (tel/log! :warn
                    ["Failed to send meeting invitation"
                     {:user    (:user-id member)
                      :meeting meeting-name
                      :error   (ex-message e)}]))))
    @sent-count))

(defn- action-status
  "Returns system status information."
  [{:keys [send-message!]} reply-to]
  (let [message (str "Admin bot is running.\nSystem time: " (java.time.Instant/now))]
    (send-message! reply-to message)))

(defn- action-create-meet-link
  "Creates a link to the admin interface for distributing meet links."
  [{:keys [send-message! link-provider]} reply-to]
  (let [link-url (link-provider/create-link-generation-page-url link-provider)]
    (send-message! reply-to (str "Create meeting links here: " link-url))))

(defn- action-login-user-admin
  "Creates a link to the admin interface for user and room management.
   Only available to users in the :group/owner group."
  [{:keys [send-message! link-provider user-db]} reply-to]
  (let [user-id (:local-part reply-to)]
    (if (user-has-group? user-db user-id :group/owner)
      (let [admin-url (link-provider/create-admin-signin-url link-provider user-id)]
        (send-message! reply-to (str "Admin portal: " admin-url)))
      (send-message! reply-to "Unauthorized: This command is only available to owners."))))

(defn- action-login-ej-admin
  "Shows admin bot credentials and ejabberd admin HTTP page URL.
   Only available to users in the :group/owner group."
  [{:keys [send-message! credentials admin-http-portal-url xmpp-domain user-db]} reply-to]
  (let [user-id (:local-part reply-to)]
    (if (user-has-group? user-db user-id :group/owner)
      (let [message (str "Ejabberd Admin Credentials:\n"
                         "Username: "
                         (:username credentials)
                         "@"
                         xmpp-domain
                         "\n"
                         "Password: "
                         \"
                         (:password credentials)
                         \"
                         "\n\n"
                         "Admin Console: "
                         admin-http-portal-url)]
        (send-message! reply-to message))
      (send-message! reply-to "Unauthorized: This command is only available to owners."))))

(defn- action-create-meet
  "Creates Jitsi meeting links and sends them to all room members via DM.
   Admin users receive moderator privileges in the meeting."
  [{:keys [user-db link-provider xmpp-domain send-message!]} room-jid meet-room-name]
  (try
    (b/cond
      :let [room-id     (:local-part room-jid)
            db          (file-db/read-user-db user-db)
            rooms       (:rooms db)
            all-members (:members db)
            room        (first (filter #(= (:room-id %) room-id) rooms))]

      (not room)
      (tel/log! :warn ["Room not found in user-db" {:room-id room-id}])

      :let [members-with-affiliation (room-membership/get-room-members-with-affiliations
                                      room
                                      all-members
                                      xmpp-domain)
            final-meet-name          (if meet-room-name
                                       (csk/->PascalCase meet-room-name)
                                       (str "Meet-" (subs (str (java.util.UUID/randomUUID)) 0 8)))
            ;; Filter out admin bot
            recipients               (remove #(= (:user-id %) "admin") members-with-affiliation)]

      :do (tel/log! :info
                    ["Creating Jitsi meet for room"
                     {:room-id      room-id
                      :room-name    (:name room)
                      :meet-name    final-meet-name
                      :member-count (count recipients)}])

      ;; Send invitations using shared function
      :let [sent-count (send-jitsi-invitations!
                        send-message!
                        link-provider
                        final-meet-name
                        recipients
                        #(= (:affiliation %) "admin")
                        xmpp-domain
                        :message-prefix
                        (str "Meeting created for room '" (:name room) "'"))]

      :do (tel/log! :info ["Meeting invitations completed" {:sent sent-count :total (count recipients)}]))

    (catch Exception e
      (tel/log! :error ["Failed to create meet" {:room-id (:local-part room-jid) :error (ex-message e)}]))))

(defn- send-help
  "Sends help documentation based on context (DM or MUC).
   
   For DM context, shows different commands based on user's group membership."
  [{:keys [send-message! user-db]} reply-to service]
  (let
    [user-id (:local-part reply-to)
     is-owner? (and (= service :dm) (user-has-group? user-db user-id :group/owner))
     help-text
     (case service
       :dm
       (if is-owner?
         "Available commands (all start with 'bot'):\n- bot status: Check if bot is alive\n- bot create meet: Get link to create/distribute meeting links\n- bot login user admin: Get link to user/room management\n- bot login ej admin: Show ejabberd admin credentials and URL"
         "Available commands (all start with 'bot'):\n- bot status: Check if bot is alive\n- bot create meet: Get link to create/distribute meeting links")
       :muc
       "Available commands (all start with 'bot'):\n- bot create meet [room-name]: Create Jitsi meeting for this room")]
    (send-message! reply-to help-text)))

;; =============================================================================
;; Command Dispatchers
;; =============================================================================

(defn handle-dm-command
  "Handles incoming direct message commands.
   
   All commands must start with 'bot' (case insensitive).
   Falls through to help if command is not recognized."
  [conf reply-to message-str]
  (b/cond
    :when-let [cmd (second (re-matches #"(?i)bot\s*(.*)" message-str))]

    (nil? cmd)
    (send-help conf reply-to :dm)

    :let [[_ status] (re-matches #"(?i)status.*" cmd)]
    (some? status)
    (action-status conf reply-to)

    :let [[_ create-meet] (re-matches #"(?i)create\s+meet.*" cmd)]
    (some? create-meet)
    (action-create-meet-link conf reply-to)

    :let [[_ login-user] (re-matches #"(?i)login\s+user\s+admin.*" cmd)]
    (some? login-user)
    (action-login-user-admin conf reply-to)

    :let [[_ login-ej] (re-matches #"(?i)login\s+ej\s+admin.*" cmd)]
    (some? login-ej)
    (action-login-ej-admin conf reply-to)

    ;; Fallthrough - send help
    (send-help conf reply-to :dm)))

(defn handle-muc-command
  "Handles incoming MUC (groupchat) commands.
   
   All commands must start with 'bot' (case insensitive).
   Falls through to help if command is not recognized."
  [conf room-jid message-str]
  (b/cond
    :when-let [cmd (second (re-matches #"(?i)bot\s*(.*)" message-str))]

    (nil? cmd)
    (send-help conf room-jid :muc)

    :let [[_ meet-room-name] (re-matches #"(?i)create\s+meet(?:\s+(.+))?" cmd)]
    (some? meet-room-name)
    (do
      (tel/log! :info
                ["Create meet command received"
                 {:room      (:local-part room-jid)
                  :meet-name meet-room-name}])
      (action-create-meet conf room-jid meet-room-name))

    ;; Fallthrough - send help
    (send-help conf room-jid :muc)))
