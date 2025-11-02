(ns dev.freeformsoftware.server.core
  (:require
   [clj-simple-router.core :as router]
   [integrant.core :as ig]
   [ring.adapter.jetty :as ring-jetty]
   [ring.middleware.resource :as resource]
   [ring.util.response :as response]
   [dev.freeformsoftware.server.routes :as routes]
   [taoensso.telemere :as tel]
   [ring.middleware.defaults :as ring-defaults]
   [clojure.string :as str])
  (:import
    [org.eclipse.jetty.server Server]))

(set! *warn-on-reflection* true)

(defn wrap-tap>-exception
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (tel/error! "Handler error" e)
        (tap> e)
        (throw e)))))

(defn wrap-static-cache
  "Add Cache-Control headers for static assets (js/css)"
  [handler]
  (fn [request]
    (let [response (handler request)
          uri      (:uri request)]
      (if (and response
               (or (str/starts-with? uri "/js/")
                   (str/starts-with? uri "/css/")))
        (assoc-in response [:headers "Cache-Control"] "public, max-age=0")
        response))))

(defn handler
  [{:keys [env] :as config}]
  (let [create-router (memoize router/router)]
    (-> (fn [req]
          (let [router (wrap-tap>-exception (create-router (@routes/!create-routes config)))]
            (try
              (or (router req)
                  (response/redirect "/"))
              (catch Exception e (tel/error! "Handler error" e)))))
        (ring-defaults/wrap-defaults
         (-> (case env
                   (:dev :test)
                   ring-defaults/site-defaults
                   (:prod)
                   ring-defaults/secure-site-defaults)
             (assoc-in [:security :anti-forgery] false)
             (assoc-in [:security :frame-options] :deny)))
        (wrap-static-cache))))

(defmethod ig/init-key ::server
  [_ {:keys [jetty] :as config}]
  (let [options  (merge {:port  3001
                         :host  "0.0.0.0"
                         :join? false}
                        jetty)
        !handler (atom (handler config))]
    (tel/log! :info ["Starting server " options])
    {:!handler !handler
     :server   (ring-jetty/run-jetty (fn [req] (@!handler req))
                                     options)}))

(defmethod ig/suspend-key! ::server
  [_ _])

(defmethod ig/resume-key ::server
  [_ config _ {:keys [!handler] :as inst}]
  (reset! !handler (handler config))
  inst)

(defmethod ig/halt-key! ::server
  [_ {:keys [server] :as inst}]
  (.stop ^Server server))
