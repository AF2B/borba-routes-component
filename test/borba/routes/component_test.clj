(ns borba.routes.component-test
  (:require
   [borba.routes.component :as component]
   [borba.routes.logging :as logging]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [integrant.core :as ig]
   [io.pedestal.connector :as conn]
   [io.pedestal.connector.test :as test]
   [io.pedestal.http.jetty :as jetty]
   [io.pedestal.http.route :as route]))

(set! *warn-on-reflection* true)

(defn- stamp
  "An interceptor that leaves its label in the request."
  [label]
  {:name  (keyword "test" (name label))
   :enter (fn [ctx]
            (update-in ctx [:request :stamps] (fnil conj []) label))})

(defn- describe-request
  "What the handler of a test says about the request it got: its label, the
   path parameters and the stamps it got on the way."
  [label
   request]
  (str (name label)
       " " (pr-str (:path-params request))
       " " (str/join "," (map name (:stamps request)))))

(defn- answering
  "A handler entry whose handler describes the request it got."
  ([label]
   (answering label []))
  ([label chain]
   {:chain   chain
    :handler {:name  (keyword "handler" (name label))
              :enter (fn [ctx]
                       (assoc ctx
                              :response
                              {:status 200
                               :body   (describe-request label
                                                         (:request ctx))}))}}))

(def ^:private not-found
  {:chain   []
   :handler {:name  :borba/not-found
             :enter (fn [ctx]
                      (assoc ctx :response {:status 404 :body "no route"}))}})

(def ^:private handlers
  {:health/check (answering :health)
   :user/create  (answering :create)
   :user/show    (answering :show)
   :user/list    (answering :list [(stamp :base)])
   :borba/not-found not-found})

(def ^:private interceptors
  {:auth  (stamp :auth)
   :limit (stamp :limit)})

(defn- thrown-data
  [f]
  (try (f)
       nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- connector
  [fragment]
  (jetty/create-connector
   (conn/with-routes (conn/default-connector-map "127.0.0.1" 0) fragment)
   {:join? false}))

(defn- respond
  "Builds the routes, sends a request through them and returns the response."
  [options method url]
  (binding [route/*print-routing-table* false]
    (test/response-for (connector (component/build options)) method url)))

(defn- options
  [routes]
  {:routes       routes
   :handlers     handlers
   :interceptors interceptors})

(deftest dispatch-test
  (let [routes [["/health" :get :health/check]
                ["/v1/users" :post :user/create]
                ["/v1/users" :get :user/list]
                ["/v1/users/:id" :get :user/show]]
        run    (fn [method url]
                 (respond (options routes) method url))]
    (testing "sends each method and path to its handler"
      (is (= "health {} " (:body (run :get "/health"))))
      (is (= "create {} " (:body (run :post "/v1/users"))))
      (is (= "list {} base" (:body (run :get "/v1/users")))))

    (testing "reads the parameters of the path"
      (is (= "show {:id \"7\"} " (:body (run :get "/v1/users/7")))))

    (testing "sends what no route matches to the handler of the 404"
      (doseq [[method url] [[:get "/nowhere"]
                            [:get "/v1/users/7/orders/9"]
                            [:delete "/v1/users"]
                            [:put "/health"]
                            [:get "/"]]]
        (let [response (run method url)]
          (is (= 404 (:status response)) (str method " " url))
          (is (= "no route" (:body response))))))))

(deftest route-interceptors-test
  (testing "runs the interceptors of the route after the chain and before the
            handler, in the order given"
    (let [response (respond
                    (options [["/v1/users" :get :user/list
                               {:interceptors [:auth :limit]}]])
                    :get
                    "/v1/users")]
      (is (= "list {} base,auth,limit" (:body response)))))

  (testing "takes an interceptor given as a map, as it is"
    (let [response (respond
                    (options [["/v1/users" :get :user/list
                               {:interceptors [:auth (stamp :inline)]}]])
                    :get
                    "/v1/users")]
      (is (= "list {} base,auth,inline" (:body response)))))

  (testing "a route without options or without interceptors has none"
    (is (= "health {} "
           (:body (respond (options [["/health" :get :health/check {}]])
                           :get
                           "/health"))))
    (is (= "health {} "
           (:body (respond
                   (options [["/health" :get :health/check nil]])
                   :get
                   "/health"))))))

(deftest names-test
  (testing "a handler with more than one route needs a name for each"
    (let [routes [["/a" :get :health/check {:name :a}]
                  ["/b" :get :health/check {:name :b}]]]
      (is (= "health {} " (:body (respond (options routes) :get "/a"))))
      (is (= "health {} " (:body (respond (options routes) :get "/b"))))))

  (testing "says so when two routes have the same name"
    (is (= :borba.routes.component/duplicate-route-name
           (:error (thrown-data
                    #(component/build
                      (options [["/a" :get :health/check]
                                ["/b" :get :health/check]])))))))

  (testing "says so when two routes have the same method and path"
    (is (= {:error  :borba.routes.component/duplicate-route
            :method :get
            :path   "/a"}
           (thrown-data
            #(component/build
              (options [["/a" :get :health/check {:name :first}]
                        ["/a" :get :user/show {:name :second}]])))))))

(deftest invalid-route-test
  (let [invalid? (fn [route]
                   (= :borba.routes.component/invalid-route
                      (:error (thrown-data
                               #(component/build (options [route]))))))]
    (testing "a route is a path, a method, a handler key and options"
      (is (invalid? ["/a" :get]))
      (is (invalid? ["/a" :get :health/check {} :extra]))
      (is (invalid? {:path "/a"}))
      (is (invalid? nil)))

    (testing "the path is a string that starts with a slash"
      (is (invalid? ["a" :get :health/check]))
      (is (invalid? [:a :get :health/check])))

    (testing "the method is one of those of HTTP"
      (is (invalid? ["/a" :fetch :health/check]))
      (is (invalid? ["/a" "GET" :health/check]))
      (is (invalid? ["/a" :any :health/check])))

    (testing "the handler key is a keyword"
      (is (invalid? ["/a" :get "health/check"])))

    (testing "the options are a map with the options there are"
      (is (invalid? ["/a" :get :health/check [:auth]]))
      (is (invalid? ["/a" :get :health/check {:interceptor [:auth]}]))
      (is (invalid? ["/a" :get :health/check {:interceptors :auth}])))

    (testing "an interceptor is a keyword or a map"
      (is (invalid? ["/a" :get :health/check {:interceptors ["auth"]}])))

    (testing "says which route and why"
      (let [data (thrown-data
                  #(component/build (options [["a" :get :health/check]])))]
        (is (= ["a" :get :health/check] (:route data)))))))

(deftest unregistered-test
  (testing "a handler that is not registered fails the start, naming it"
    (is (= {:error       :borba.routes.component/unknown-handler
            :route       ["/x" :get :nobody/home]
            :handler-key :nobody/home}
           (thrown-data
            #(component/build (options [["/x" :get :nobody/home]]))))))

  (testing "an interceptor that is not registered fails the start, naming it"
    (is (= {:error       :borba.routes.component/unknown-interceptor
            :route       ["/x" :get :health/check {:interceptors [:ghost]}]
            :interceptor :ghost}
           (thrown-data
            #(component/build
              (options [["/x" :get :health/check
                         {:interceptors [:ghost]}]])))))
    (is (= :borba.routes.component/unknown-interceptor
           (:error (thrown-data
                    #(component/build
                      {:routes   [["/x" :get :health/check
                                   {:interceptors [:auth]}]]
                       :handlers handlers}))))))

  (testing "a handler that is not a chain and a handler fails the start"
    (is (= :borba.routes.component/invalid-handler-entry
           (:error (thrown-data
                    #(component/build
                      {:routes   [["/x" :get :old/style]]
                       :handlers {:old/style [{:name :h}]}})))))))

(deftest pedestal-checks-test
  (testing "an interceptor that Pedestal cannot use fails the start, and not
            the first request"
    (is (thrown? Throwable
                 (component/build
                  (options [["/x" :get :health/check
                             {:interceptors [{:name :bad
                                              :enter "not a function"}]}]]))))))

(deftest not-found-test
  (testing "has no fallback when the handlers have no handler for it"
    (let [fragment (component/build
                    {:routes   [["/health" :get :health/check]]
                     :handlers (dissoc handlers :borba/not-found)})]
      (is (= 1 (count (:routes (route/expand-routes fragment)))))))

  (testing "has a fallback for the root and for everything else"
    (let [fragment (component/build
                    (options [["/health" :get :health/check]]))]
      (is (= 3 (count (:routes (route/expand-routes fragment))))))))

(deftest component-test
  (testing "initialises into the routes, and says how many there are"
    (let [system  (atom nil)
          entries (logging/call-capturing
                   #(reset! system
                            (ig/init {:http/routes
                                      (options [["/health" :get :health/check]
                                                ["/v1/users" :post
                                                 :user/create]])})))]
      (is (= ["registered 2 route(s)"]
             (logging/messages (filter #(= :info (:level %)) entries))))
      (is (= ["route GET /health -> :health/check"
              "route POST /v1/users -> :user/create"]
             (logging/messages (filter #(= :debug (:level %)) entries))))
      (is (some? (:http/routes @system)))))

  (testing "warns when there are no routes"
    (let [entries (logging/call-capturing
                   #(ig/init {:http/routes (options [])}))]
      (is (= :warn (:level (first entries))))
      (is (str/includes? (:message (first entries)) "no routes")))))
