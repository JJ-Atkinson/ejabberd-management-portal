(ns dev.freeformsoftware.server.internal-routes
  (:require
   [dev.freeformsoftware.ejabberd.admin-bot :as admin-bot]
   [ring.util.response :as response]))

(set! *warn-on-reflection* true)

(defn create-routes
  [{:keys [admin-bot]}]
  {"POST /api/actions/send-omemo-lacking/*"
   (fn [{[user-id] :path-params}]
     (admin-bot/send-message!
      admin-bot
      {:local-part user-id :service :dm}
      (str "The message you just sent was not encrypted - please check your client "
           "configuration to ensure OMEMO encryption is enabled to send messages on this server."))
     (response/status 200))})

^:clj-reload/keep
(defonce !create-routes (atom nil))
(reset! !create-routes create-routes)
