(ns dev.freeformsoftware.server.route-utils
  (:require
   [clojure.set :as set]))

(set! *warn-on-reflection* true)

(defn merge-routes
  [& routes]
  (reduce (fn [acc rts]
            (if-let [duplicate-routes (seq (set/intersection (set (keys acc)) (set (keys rts))))]
              (let [ex (ex-info "Duplicate routes detected!" {:dup duplicate-routes})]
                (tap> ex)
                (throw ex))
              (merge acc rts)))
          routes))

(defn wrap-routes 
  [wrap-fn routes]
  (update-vals routes wrap-fn))