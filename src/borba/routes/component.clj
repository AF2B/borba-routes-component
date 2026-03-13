(ns borba.routes.component
  (:require [integrant.core :as ig]))

(defn- expand-route
  [handlers [path method handler-key]]
  (let [interceptors (get handlers handler-key)]
    (when-not interceptors
      (throw (ex-info "Handler not found"
                      {:handler handler-key})))
    [path method interceptors :route-name handler-key]))

(defmethod ig/init-key :http/routes
  [_ {:keys [routes handlers]}]
  (into #{} (map (partial expand-route handlers) routes)))

(defmethod ig/halt-key! :http/routes
  [_ _] nil)
