(ns borba.routes.component
  "The Integrant component that builds the routes of a service:

     :http/routes
     {:routes       [[\"/health\" :get :health/check]
                     [\"/v1/users\" :post :user/create]
                     [\"/v1/admin\" :get :admin/list
                      {:interceptors [:auth/jwt :rate-limit]}]]
      :handlers     #ig/ref :service/handlers
      :interceptors #ig/ref :service/interceptors}

   A route is a vector of a path, an HTTP method, the key of a handler and,
   optionally, a map of options:

     :interceptors  the interceptors to run for this route, after the ones every
                    handler has and before the handler: keywords, which are
                    looked up in :interceptors (the map that
                    `:service/interceptors` builds), or interceptor maps
     :name          the name of the route, which is the handler key unless
                    the handler has more than one route

   The chain of a route is the one the handler gets from `:service/handlers`,
   with the interceptors of the route before the handler itself.

   A request that no route matches goes to the handler `:borba/not-found`,
   which answers it with a typed 404.

   Everything is checked when the system starts, so that a typo in a route is
   a failure of the start that names the route, and not a 404 found later: the
   shape of the route, the method, the handler key, the interceptors, and a
   method and path or a name that is used twice.

   The value is a Pedestal routing fragment, which borba-server-component
   hands to the router."
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [io.pedestal.http.route :as route]
   [io.pedestal.http.route.definition.table :as table]))

(def ^:private http-methods #{:get :post :put :patch :delete :head :options})
(def ^:private route-options #{:interceptors :name})

(def ^:private not-found-key :borba/not-found)
(def ^:private not-found-name :borba/not-found)
(def ^:private not-found-root-name :borba/not-found-root)
(def ^:private not-found-root-path "/")
(def ^:private not-found-path "/*unmatched")
(def ^:private any-method :any)

(def ^:private min-route-length 3)
(def ^:private max-route-length 4)

(defn- invalid-route
  [route
   message
   data]
  (ex-info (str "the route " (pr-str route) " is not valid: " message)
           (merge {:error ::invalid-route
                   :route route}
                  data)))

(defn- check-shape
  "Fails when a route is not a path, a method, a handler key and options."
  [route]
  (when-not (and (vector? route)
                 (<= min-route-length (count route) max-route-length))
    (throw (invalid-route route
                          (str "it is [path method handler-key] or"
                               " [path method handler-key options]")
                          {})))
  (let [[path method handler-key options] route]
    (when-not (and (string? path) (str/starts-with? path "/"))
      (throw (invalid-route route "the path is a string that starts with /"
                            {:path path})))
    (when-not (contains? http-methods method)
      (throw (invalid-route route
                            (str "the method is one of " (sort http-methods))
                            {:method method})))
    (when-not (keyword? handler-key)
      (throw (invalid-route route "the handler key is a keyword"
                            {:handler-key handler-key})))
    (when-not (or (nil? options) (map? options))
      (throw (invalid-route route "the options are a map" {:options options})))
    (when-let [unknown (seq (remove route-options (keys options)))]
      (throw (invalid-route route
                            (str "the options can be "
                                 (sort route-options)
                                 ", and these are not: "
                                 (vec unknown))
                            {:unknown (vec unknown)})))
    (when-not (or (nil? (:interceptors options))
                  (sequential? (:interceptors options)))
      (throw (invalid-route route
                            "the interceptors are a vector"
                            {:interceptors (:interceptors options)})))))

(defn- handler-entry
  "Returns the chain and the handler of a handler key, and fails when there is
   none, or when it is not the map that `:service/handlers` builds."
  [route
   handlers
   handler-key]
  (let [entry (get handlers handler-key)]
    (when (nil? entry)
      (throw (ex-info (str "the route " (pr-str route)
                           " names the handler " handler-key
                           ", which is not registered")
                      {:error       ::unknown-handler
                       :route       route
                       :handler-key handler-key})))
    (when-not (and (map? entry)
                   (vector? (:chain entry))
                   (map? (:handler entry)))
      (throw (ex-info (str "the handler " handler-key " is not a chain and a"
                           " handler: are the handlers from"
                           " borba-handlers-component 2 or later?")
                      {:error       ::invalid-handler-entry
                       :route       route
                       :handler-key handler-key})))
    entry))

(defn- route-interceptor
  "Returns the interceptor a route asks for: the map it gave, or the one of
   that name in the map of interceptors."
  [route
   available
   interceptor]
  (cond
    (map? interceptor)
    interceptor

    (keyword? interceptor)
    (or (get available interceptor)
        (throw (ex-info (str "the route " (pr-str route)
                             " asks for the interceptor " interceptor
                             ", which is not registered; is :interceptors"
                             " configured?")
                        {:error       ::unknown-interceptor
                         :route       route
                         :interceptor interceptor})))

    :else
    (throw (invalid-route route
                          "an interceptor is a keyword or a map"
                          {:interceptor interceptor}))))

(defn- plan-route
  "Checks a route and returns it as data: its path, method and name, and the
   chain it runs."
  [{:keys [handlers interceptors]}
   route]
  (check-shape route)
  (let [[path method handler-key options] route
        entry (handler-entry route handlers handler-key)]
    {:path    path
     :method  method
     :handler handler-key
     :name    (or (:name options) handler-key)
     :chain   (-> (:chain entry)
                  (into (map #(route-interceptor route
                                                 (or interceptors {})
                                                 %)
                             (:interceptors options)))
                  (conj (:handler entry)))}))

(defn- duplicate
  "Returns the first value that more than one route has for a function, or nil
   when there is none."
  [planned
   f]
  (->> planned
       (group-by f)
       (filter #(> (count (val %)) 1))
       ffirst))

(defn- check-unique
  "Fails naming the first method and path, or the first name, that two routes
   have."
  [planned]
  (when-let [duplicate-route (duplicate planned (juxt :method :path))]
    (throw (ex-info (str "two routes have the same method and path: "
                         (pr-str duplicate-route))
                    {:error  ::duplicate-route
                     :method (first duplicate-route)
                     :path   (second duplicate-route)})))
  (when-let [duplicate-name (duplicate planned :name)]
    (throw (ex-info (str "two routes have the same name, " duplicate-name
                         ": give one of them a :name")
                    {:error ::duplicate-route-name
                     :name  duplicate-name}))))

(defn- not-found-routes
  "Returns the routes that send what no route matches to the handler of a 404:
   the root, which a wildcard does not match, and everything else."
  [handlers]
  (when-let [entry (get handlers not-found-key)]
    (let [chain (conj (:chain entry) (:handler entry))]
      [{:path not-found-root-path :method any-method
        :name not-found-root-name :chain chain}
       {:path not-found-path :method any-method
        :name not-found-name :chain chain}])))

(defn build
  "Builds the routes of a service, and returns them as a Pedestal routing
   fragment. Fails naming the route when it is not valid, names a handler or an
   interceptor that is not registered, or uses a method and path or a name that
   another route does.
   - routes: a vector of routes, each [path method handler-key] or
     [path method handler-key options]
   - handlers: the map that `:service/handlers` builds
   - interceptors: the map that `:service/interceptors` builds, where the
     interceptors of a route are looked up"
  [{:keys [routes handlers interceptors]}]
  (let [planned  (mapv #(plan-route {:handlers     handlers
                                     :interceptors interceptors}
                                    %)
                       routes)
        _        (check-unique planned)
        fragment (table/table-routes
                  (mapv (fn [{:keys [path method chain] route-name :name}]
                          [path method chain :route-name route-name])
                        (into planned (not-found-routes handlers))))]
    ;; Pedestal reports what it does not accept, such as an interceptor that is
    ;; not valid, when it expands the routes: do it now, and once.
    (binding [route/*print-routing-table* false]
      (route/expand-routes fragment))
    (doseq [{:keys [method path handler]} planned]
      (log/debugf "route %s %s -> %s"
                  (str/upper-case (name method))
                  path
                  handler))
    fragment))

(defmethod ig/init-key :http/routes
  [_ {:keys [routes] :as options}]
  (let [fragment (build options)]
    (if (seq routes)
      (log/infof "registered %d route(s)" (count routes))
      (log/warn "no routes are configured: every request will be a 404"))
    fragment))
