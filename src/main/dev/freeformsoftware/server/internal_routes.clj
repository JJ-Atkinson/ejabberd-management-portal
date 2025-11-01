(ns dev.freeformsoftware.server.internal-routes 
  "Internal routes are how the EJ server mods can communicate directly with this admin api.
   Most routes should not be internal, and should actually appear on the main routes."
  (:require
   [dev.freeformsoftware.ejabberd.admin-bot :as admin-bot]
   [ring.util.response :as response])
  (:import
    [org.jxmpp.jid Jid]
    [org.jxmpp.jid.impl JidCreate]))

(set! *warn-on-reflection* true)

(defn parse-jid-local-part
  "Extracts the local-part from a JID string using SMACK's JID parser.
   
   Examples:
   - \"jj@example.org/resource\" => \"jj\"
   - \"room@conference.example.org\" => \"room\"
   - \"user@domain.org/gajim.ABC123\" => \"user\"
   
   Returns nil if the JID is invalid or doesn't contain a local part."
  [jid-str]
  (when jid-str
    (try
      (let [^Jid jid (JidCreate/from ^CharSequence jid-str)]
        (str (.getLocalpartOrNull jid)))
      (catch Exception _e
        nil))))

(defn create-routes
  [{:keys [admin-bot]}]
  {"POST /api/actions/send-omemo-lacking"
   (fn [{{:keys [to from type timestamp]} :body-params :as req}]
     (let [user-id (parse-jid-local-part from)
           to      (parse-jid-local-part to)]
       (admin-bot/send-message!
        admin-bot
        {:local-part user-id :service :dm}
        (str "The message you just sent to \"" to
             "\" was not encrypted - please check your client "
             "configuration to ensure OMEMO encryption is enabled to send messages on this server.")))
     (response/status 200))})

^:clj-reload/keep
(defonce !create-routes (atom nil))
(reset! !create-routes create-routes)
