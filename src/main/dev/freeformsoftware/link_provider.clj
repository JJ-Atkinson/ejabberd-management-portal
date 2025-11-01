(ns dev.freeformsoftware.link-provider
  "Integrant component for generating various application URLs.
   
   Provides centralized URL generation for:
   - Jitsi meeting links (with JWT authentication)
   - Admin signin URLs
   - User password reset URLs
   - Admin link generation page"
  (:require
   [dev.freeformsoftware.auth.jwt :as jwt]
   [integrant.core :as ig]
   [taoensso.telemere :as tel]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Link Generation Functions
;; =============================================================================

(defn create-jitsi-meet-link
  "Creates a Jitsi meet link with JWT authentication.
   
   Args:
     component - Component map with :jwt-secret and :jitsi-config
     name - User's display name
     user-jid - User's XMPP address (e.g., 'alice@example.org')
     room - Meeting room name
   
   Options:
     :moderator? - Boolean, whether user is a moderator (default false)
     :duration-hours - JWT validity duration (default 3)
     :avatar - Optional avatar URL
   
   Returns:
     Full Jitsi meet URL with JWT token"
  [component name user-jid room & {:keys [moderator? duration-hours avatar]
                                   :or {moderator? false
                                        duration-hours 3}}]
  (let [jwt-secret (:jwt-secret component)
        jitsi-config (:jitsi-config component)
        opts (cond-> {:moderator? moderator?
                      :duration-hours duration-hours}
               avatar (assoc :avatar-url avatar))]
    ;; Use apply to unpack opts map into keyword arguments
    (apply jwt/create-jitsi-link {:secret jwt-secret} jitsi-config name user-jid room (mapcat identity opts))))

(defn create-admin-signin-url
  "Creates an admin signin URL with JWT authentication.
   
   Args:
     component - Component map with :management-portal-url-base and :jwt-secret
   
   Options:
     :duration-hours - JWT validity duration (default 24)
     :user - Username for admin (default 'admin')
   
   Returns:
     Full URL to management portal with admin JWT token"
  [component & {:keys [duration-hours user]
                :or {duration-hours 24
                     user "admin"}}]
  (let [jwt-secret (:jwt-secret component)
        base-url (:management-portal-url-base component)
        admin-jwt (jwt/create-jwt
                   {:secret jwt-secret
                    :audience base-url}
                   {:role :admin
                    :user user}
                   :duration-hours duration-hours)]
    (str base-url "/?jwt=" admin-jwt)))

(defn create-password-reset-url
  "Creates a user password reset URL with JWT authentication.
   
   Args:
     component - Component map with :management-portal-url-base and :jwt-secret
     user-id - User's XMPP account ID
     name - User's display name
   
   Options:
     :duration-hours - JWT validity duration (default 24)
   
   Returns:
     Full URL to signup page with reset JWT token"
  [component user-id name & {:keys [duration-hours]
                             :or {duration-hours 24}}]
  (let [jwt-secret (:jwt-secret component)
        base-url (:management-portal-url-base component)
        signup-jwt (jwt/create-jwt
                    {:secret jwt-secret
                     :audience base-url}
                    {:role :signup
                     :user-id user-id
                     :name name}
                    :duration-hours duration-hours)]
    (str base-url "/signup?jwt=" signup-jwt)))

(defn create-link-generation-page-url
  "Creates URL to meeting invitation page with JWT authentication.
   
   Args:
     component - Component map with :management-portal-url-base and :jwt-secret
   
   Options:
     :duration-hours - JWT validity duration (default 24)
   
   Returns:
     Full URL to send-meet-invite page with JWT token"
  [component & {:keys [duration-hours]
                :or {duration-hours 24}}]
  (let [jwt-secret (:jwt-secret component)
        base-url (:management-portal-url-base component)
        invite-jwt (jwt/create-jwt
                    {:secret jwt-secret
                     :audience base-url}
                    {:role "meet-invite"}
                    :duration-hours duration-hours)]
    (str base-url "/send-meet-invite?jwt=" invite-jwt)))

;; =============================================================================
;; Integrant Lifecycle
;; =============================================================================

(defmethod ig/init-key ::link-provider
  [_ {:keys [jwt-secret management-portal-url-base jitsi-config] :as config}]
  (tel/log! :info ["Initializing link-provider component"
                   {:management-portal-url-base management-portal-url-base}])

  (when (or (nil? jwt-secret) (empty? jwt-secret))
    (throw (ex-info "jwt-secret is required for link-provider"
                    {:type :missing-config
                     :config config})))

  (when (or (nil? management-portal-url-base) (empty? management-portal-url-base))
    (throw (ex-info "management-portal-url-base is required for link-provider"
                    {:type :missing-config
                     :config config})))

  (when (nil? jitsi-config)
    (throw (ex-info "jitsi-config is required for link-provider"
                    {:type :missing-config
                     :config config})))

  (tel/log! :info ["Link-provider component initialized successfully"])
  ;; Return plain map with configuration
  (let [conf {:jwt-secret jwt-secret
              :management-portal-url-base management-portal-url-base
              :jitsi-config jitsi-config}]
    (def testing-conf* conf)
    conf))

(defmethod ig/halt-key! ::link-provider
  [_ component]
  (tel/log! :debug ["Halting link-provider component"])
  ;; No cleanup needed - stateless component
  nil)

;; =============================================================================
;; REPL Usage Examples
;; =============================================================================

(comment
  ;; Initialize component manually

  ;; Create Jitsi meet link
  (create-jitsi-meet-link testing-conf*
                          "Alice Smith"
                          "alice@example.com"
                          "Team-Meeting"
                          :moderator? false
                          :duration-hours 3)

  ;; Create admin signin URL
  (create-admin-signin-url testing-conf*
                           :user "alice"
                           :duration-hours 24)

  ;; Create password reset URL
  (create-password-reset-url testing-conf*
                             "alice"
                             "Alice Smith"
                             :duration-hours 24)

  ;; Get link generation page URL
  (create-link-generation-page-url testing-conf*)

  ;; Halt component
  (ig/halt-key! ::link-provider testing-conf*))
