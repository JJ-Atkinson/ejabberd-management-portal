(ns dev.freeformsoftware.auth.jwt
  (:require
   [buddy.sign.jwt :as jwt]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
    [java.nio.charset StandardCharsets]
    [java.security KeyFactory Signature]
    [java.security.spec PKCS8EncodedKeySpec]
    [java.util Base64]))

;; =============================================================================
;; Configuration
;; =============================================================================

;; Default internal JWT configuration
(def internal-config
  {:issuer  "dev.freeformsoftware.ejabberd-management-portal"
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
  (let [now     (quot (System/currentTimeMillis) 1000)
        exp     (+ now (* duration-hours 60 60))
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
      (throw (ex-info "Invalid issuer"
                      {:expected (:issuer internal-config)
                       :actual   (:iss claims)})))
    ;; Verify subject matches internal config
    (when-not (= (:sub claims) (:subject internal-config))
      (throw (ex-info "Invalid subject"
                      {:expected (:subject internal-config)
                       :actual   (:sub claims)})))
    ;; Verify audience matches expected value
    (when expected-audience
      (when-not (= (:aud claims) expected-audience)
        (throw (ex-info "Invalid audience"
                        {:expected expected-audience
                         :actual   (:aud claims)}))))
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
    :or   {avatar-url nil moderator? false duration-hours 2}}]
  (let [now          (quot (System/currentTimeMillis) 1000)
        exp          (+ now (* duration-hours 60 60))
        user-context (cond-> {:name      user-name
                              :email     user-jid
                              :moderator moderator?}
                       avatar-url (assoc :avatar avatar-url))
        payload      {:iss     (:issuer jitsi-config)
                      :aud     (:audience jitsi-config)
                      :sub     (:subject jitsi-config)
                      :room    room-name
                      :exp     exp
                      :iat     now
                      :context {:user user-context}}
        jwt-token    (jwt/sign payload secret {:header {:typ "JWT"}})
        base-url     (:base-url jitsi-config)]
    (str base-url
         "/"     (java.net.URLEncoder/encode room-name "UTF-8")
         "?jwt=" (java.net.URLEncoder/encode jwt-token "UTF-8"))))

;; =============================================================================
;; Scoria-Specific JWT Functions
;; =============================================================================

(defn- now-seconds
  []
  (quot (System/currentTimeMillis) 1000))

(defn- utf8-bytes
  [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- base64-url-encode
  [bytes]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))

(defn- pem-body
  [pem]
  (-> (str pem)
      (str/replace #"-----BEGIN [^-]+-----" "")
      (str/replace #"-----END [^-]+-----" "")
      (str/replace #"\s" "")))

(defn- rsa-private-key
  [private-key-pem]
  (when (str/blank? private-key-pem)
    (throw (ex-info "Scoria private key PEM is required" {:type :missing-config})))
  (let [key-bytes (.decode (Base64/getDecoder) (pem-body private-key-pem))
        spec      (PKCS8EncodedKeySpec. key-bytes)]
    (.generatePrivate (KeyFactory/getInstance "RSA") spec)))

(defn- rs256-sign
  [private-key-pem signing-input]
  (let [signature (Signature/getInstance "SHA256withRSA")]
    (.initSign signature (rsa-private-key private-key-pem))
    (.update signature (utf8-bytes signing-input))
    (base64-url-encode (.sign signature))))

(defn create-scoria-jwt
  "Create a Scoria RS256 JWT for a portal user.

   scoria-config must include :private-key-pem, :issuer, and :audience. Concrete
   production values are supplied by deployment config, not this source tree."
  [{:keys [private-key-pem issuer audience kid]} user & {:keys [duration-days]
                                                         :or   {duration-days 15}}]
  (when (str/blank? issuer)
    (throw (ex-info "Scoria JWT issuer is required" {:type :missing-config})))
  (when (str/blank? audience)
    (throw (ex-info "Scoria JWT audience is required" {:type :missing-config})))
  (let [now       (now-seconds)
        exp       (+ now (* duration-days 24 60 60))
        header    (cond-> {:typ "JWT" :alg "RS256"}
                    (not (str/blank? kid)) (assoc :kid kid))
        payload   (cond-> {:iss issuer
                           :aud audience
                           :sub (:user-id user)
                           :iat now
                           :exp exp}
                    (:name user) (assoc :name (:name user)))
        encoded-h (base64-url-encode (utf8-bytes (json/generate-string header)))
        encoded-p (base64-url-encode (utf8-bytes (json/generate-string payload)))
        input     (str encoded-h "." encoded-p)]
    (str input "." (rs256-sign private-key-pem input))))
