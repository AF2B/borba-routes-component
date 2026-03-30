(ns borba.routes.component
  "Integrant component for :http/routes.

   Builds a Pedestal route table from a vector of route tuples and a map of
   pre-built handler chains (provided by :service/handlers).

   ── Route tuple format ───────────────────────────────────────────────────────

   Simple:
     [\"/health\" :get :health/check]

   With route-level interceptors:
     [\"/v1/admin\" :get :admin/list {:interceptors [#ig/ref :interceptors/auth]}]

   The :interceptors vector contains already-resolved Pedestal interceptor maps
   (Integrant resolves #ig/ref values before calling ig/init-key). They are
   inserted just before the handler function interceptor, in the order given.

   ── Interceptor execution order ──────────────────────────────────────────────

   For a route with interceptors [A B C]:
     error-handler → inject-components → parse-body → parse-query →
     parse-path-params → parse-headers → json-response →
     (handler-interceptors from defmulti) →
     A → B → C →
     handler-fn

   ── EDN configuration ────────────────────────────────────────────────────────

     :http/routes
     {:routes   [[\"/health\" :get :health/check]
                 [\"/v1/users\" :post :user/create]
                 [\"/v1/admin\" :get :admin/list
                  {:interceptors [#ig/ref :interceptors/auth-jwt
                                  #ig/ref :interceptors/rate-limit]}]]
      :handlers #ig/ref :service/handlers}"
  (:require [integrant.core :as ig]
            [io.pedestal.http.route :as route]
            [clojure.string :as str]))

(defn- build-route
  "Builds a single Pedestal route tuple from a route config entry.
   Inserts route-level interceptors just before the handler-fn interceptor."
  [[path method handler-key & [opts]] handlers]
  (let [chain        (get handlers handler-key)
        _            (when (nil? chain)
                       (throw (ex-info (str "[routes] No handler registered for: " handler-key)
                                       {:handler-key handler-key})))
        extra-inters (:interceptors opts [])
        ;; Insert route-level interceptors between the base chain and the handler fn.
        ;; The last element of chain is always the handler-fn interceptor.
        base-chain   (vec (butlast chain))
        handler-fn   (last chain)
        full-chain   (-> base-chain
                         (into extra-inters)
                         (conj handler-fn))]
    [path method full-chain :route-name handler-key]))

(defmethod ig/init-key :http/routes
  [_ {:keys [routes handlers]}]
  (when (empty? routes)
    (println "⚠️  [routes] No routes configured."))
  (let [route-table (into #{} (map #(build-route % handlers) routes))
        expanded    (route/expand-routes route-table)]
    (println (str "🛣️  [routes] Registered " (count routes) " route(s): "
                  (str/join ", " (map #(str (nth % 1) " " (nth % 0)) routes))))
    expanded))

(defmethod ig/halt-key! :http/routes [_ _] nil)
