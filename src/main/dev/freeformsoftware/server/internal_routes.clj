(ns dev.freeformsoftware.server.internal-routes
  "Internal routes are how the EJ server mods can communicate directly with this admin api.
   Most routes should not be internal, and should actually appear on the main routes."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [dev.freeformsoftware.db.user-db :as file-db]
    [dev.freeformsoftware.ejabberd.admin-bot :as admin-bot]
    [dev.freeformsoftware.server.route-utils :as route-utils]
    [ring.util.response :as response])
  (:import
     [java.nio.charset StandardCharsets]
     [java.security MessageDigest]
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

(defn- json-response
  [status body]
  {:status  status
   :headers {"Content-Type" "application/json"}
   :body    (json/generate-string body)})

(defn- bearer-token
  [request]
  (when-let [^String header (get-in request [:headers "authorization"])]
    (when (str/starts-with? (str/lower-case header) "bearer ")
      (str/trim (subs header 7)))))

(defn- constant-time=
  [a b]
  (and (string? a)
       (string? b)
       (MessageDigest/isEqual (.getBytes ^String a StandardCharsets/UTF_8)
                              (.getBytes ^String b StandardCharsets/UTF_8))))

(defn- wrap-scoria-service-token
  [{:keys [scoria-directory-token]} handler]
  (fn [request]
    (cond
      (str/blank? scoria-directory-token)
      (json-response 503 {:error "scoria-directory-token is not configured"})

      (constant-time= scoria-directory-token (bearer-token request))
      (handler request)

      :else
      (json-response 401 {:error "unauthorized"}))))

(defn- group-id
  [group]
  (subs (str group) 1))

(defn- user->scoria
  [member]
  (cond-> {:id   (:user-id member)
           :name (:name member)}
    (:email member) (assoc :email (:email member))))

(defn- tag->scoria
  [[group label]]
  {:id   (group-id group)
   :name label})

(defn- portal-state
  [user-db]
  (file-db/read-user-db user-db))

(defn- find-member
  [state user-id]
  (first (filter #(= (:user-id %) user-id) (:members state))))

(defn- tags-by-id
  [state]
  (into {}
        (map (fn [[group label]] [(group-id group) {:id (group-id group) :name label}]))
        (:groups state)))

(defn- scoria-directory-routes
  [{:keys [user-db] :as conf}]
  (route-utils/wrap-routes
   (partial wrap-scoria-service-token conf)
   {"GET /api/scoria/users"
    (fn [_request]
      (let [state (portal-state user-db)]
        (json-response 200 {:users (mapv user->scoria (:members state))})))

    "GET /api/scoria/users/*"
    (fn [{[user-id] :path-params}]
      (let [state (portal-state user-db)]
        (if-let [member (find-member state user-id)]
          (json-response 200 {:user (user->scoria member)})
          (json-response 404 {:error "unknown-user" :user-id user-id}))))

    "GET /api/scoria/tags"
    (fn [_request]
      (let [state (portal-state user-db)]
        (json-response 200 {:tags (mapv tag->scoria (:groups state))})))

    "GET /api/scoria/users/*/tags"
    (fn [{[user-id] :path-params}]
      (let [state (portal-state user-db)
            tags  (tags-by-id state)]
        (if-let [member (find-member state user-id)]
          (json-response 200 {:tags (mapv #(get tags (group-id %)) (:groups member))})
          (json-response 404 {:error "unknown-user" :user-id user-id}))))}))

(defn create-routes
  [{:keys [admin-bot] :as conf}]
  (route-utils/merge-routes
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
      (response/status 200))}
   (scoria-directory-routes conf)))

^:clj-reload/keep
(defonce !create-routes (atom nil))
(reset! !create-routes create-routes)
