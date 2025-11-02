(ns dev.freeformsoftware.logging-conf
  "Integrant component for configuring logging settings."
  (:require
   [integrant.core :as ig]
   [taoensso.telemere :as tel])
  (:import
    [java.util.logging Logger Level]))

(set! *warn-on-reflection* true)

(defmethod ig/init-key ::logging-conf
  [_ {:keys [log-level]}]
  (let [level (or log-level :info)]
    ;; Set Telemere log level
    (tel/set-min-level! level)
    (println "Setting log level to" level)

    {:log-level level}))
