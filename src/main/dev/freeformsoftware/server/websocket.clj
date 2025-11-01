(ns dev.freeformsoftware.server.websocket
  (:require
   [ring.websocket :as ws]
   [taoensso.telemere :as tel]))


(set! *warn-on-reflection* true)

^:clj-reload/keep
(def clients (atom #{}))

(defn broadcast!
  "Send a message to all connected clients"
  [message]
  (doseq [client @clients]
    (try
      (ws/send client message)
      (catch Exception e
        (tel/error! "Failed to send websocket message" {:error e})))))

(defn reload-handler
  "WebSocket handler for dev reload"
  [req]
  (assert (ws/upgrade-request? req))
  {::ws/listener {:on-open    (fn [socket]
                                (swap! clients conj socket)
                                (tel/log! :info "WebSocket client connected"))

                  :on-message (fn [socket message]
                                (tel/log! :debug ["WebSocket message received:" message]))

                  :on-close   (fn [socket status-code reason]
                                (swap! clients disj socket)
                                (tel/log! :info
                                          ["WebSocket client disconnected"
                                           {:status status-code :reason reason}]))

                  :on-error   (fn [socket error]
                                (swap! clients disj socket)
                                (tel/error! "WebSocket error" {:error error}))}})
