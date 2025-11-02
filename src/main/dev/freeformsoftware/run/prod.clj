(ns dev.freeformsoftware.run.prod
  (:require
   [dev.freeformsoftware.config :as config]
   dev.freeformsoftware.db.user-db
   dev.freeformsoftware.ejabberd.admin-bot
   dev.freeformsoftware.ejabberd.ejabberd-api
   dev.freeformsoftware.ejabberd.sync-state
   dev.freeformsoftware.server.core
   [integrant.core :as ig]
   [taoensso.telemere.slf4j]
   [taoensso.telemere :as tel])
  (:gen-class))

(set! *warn-on-reflection* true)

(defonce !system (atom nil))

(defn start-server!
  []
  (reset! !system
    (ig/init (config/resolve-config! false))))

(defn -main
  [& args]
  (start-server!))