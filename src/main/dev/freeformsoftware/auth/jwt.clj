(ns dev.freeformsoftware.auth.jwt
  (:require
   [buddy.sign.jwt :as jwt]
   [clojure.string :as str]))

;; =============================================================================
;; Configuration
;; =============================================================================

;; Default internal JWT configuration
(def internal-config
  {:issuer "dev.freeformsoftware.ejabberd-management-portal"
   :subject "dev.freeformsoftware.ejabberd-management-portal.ui-portal"})

;; Jitsi configuration is now defined in config.edn and passed as a parameter.
;; Example structure:
;; {:base-url "https://meet.yourserverhere.org"
;;  :app-id "jitsi"                ; JWT_APP_ID
;;  :issuer "dev.freeformsoftware" ; JWT_ACCEPTED_ISSUERS
;;  :audience "jitsi"              ; JWT_ACCEPTED_AUDIENCES
;;  :subject "meet.jitsi"}         ; Standard Jitsi subject

;; =============================================================================
;; Core JWT Functions
;; =============================================================================

(defn create-jwt
  "Creates an internal JWT token for application use.

  conf: map containing :secret and :audience (typically management-portal-url-base)
  claims: map of JWT claims (will be merged with iss/sub/aud/iat/exp)
  opts: optional map with :duration-hours (default 24)"
  [{:keys [secret audience]} claims & {:keys [duration-hours] :or {duration-hours 24}}]
  (let [now (quot (System/currentTimeMillis) 1000)
        exp (+ now (* duration-hours 60 60))
        payload (merge
                 {:iss (:issuer internal-config)
                  :sub (:subject internal-config)
                  :aud audience
                  :iat now
                  :exp exp}
                 claims)]
    (jwt/sign payload secret {:header {:typ "JWT"}})))

(defn unsign-jwt
  "Verifies and decodes an internal JWT token.

  conf: map containing :secret and :expected-audience
  token: JWT string to verify

  Returns the decoded claims if valid, throws exception otherwise."
  [{:keys [secret expected-audience]} token]
  (let [claims (jwt/unsign token secret)]
    ;; Verify issuer matches internal config
    (when-not (= (:iss claims) (:issuer internal-config))
      (throw (ex-info "Invalid issuer" {:expected (:issuer internal-config)
                                        :actual (:iss claims)})))
    ;; Verify subject matches internal config
    (when-not (= (:sub claims) (:subject internal-config))
      (throw (ex-info "Invalid subject" {:expected (:subject internal-config)
                                         :actual (:sub claims)})))
    ;; Verify audience matches expected value
    (when expected-audience
      (when-not (= (:aud claims) expected-audience)
        (throw (ex-info "Invalid audience" {:expected expected-audience
                                            :actual (:aud claims)}))))
    claims))

;; =============================================================================
;; Jitsi-Specific JWT Functions
;; =============================================================================

(defn create-jitsi-link
  "Creates a complete Jitsi meeting link with JWT authentication.

  conf: map containing :secret
  jitsi-config: map containing :base-url, :issuer, :audience, :subject
  user-name: display name for the user
  user-jid: user's XMPP address (e.g., 'alice@example.org')
  room-name: Jitsi room name
  opts: optional map with:
    - :avatar-url - user avatar URL
    - :moderator? - grant moderator privileges (default false)
    - :duration-hours - token validity in hours (default 2)"
  [{:keys [secret]} jitsi-config user-name user-jid room-name &
   {:keys [avatar-url moderator? duration-hours]
    :or {avatar-url nil moderator? false duration-hours 2}}]
  (let [now (quot (System/currentTimeMillis) 1000)
        exp (+ now (* duration-hours 60 60))
        user-context (cond-> {:name user-name
                              :email user-jid
                              :moderator moderator?}
                       avatar-url (assoc :avatar avatar-url))
        payload {:iss (:issuer jitsi-config)
                 :aud (:audience jitsi-config)
                 :sub (:subject jitsi-config)
                 :room room-name
                 :exp exp
                 :iat now
                 :context {:user user-context}}
        jwt-token (jwt/sign payload secret {:header {:typ "JWT"}})
        base-url (:base-url jitsi-config)]
    (str base-url "/" (java.net.URLEncoder/encode room-name "UTF-8")
         "?jwt=" (java.net.URLEncoder/encode jwt-token "UTF-8"))))
