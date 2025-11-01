(ns dev.freeformsoftware.run.debugging-nrepl-server
  (:require
   [integrant.core :as ig]
   [nrepl.server :as nrepl-server :refer [start-server]]
   [taoensso.telemere :as tel]))

(defmethod ig/init-key ::debugging-nrepl-server
  [_ {:keys [bind port] :or {bind "0.0.0.0" port 8001}}]
  (tel/log! :info ["Starting NREPL server" :bind bind :port port])
  (future
    (try (start-server :bind bind :port port)
         (catch Exception e
           (tel/error! "Unable to start NREPL server!" e)))))

(defmethod ig/halt-key! ::debugging-nrepl-server
  [_ nrepl-server]
  (nrepl-server/stop-server nrepl-server))
