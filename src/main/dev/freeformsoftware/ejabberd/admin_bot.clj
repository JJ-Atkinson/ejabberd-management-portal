(ns dev.freeformsoftware.ejabberd.admin-bot
  "XMPP admin bot component.
   
   This bot maintains a persistent connection to the XMPP server and can:
   - Respond to user commands
   - Participate in all managed rooms
   - Appear in all managed users' rosters
   
   The bot is automatically managed on every boot:
   - Checks if admin-bot user exists in ejabberd
   - Creates user if it doesn't exist
   - Uses existing credentials from file-db if available
   - Resets password if connection fails"
  (:require
   [again.core :as again]
   [babashka.fs :as fs]
   [dev.freeformsoftware.db.file-interaction :as file-db]
   [dev.freeformsoftware.db.util :as db-util]
   [dev.freeformsoftware.ejabberd.ejabberd-api :as api]
   [dev.freeformsoftware.ejabberd.admin-bot-actions :as actions]
   [integrant.core :as ig]
   [taoensso.telemere :as tel])
  (:import
   [org.jivesoftware.smack AbstractXMPPConnection ConnectionListener ReconnectionManager ReconnectionManager$ReconnectionPolicy]
   [org.jivesoftware.smack.packet Stanza Message]
   [org.jivesoftware.smack.tcp XMPPTCPConnection XMPPTCPConnectionConfiguration XMPPTCPConnectionConfiguration$Builder]
   [org.jivesoftware.smack.chat2 ChatManager Chat IncomingChatMessageListener]
   [org.jivesoftware.smack MessageListener]
   [org.jivesoftware.smackx.muc MultiUserChat MultiUserChatManager]
   [org.jivesoftware.smackx.omemo OmemoManager OmemoConfiguration OmemoMessage$Received]
   [org.jivesoftware.smackx.omemo.signal SignalOmemoService SignalFileBasedOmemoStore SignalCachingOmemoStore]
   [org.jivesoftware.smackx.omemo.listener OmemoMessageListener]
   [org.jivesoftware.smackx.omemo.trust OmemoFingerprint TrustState OmemoTrustCallback]
   [org.jivesoftware.smackx.omemo.internal OmemoDevice]
   [org.jivesoftware.smackx.carbons.packet CarbonExtension$Direction]
   [org.jxmpp.jid EntityBareJid]
   [org.jxmpp.jid.parts Domainpart Localpart Resourcepart]
   [org.jxmpp.jid.impl JidCreate]
   [java.io File]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Admin Bot Member Definition
;; =============================================================================

(def member-admin-bot
  "Virtual member definition for the admin bot.
   This is ghost-included in sync-state to ensure the bot appears in all rooms and rosters."
  {:name "Admin Bot"
   :user-id "admin"})

;; =============================================================================
;; XMPP Connection Helpers
;; =============================================================================

(defn- create-xmpp-connection
  "Creates and connects an XMPP connection with the given credentials.
   
   Returns connection on success, throws exception on failure.
   Distinguishes between authentication errors and other connection errors."
  [username password host]
  (let [^XMPPTCPConnectionConfiguration$Builder builder (XMPPTCPConnectionConfiguration/builder)
        config (-> builder
                   (.setUsernameAndPassword ^String username ^String password)
                   (.setXmppDomain ^String host)
                   (.setHost ^String (str "xmpp." host))
                   (.setPort (int 5222))
                   (.build))
        ^AbstractXMPPConnection connection (XMPPTCPConnection. config)]
    (try
      (.connect connection)
      (.login connection)
      (tel/log! :info
                ["XMPP connection established"
                 {:user (str (.getUser connection))
                  :connected (.isConnected connection)
                  :authenticated (.isAuthenticated connection)}])
      connection
      (catch org.jivesoftware.smack.sasl.SASLErrorException e
        ;; Authentication failure (wrong password, policy violation, etc.)
        (let [sasl-failure (.getSASLFailure e)
              condition (str sasl-failure)]
          (tel/log! :error ["XMPP authentication failed"
                            {:user username
                             :host host
                             :error-condition condition
                             :error-text (.getMessage e)}])
          (throw (ex-info "XMPP authentication failed"
                          {:type :authentication-failure
                           :username username
                           :condition condition}
                          e))))
      (catch org.jivesoftware.smack.XMPPException$StreamErrorException e
        ;; Stream error (policy-violation for IP bans, etc.)
        (let [stream-error (.getStreamError e)
              condition (str (.getCondition stream-error))
              descriptive-text (.getDescriptiveText stream-error)]
          (tel/log! :error ["XMPP stream error"
                            {:user username
                             :host host
                             :condition condition
                             :text descriptive-text}])
          (throw (ex-info "XMPP stream error"
                          {:type :stream-error
                           :condition condition
                           :text descriptive-text
                           :ip-ban? (= "policy-violation" condition)}
                          e))))
      (catch Exception e
        ;; Other connection errors
        (tel/log! :error ["XMPP connection failed" {:user username :host host :error (ex-message e)}])
        (throw e)))))

(defn- init-omemo-service!
  "Initializes the OMEMO service and configures the file-based store backend.
   
   Note: This acknowledges the GPLv3 license of smack-omemo-signal.
   The store can only be set once globally, before any OmemoManager instances are created."
  [db-folder]
  (try
    (SignalOmemoService/acknowledgeLicense)
    (SignalOmemoService/setup)

    ;; Configure file-based store (wrapped in caching store for performance)
    (let [omemo-store-path (fs/path db-folder "omemo")]
      (fs/create-dirs omemo-store-path)
      (let [file-store (SignalFileBasedOmemoStore. (fs/file omemo-store-path))
            caching-store (SignalCachingOmemoStore. file-store)
            service (SignalOmemoService/getInstance)]
        (.setOmemoStoreBackend service caching-store)
        (tel/log! :info ["OMEMO service initialized with file-based store"
                         {:path (str omemo-store-path)}])))
    (catch Exception e
      (tel/log! :warn ["OMEMO service initialization issue" {:error (ex-message e)}]))))

;; Forward declaration for reconnection handler
(declare join-all-rooms)

(defn- setup-connection-listener
  "Sets up a listener to monitor XMPP connection state changes.
   
   Logs when the connection is:
   - Connected
   - Authenticated
   - Closed (normal or error)
   
   Also enables automatic reconnection with exponential backoff.
   
   The conf-atom should be updated with the final conf after initialization,
   so that reconnection handlers have access to the complete config.
   
   Returns the listener instance."
  [^AbstractXMPPConnection connection !conf]
  (let [listener (reify ConnectionListener
                   (connected [_this _connection]
                     (tel/log! :info ["XMPP connection established"]))

                   (authenticated [_this _connection resumed]
                     (tel/log! :info ["XMPP authenticated" {:resumed resumed}])
                     ;; After successful reconnection, rejoin all rooms
                     (when resumed
                       (tel/log! :info ["Reconnection detected - rejoining rooms"])
                       (try
                         (when-let [conf @!conf]
                           (join-all-rooms conf))
                         (catch Exception e
                           (tel/log! :error ["Failed to rejoin rooms after reconnection"
                                             {:error (ex-message e)}])))))

                   (connectionClosed [_this]
                     (tel/log! :warn ["XMPP connection closed normally"]))

                   (connectionClosedOnError [_this e]
                     (tel/log! :error ["XMPP connection closed due to error"
                                       {:error (ex-message e)
                                        :type (type e)}])))]
    (.addConnectionListener connection listener)

    ;; Enable automatic reconnection
    (ReconnectionManager/setEnabledPerDefault true)
    (let [reconnection-manager (ReconnectionManager/getInstanceFor connection)]
      (.setReconnectionPolicy reconnection-manager ReconnectionManager$ReconnectionPolicy/RANDOM_INCREASING_DELAY)
      (.enableAutomaticReconnection reconnection-manager)
      (tel/log! :info ["Automatic reconnection enabled with random increasing delay"]))

    (tel/log! :info ["Connection listener registered"])
    listener))

(defn- create-omemo-manager
  "Creates and initializes an OmemoManager for the given connection.
   
   The file-based store is already configured globally via init-omemo-service!
   Sets up TOFU (Trust On First Use) trust strategy before initialization.
   This will generate OMEMO keys if they don't exist, making the device visible to other clients."
  [^AbstractXMPPConnection connection]
  (try
    (let [omemo-manager (OmemoManager/getInstanceFor connection)

          ;; Setup TOFU trust callback BEFORE initializing
          trust-callback (reify OmemoTrustCallback
                           (^TrustState getTrust [this ^OmemoDevice device ^OmemoFingerprint fingerprint]
                             ;; Always trust on first use
                             TrustState/trusted)

                           (^void setTrust [this ^OmemoDevice device ^OmemoFingerprint fingerprint ^TrustState state]
                             ;; Log trust decisions
                             (tel/log! :debug ["OMEMO trust decision"
                                               {:device (str device)
                                                :fingerprint (str fingerprint)
                                                :state (str state)}])))]

      (.setTrustCallback omemo-manager trust-callback)
      (tel/log! :info ["Initializing OMEMO manager with TOFU trust strategy..."])
      (.initialize omemo-manager) ;; Generate keys and register device
      (tel/log! :info ["OMEMO manager initialized"
                       {:device-id (.getDeviceId omemo-manager)}])
      omemo-manager)
    (catch Exception e
      (tel/log! :error ["Failed to create/initialize OmemoManager" {:error (ex-message e)}])
      nil)))

(defn- get-chat-manager
  "Gets ChatManager for a connection."
  ^ChatManager [^AbstractXMPPConnection conn]
  (ChatManager/getInstanceFor conn))

(defn- get-muc-manager
  "Gets MultiUserChatManager for a connection."
  ^MultiUserChatManager [^AbstractXMPPConnection conn]
  (MultiUserChatManager/getInstanceFor conn))

(defn- send-dm!
  "Sends a direct message via ChatManager.
   
   Attempts OMEMO encryption if omemo-manager is provided, falls back to plaintext."
  ([^AbstractXMPPConnection conn ^EntityBareJid jid ^String message]
   (send-dm! conn jid message nil))
  ([^AbstractXMPPConnection conn ^EntityBareJid jid ^String message ^OmemoManager omemo-manager]
   (if omemo-manager
     (try
       (let [encrypted-message (.encrypt omemo-manager jid message)
             ;; In SMACK 4.4.x, use buildMessage with StanzaFactory
             ;; getStanzaFactory().buildMessageStanza() returns a MessageBuilder
             message-builder (-> conn .getStanzaFactory .buildMessageStanza)
             stanza (.buildMessage encrypted-message message-builder jid)]
         (.sendStanza conn stanza)
         (tel/log! :debug ["Sent OMEMO encrypted DM" {:to (str jid)}]))
       (catch Exception e
         (tel/log! :warn ["Failed to send OMEMO encrypted message, falling back to plaintext" {:error (ex-message e)}])
         (let [chat-manager (get-chat-manager conn)
               ^Chat chat (.chatWith chat-manager jid)]
           (.send chat message))))
     ;; No OMEMO manager, send plaintext
     (let [chat-manager (get-chat-manager conn)
           ^Chat chat (.chatWith chat-manager jid)]
       (.send chat message)))))

(defn- send-muc-message!
  "Sends a message to a MUC room.
   
   Attempts OMEMO encryption if omemo-manager is provided, falls back to plaintext.
   
   NOTE: MUC OMEMO encryption is currently disabled due to connection stability issues."
  ([^AbstractXMPPConnection conn ^EntityBareJid jid ^String message]
   (send-muc-message! conn jid message nil))
  ([^AbstractXMPPConnection conn ^EntityBareJid jid ^String message ^OmemoManager omemo-manager]
   ;; Temporarily disabled - MUC OMEMO encryption causes connection timeouts
   ;; TODO: Investigate why OMEMO device list queries to MUC rooms timeout
   #_(let [muc-manager (get-muc-manager conn)
           ^MultiUserChat muc (.getMultiUserChat muc-manager jid)]
       (.sendMessage muc message))))

(defn breakup-jid
  "Breaks up a JID entity into its component parts.
   
   Returns a map with:
   - :bare-jid - The full bare JID string
   - :local-part - The local part (username or room name)
   - :domain - The domain part
   - :service - Either :dm (for users) or :muc (for rooms)"
  [{:keys [xmpp-domain muc-service] :as conf} ^EntityBareJid jid-ent]
  (let [jid-domain (str (.asDomainBareJid jid-ent))
        service (cond
                  (= jid-domain xmpp-domain)
                  :dm
                  (= jid-domain muc-service)
                  :muc
                  :else
                  (throw (ex-info "The bot is trying to communicate with a foreign entity!"
                                  {:ent jid-ent})))]
    {:bare-jid (str (.asBareJid jid-ent))
     :local-part (str (.getLocalpartOrNull jid-ent))
     :domain jid-domain
     :service service}))

(defn construct-jid
  "Constructs a JID for either a user (DM) or a room (MUC).
   
   Parameters:
   - conf: Configuration map containing :xmpp-domain and :muc-service
   - jid-params: Map with :local-part (string) and :service (keyword :dm or :muc)
   
   Returns an EntityBareJid suitable for XMPP operations."
  [{:keys [xmpp-domain muc-service] :as conf} {:keys [local-part service]}]
  {:pre [(string? local-part)
         (#{:dm :muc} service)]}
  (let [domain (case service
                 :dm xmpp-domain
                 :muc muc-service)]
    (JidCreate/entityBareFrom
     (Localpart/from local-part)
     (Domainpart/from domain))))

(defn send-message!
  "Sends a message to either a user (DM) or a room (MUC).
   
   Parameters:
   - conf: Configuration map containing connection and domain info
   - jid-params: Map with :local-part (string) and :service (keyword :dm or :muc)
   - message-str: The message content to send
   
   For :dm service, uses ChatManager to send direct messages.
   For :muc service, sends messages to MUC rooms (requires room to be joined).
   
   Attempts OMEMO encryption if omemo-manager is available in conf."
  [{:keys [connection omemo-manager] :as conf} jid-params message-str]
  (when-not (= (:local-part jid-params) (:user-id member-admin-bot))
    (let [jid (construct-jid conf jid-params)]
      (case (:service jid-params)
        :dm (send-dm! connection jid message-str omemo-manager)
        :muc (send-muc-message! connection jid message-str omemo-manager)))))

(defn- handle-dm!
  "Handles incoming direct messages by delegating to admin-bot-actions."
  [conf jid message-str]
  (let [conf-with-send (assoc conf :send-message! (partial send-message! conf))]
    (actions/handle-dm-command conf-with-send jid message-str)))

(defn- handle-muc-message!
  "Handles incoming MUC (groupchat) messages by delegating to admin-bot-actions."
  [conf room-jid sender-jid message-str]
  ;; Ignore messages from the bot itself
  (when-not (= (:local-part sender-jid) (:user-id member-admin-bot))
    (let [conf-with-send (assoc conf :send-message! (partial send-message! conf))]
      (actions/handle-muc-command conf-with-send room-jid message-str))))

(defn- setup-omemo-listener
  "Sets up a listener for incoming OMEMO encrypted messages."
  [^OmemoManager omemo-manager conf]
  (when omemo-manager
    (try
      (let [listener (reify OmemoMessageListener
                       (^void onOmemoMessageReceived [this ^Stanza stanza ^OmemoMessage$Received omemo-message]
                         (tel/log! :info ["Received OMEMO encrypted message"])
                         (try
                           (let [from (.getFrom stanza)
                                 decrypted-body (.getBody omemo-message)]
                             (handle-dm! conf (breakup-jid conf from) decrypted-body))
                           (catch Exception e
                             (tel/log! :error ["Error handling OMEMO message" {:error (ex-message e)}]))))

                       (^void onOmemoCarbonCopyReceived [this ^CarbonExtension$Direction direction
                                                         ^Message carbon-copy ^Message wrapping-message
                                                         ^OmemoMessage$Received omemo-message]
                         (tel/log! :debug ["Received OMEMO carbon copy"
                                           {:direction (str direction)
                                            :body (.getBody omemo-message)}])))]
        (.addOmemoMessageListener omemo-manager listener)
        (tel/log! :info ["OMEMO message listener registered"])
        listener)
      (catch Exception e
        (tel/log! :error ["Failed to setup OMEMO listener" {:error (ex-message e)}])
        nil))))

(defn- setup-message-listener
  "Sets up a message listener on the XMPP connection.
   
   Returns the listener object so it can be stored in the conf map."
  [{:keys [connection] :as conf}]
  (let [chat-manager (ChatManager/getInstanceFor connection)
        listener (reify
                   IncomingChatMessageListener
                   (newIncomingMessage [_ from message _chat]
                     (handle-dm! conf (breakup-jid conf from) (.getBody message))))]
    (.addIncomingListener chat-manager listener)
    (tel/log! :info ["Message listener registered"])
    listener))

(defn join-muc-room
  "Joins a MUC room and sets up message listener.
   
   Parameters:
   - conf: Admin bot configuration map with :connection, :muc-service, and :muc-room-listeners (atom)
   - room-id: The room identifier (local part, without @muc-service)
   
   Returns the MultiUserChat object on success, nil on failure.
   
   Side effects: Adds listener to :muc-room-listeners atom with key \"room-id@muc-service\""
  [{:keys [connection muc-service xmpp-domain !muc-room-listeners] :as conf} room-id]
  (try
    (let [^AbstractXMPPConnection conn connection
          muc-manager (MultiUserChatManager/getInstanceFor conn)
          room-jid-str (str room-id "@" muc-service)
          room-jid (JidCreate/entityBareFrom room-jid-str)
          ^MultiUserChat muc (.getMultiUserChat muc-manager room-jid)
          nickname (Resourcepart/from (:user-id member-admin-bot))]

      ;; Join the room
      (.join muc nickname)
      (tel/log! :info ["Joined MUC room" {:room-id room-id :muc-service muc-service}])

      ;; Add message listener
      (let [listener (reify
                       MessageListener
                       (processMessage [_ message]
                         (when-let [body (.getBody message)]
                           (let [from (.getFrom message)
                                 sender-nickname (str (.getResourceOrNull from))]
                             (handle-muc-message! conf
                                                  (breakup-jid conf from)
                                                  {:local-part sender-nickname
                                                   :domain xmpp-domain
                                                   :service :muc}
                                                  body)))))]
        (.addMessageListener muc listener)

        ;; Store listener in atom for tracking
        (when !muc-room-listeners
          (swap! !muc-room-listeners assoc room-jid-str listener)))

      muc)
    (catch Exception e
      (tel/log! :warn ["Failed to join MUC room" {:room-id room-id :error (ex-message e)}])
      nil)))

(defn- join-all-rooms
  "Joins all rooms defined in user-db that don't already have listeners.
   
   Checks :muc-room-listeners atom to see which rooms are already joined,
   and only joins rooms that don't have listeners yet."
  [{:keys [user-db muc-service !muc-room-listeners] :as conf}]
  (try
    (let [db (file-db/read-user-db user-db)
          rooms (:rooms db)
          existing-listeners (if !muc-room-listeners @!muc-room-listeners {})
          joined-rooms (atom [])]

      (doseq [room rooms
              :let [room-id (:room-id room)
                    room-jid-str (when room-id (str room-id "@" muc-service))]
              :when (and room-id (not (contains? existing-listeners room-jid-str)))]
        (when-let [muc (join-muc-room conf room-id)]
          (swap! joined-rooms conj {:room-id room-id :muc muc})))

      (tel/log! :info ["Joined MUC rooms" {:count (count @joined-rooms) :skipped (count existing-listeners)}])
      @joined-rooms)
    (catch Exception e
      (tel/log! :warn ["Failed to join all rooms" {:error (ex-message e)}])
      [])))

(defn join-room-if-new!
  "Joins a single room if it's not already joined (doesn't have a listener).
   
   This is meant to be called after creating a new room to ensure the bot joins it immediately.
   
   Parameters:
   - admin-bot: Admin bot configuration map with :muc-room-listeners atom
   - room-id: The room identifier to join
   
   Returns the MultiUserChat object on success, nil if already joined or on failure."
  [{:keys [muc-service !muc-room-listeners] :as admin-bot} room-id]
  (when (and admin-bot !muc-room-listeners)
    (let [room-jid-str (str room-id "@" muc-service)
          existing-listeners @!muc-room-listeners]
      (if (contains? existing-listeners room-jid-str)
        (do
          (tel/log! :debug ["Room already joined, skipping" {:room-id room-id}])
          nil)
        (do
          (tel/log! :info ["Joining newly created room" {:room-id room-id}])
          (join-muc-room admin-bot room-id))))))

(defn- leave-muc-room
  "Leaves a single MUC room with error handling."
  [room-id ^MultiUserChat muc]
  (try
    (.leave muc)
    (tel/log! :info ["Left MUC room" {:room-id room-id}])
    (catch Exception e
      (tel/log! :warn ["Failed to leave MUC room" {:room-id room-id :error (ex-message e)}]))))

(defn- disconnect-xmpp
  "Disconnects an XMPP connection immediately.
   
   Uses instantShutdown() instead of disconnect() to avoid waiting for 
   responses during shutdown. The server will automatically clean up 
   room memberships when the connection drops."
  [{:keys [connection]}]
  (when connection
    (let [^AbstractXMPPConnection conn connection]
      (when (.isConnected conn)
        (try
          ;; Use instantShutdown instead of disconnect to avoid waiting
          ;; for server responses that may never come during shutdown
          (.instantShutdown conn)
          (tel/log! :info ["XMPP connection closed"])
          (catch Exception e
            (tel/log! :warn ["Failed to disconnect XMPP connection" {:error (ex-message e)}])))))))

;; =============================================================================
;; Credential Management
;; =============================================================================

(defn- save-credentials
  "Saves credentials to file-db."
  [user-db-component username password]
  (let [creds {:username username :password password}
        db (file-db/read-user-db user-db-component)
        updated-db (assoc-in db [:do-not-edit-state :admin-credentials] creds)]
    (file-db/write-user-db user-db-component updated-db)
    creds))

(defn- reset-password
  "Resets admin-bot password and saves to file-db."
  [user-db-component ejabberd-api username]
  (let [new-password (db-util/generate-random-password)]
    (api/change-password ejabberd-api username new-password)
    (tel/log! :info ["Admin bot password reset"])
    (save-credentials user-db-component username new-password)))

(defn- ensure-user-exists
  "Ensures admin-bot user exists in ejabberd. Returns credentials if created, nil otherwise."
  [user-db-component ejabberd-api username]
  (if-let [registered-users (try
                              (api/registered-users ejabberd-api)
                              (catch Exception e
                                (tel/log! :warn ["Failed to query registered users" {:error (ex-message e)}])
                                nil))]
    (when-not (some #{username} registered-users)
      (tel/log! :info ["Admin bot user does not exist, creating it"])
      (let [password (db-util/generate-random-password)]
        (api/register ejabberd-api username password)
        (tel/log! :info ["Admin bot user created in ejabberd" {:username username}])
        (save-credentials user-db-component username password)))
    ;; If we couldn't get registered users, don't try to create
    nil))

(defn- get-or-create-credentials
  "Gets existing credentials or creates new ones if admin-bot doesn't exist in file-db."
  [user-db-component ejabberd-api]
  (let [username (:user-id member-admin-bot)
        db (file-db/read-user-db user-db-component)
        existing-creds (get-in db [:do-not-edit-state :admin-credentials])]

    ;; Ensure user exists in ejabberd (creates with new creds if needed)
    (when-let [new-creds (ensure-user-exists user-db-component ejabberd-api username)]
      new-creds)

    ;; Return existing creds or reset password if none exist
    (or existing-creds
        (do
          (tel/log! :info ["No credentials in file-db, resetting password for admin"])
          (reset-password user-db-component ejabberd-api username)))))

;; =============================================================================
;; Integrant Component
;; =============================================================================

(defmethod ig/init-key ::admin-bot
  [_ {:keys [user-db ejabberd-api link-provider xmpp-domain admin-http-portal-url]}]
  (tel/log! :info ["Initializing admin bot component"])

  ;; Initialize OMEMO service with file-based store
  (init-omemo-service! (:db-folder user-db))

  (let [username (:user-id member-admin-bot)
        muc-service (:muc-service ejabberd-api)
        credentials (atom (get-or-create-credentials user-db ejabberd-api))

        ;; Connection attempt that uses current credentials from atom
        attempt-connection (fn []
                             (create-xmpp-connection
                              (:username @credentials)
                              (:password @credentials)
                              xmpp-domain))

        ;; Callback to reset password on authentication failure (but not IP bans)
        retry-callback (fn [{:keys [attempt error]}]
                         (when (and error (= attempt 1))
                           (let [ex-data (ex-data error)]
                             (cond
                               ;; IP ban - don't retry, just warn
                               (:ip-ban? ex-data)
                               (tel/log! :error ["IP banned from XMPP server - cannot retry"
                                                 {:condition (:condition ex-data)
                                                  :text (:text ex-data)
                                                  :advice "Wait for ban to expire or manually unban IP on ejabberd server"}])

                               ;; Authentication failure - reset password and retry
                               (= :authentication-failure (:type ex-data))
                               (do
                                 (tel/log! :warn ["Authentication failed, resetting password"])
                                 (reset! credentials (reset-password user-db ejabberd-api username)))

                               ;; Other errors - just log
                               :else
                               (tel/log! :error ["Connection failed with unknown error" {:error (ex-message error)}])))))

        ;; Attempt connection with retry (only if not IP banned)
        connection (try
                     (again/with-retries
                       {::again/callback retry-callback
                        ::again/strategy [0] ;; One retry after callback
                        ::again/callback-result-fn (fn [{:keys [error]}]
                                                     ;; Don't retry if IP banned
                                                     (if (and error (:ip-ban? (ex-data error)))
                                                       ::again/fail
                                                       ::again/retry))}
                       (attempt-connection))
                     (catch Exception e
                       (let [ex-data (ex-data e)]
                         (if (:ip-ban? ex-data)
                           (tel/log! :error ["Admin bot cannot start - IP is banned from XMPP server"
                                             {:unban-time (:text ex-data)}])
                           (tel/log! :error ["Failed to connect admin bot" {:error (ex-message e)}])))
                       nil))

        ;; Initialize listener tracking atoms
        !muc-room-listeners (atom {})

        ;; Create atom to hold final conf (for reconnection handlers)
        !final-conf (atom nil)

        ;; Setup base config
        conf {:admin-http-portal-url admin-http-portal-url
              :connection connection
              :credentials @credentials
              :user-db user-db
              :ejabberd-api ejabberd-api
              :link-provider link-provider
              :xmpp-domain xmpp-domain
              :muc-service muc-service
              :muc-room-listeners !muc-room-listeners
              :error (when-not connection "Failed to establish XMPP connection")}]

    (if connection
      (do
        (tel/log! :info ["Admin bot connected successfully"])

        ;; Setup connection listener to monitor disconnections
        (let [connection-listener (setup-connection-listener connection !final-conf)

              ;; Create OMEMO manager (store already configured globally)
              omemo-manager (create-omemo-manager connection)

              ;; CRITICAL FIX: Create conf-with-omemo BEFORE setting up listeners
              ;; so that all listeners (including OMEMO listener) capture the complete
              ;; conf with OMEMO support
              conf-with-omemo (assoc conf
                                     :connection-listener connection-listener
                                     :omemo-manager omemo-manager)

              ;; NOW setup OMEMO listener with complete config
              omemo-listener (setup-omemo-listener omemo-manager conf-with-omemo)

              ;; Setup DM listener and store it (now with OMEMO in conf)
              dm-listener (setup-message-listener conf-with-omemo)

              ;; Join all MUC rooms (now with OMEMO in conf)
              joined-rooms (join-all-rooms conf-with-omemo)

              final-conf (assoc conf-with-omemo
                                :omemo-listener omemo-listener
                                :dm-message-listener dm-listener
                                :joined-rooms joined-rooms)]
          ;; Update the atom so reconnection handlers have access to complete conf
          (reset! !final-conf final-conf)
          (def testing-conf* final-conf)
          final-conf))
      (do
        (tel/log! :warn ["Admin bot component initialized WITHOUT connection"
                         {:reason "Connection failed - check logs above"}])
        (def testing-conf* conf)
        conf))))

(defmethod ig/suspend-key! ::admin-bot
  [_ conf]
  ;; Keep XMPP connection alive during suspend/resume cycles
  ;; This prevents reconnection overhead during development
  (tel/log! :debug ["Suspending admin bot component (keeping connection alive)"])
  conf)

(defmethod ig/resume-key ::admin-bot
  [_ {:keys [user-db ejabberd-api link-provider xmpp-domain admin-http-portal-url] :as opts} old-opts old-conf]
  (let [muc-service (:muc-service ejabberd-api)
        conf (merge old-conf
                    {:user-db user-db
                     :ejabberd-api ejabberd-api
                     :link-provider link-provider
                     :xmpp-domain xmpp-domain
                     :muc-service muc-service
                     :admin-http-portal-url admin-http-portal-url})]
    (def testing-conf* conf)
    conf))

(defmethod ig/halt-key! ::admin-bot
  [_ conf]
  (tel/log! :info ["Shutting down admin bot component"])
  (disconnect-xmpp conf))
