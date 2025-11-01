(ns dev.freeformsoftware.server.routes
  (:require
   [cheshire.core :as json]
   [dev.freeformsoftware.server.route-utils :as server.route-utils]
   [dev.freeformsoftware.server.websocket :as websocket]
   [dev.freeformsoftware.server.auth-middleware :as auth-middleware]
   [dev.freeformsoftware.auth.jwt :as jwt]
   [dev.freeformsoftware.ui.pages :as ui.pages]
   [dev.freeformsoftware.ui.pages.signup :as ui.pages.signup]
   [dev.freeformsoftware.ui.pages.send-meet-invite :as ui.pages.send-meet-invite]
   [ring.util.response :as resp]))

(set! *warn-on-reflection* true)

(defn admin-routes
  [conf]
  (ui.pages/page-routes conf))

(defn signup-routes
  [conf]
  (ui.pages.signup/routes conf))

(defn meet-invite-routes
  [conf]
  (ui.pages.send-meet-invite/routes conf))

(defn etc-routes
  [conf]
  {"GET /debug-claims" (fn [request]
                         {:status  200
                          :headers {"Content-Type" "text/html"}
                          :body    (str "Authenticated! Claims: " (:jwt-claims request))})})

(defn noauth-routes
  [conf]
  {"GET /health"      (fn [_request]
                        (let [admin-bot  (:admin-bot conf)
                              connection (:connection admin-bot)
                              status     (if connection
                                           (let [^org.jivesoftware.smack.AbstractXMPPConnection conn connection]
                                             {:status    "ok"
                                              :admin-bot {:connected     (.isConnected conn)
                                                          :authenticated (.isAuthenticated conn)}})
                                           {:status    "degraded"
                                            :admin-bot {:connected     false
                                                        :authenticated false
                                                        :error         (:error admin-bot)}})]
                          {:status  200
                           :headers {"Content-Type" "application/json"}
                           :body    (json/generate-string status)}))
   "GET /favicon.ico" (constantly {:status 404
                                   :body   nil})})

(defn prod-routes
  [conf]
  (server.route-utils/merge-routes
   (server.route-utils/wrap-routes
    (partial auth-middleware/wrap-admin-auth conf)
    (admin-routes conf))
   (server.route-utils/wrap-routes
    (partial auth-middleware/wrap-signup-auth conf)
    (signup-routes conf))
   (server.route-utils/wrap-routes
    (partial auth-middleware/wrap-meet-invite-auth conf)
    (meet-invite-routes conf))
   (etc-routes conf)))

(defn dev-routes
  [conf]
  {"GET /dev/reload-ws"   websocket/reload-handler
   "GET /dev/login"       (fn [_]
                            (let [admin-jwt (jwt/create-jwt
                                             {:secret   (:jwt-secret conf)
                                              :audience (:management-portal-url-base conf)}
                                             {:role :admin
                                              :user "dev-admin"}
                                             :duration-hours
                                             24)]
                              (resp/redirect (str "/?jwt=" admin-jwt))))
   "GET /dev/test-signup" (fn [_]
                            (let [signup-jwt (jwt/create-jwt
                                              {:secret   (:jwt-secret conf)
                                               :audience (:management-portal-url-base conf)}
                                              {:role    :signup
                                               :user-id "jamess"
                                               :name    "James Smith"}
                                              :duration-hours
                                              24)]
                              (resp/redirect (str "/signup?jwt=" signup-jwt))))
   "GET /dev/logout"      (fn [_]
                            (-> (resp/redirect "/")
                                (resp/set-cookie "jwt" "" {:max-age 0 :path "/"})))})

(defn create-routes
  [{:keys [env] :as conf}]
  (let [routes
        (server.route-utils/merge-routes
         (server.route-utils/wrap-routes
          (partial auth-middleware/wrap-jwt-auth conf)
          (prod-routes conf))
         (noauth-routes conf)
         (when (= env :dev) (dev-routes conf)))]
    ;; (tap> routes)
    routes))

^:clj-reload/keep
(defonce !create-routes (atom nil))
(reset! !create-routes create-routes)
