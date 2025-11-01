(ns dev.freeformsoftware.server.internal-server
  (:require
   [clj-simple-router.core :as router]
   [integrant.core :as ig]
   [ring.adapter.jetty :as ring-jetty]
   [ring.middleware.resource :as resource]
   [ring.util.response :as response]
   [dev.freeformsoftware.server.internal-routes :as internal-routes]
   [dev.freeformsoftware.server.core :as server]
   [taoensso.telemere :as tel]
   [ring.middleware.defaults :as ring-defaults]
   [muuntaja.middleware :as muuntaja]
   [clojure.string :as str])
  (:import
    [org.eclipse.jetty.server Server]))

(set! *warn-on-reflection* true)

(defn handler
  [{:keys [env] :as config}]
  (let [create-router (memoize router/router)]
    (-> (fn [req]
          (let [router (server/wrap-tap>-exception (create-router (@internal-routes/!create-routes config)))]
            (router req)))
        (muuntaja/wrap-format)
        (ring-defaults/wrap-defaults
         (-> ring-defaults/api-defaults
             (assoc-in [:security :anti-forgery] false))))))

(defmethod ig/init-key ::server
  [_ {:keys [jetty] :as config}]
  (let [options  (merge {:port  3002
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
