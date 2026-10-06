# borba-routes-component

[![CI](https://github.com/AF2B/borba-routes-component/actions/workflows/ci.yml/badge.svg)](https://github.com/AF2B/borba-routes-component/actions/workflows/ci.yml)

The routes of a Borba service: a table of paths, methods and handler keys that an [Integrant](https://github.com/weavejester/integrant)
component checks, joins to the chains of `borba-handlers-component` and hands to [Pedestal](https://pedestal.io) as a routing
fragment. A typo in a route is a failure of the start that names the route, and everything that no route matches is a typed 404.

## Install

```clojure
io.github.af2b/borba-routes-component
{:git/url "https://github.com/AF2B/borba-routes-component"
 :git/tag "v2.0.0"
 :git/sha "<the commit of the tag, printed in the release notes>"}
```

It depends on Clojure, Integrant, `tools.logging` and Pedestal's routing module. The server itself comes from
`borba-server-component`.

## Use

A route is a vector of a path, an HTTP method, the key of a handler, and optionally a map of options:

```clojure
{:ig/system
 {:http/routes
  {:routes       [["/health" :get :health/check]
                  ["/v1/users" :post :user/create]
                  ["/v1/users/:id" :get :user/show]
                  ["/v1/admin" :get :admin/list
                   {:interceptors [:auth/jwt :rate-limit]}]]
   :handlers     #ig/ref :service/handlers
   :interceptors #ig/ref :service/interceptors}}}
```

- **The path** starts with `/` and can have parameters (`:id`, which the handler gets in `:path-params`) and a wildcard (`*rest`).
- **The method** is `:get`, `:post`, `:put`, `:patch`, `:delete`, `:head` or `:options`.
- **The handler key** is one that `borba-handlers-component` has registered. The chain of the route is the one the handler gets
  from `:service/handlers`.
- **The options** are `:interceptors` and `:name`.

The value of the component is a Pedestal routing fragment, which `borba-server-component` hands to the router. Nothing else
needs to know how the table is built.

### Interceptors of a route

`:interceptors` lists what to run for this route, after the interceptors every handler has and before the handler itself, in
the order given. A keyword is looked up in `:interceptors`, the map that `borba-interceptors-component` builds; a map is used as it
is:

```clojure
["/v1/admin" :get :admin/list {:interceptors [:auth/jwt :rate-limit]}]
```

For `GET /v1/admin` the chain is: the request id, the access log, the error handler, the parsers, the interceptors the handler
asks for itself, then `:auth/jwt`, `:rate-limit`, and the handler.

### Names

Each route has a name, which is the handler key. A handler used by more than one route needs a `:name` on each, because the
names of the routes are unique:

```clojure
[["/v1/users/:id" :get :user/show {:name :user/show-by-id}]
 ["/v1/people/:id" :get :user/show {:name :user/show-by-person}]]
```

## What is checked

Everything is checked when the system starts, so that what is wrong is found by the person who changed it and not by the first
request:

| `:error` | When |
|---|---|
| `::invalid-route` | A route is not `[path method handler-key]` or `[path method handler-key options]`, the path does not start with `/`, the method is not one of HTTP, or the options are not a map, have an option that is not `:interceptors` or `:name`, or have `:interceptors` that is not a vector of keywords and maps |
| `::unknown-handler` | The handler key is not registered (`:handler-key` and `:route` in the data) |
| `::unknown-interceptor` | A keyword of `:interceptors` is not in the map of interceptors (`:interceptor` and `:route` in the data) |
| `::invalid-handler-entry` | A handler is not the `{:chain :handler}` that `borba-handlers-component` 2 builds |
| `::duplicate-route` | Two routes have the same method and path |
| `::duplicate-route-name` | Two routes have the same name; give one of them a `:name` |

```clojure
(component/build {:routes   [["/x" :get :nobody/home]]
                  :handlers handlers})
;; throws ExceptionInfo "the route [\"/x\" :get :nobody/home] names the handler :nobody/home, which is not registered"
;;   {:error :borba.routes.component/unknown-handler
;;    :route ["/x" :get :nobody/home]
;;    :handler-key :nobody/home}
```

Pedestal's own checks, such as an interceptor that cannot be a Pedestal interceptor, run at the same time.

## What no route matches

A request that no route matches is sent to the handler `:borba/not-found`, which `borba-handlers-component` builds, so it gets
the same typed body, with a request id, as any other failure. That covers an unknown path, the root when it is not a route, and a
method that a path does not have:

```
GET /nowhere   (X-Request-Id: rid-9)
=> 404 {"error":"not-found","message":"No route matches the request.","request-id":"rid-9"}

DELETE /things
=> 404 {"error":"not-found","message":"No route matches the request.","request-id":"ec9d11ab-..."}
```

Without a `:borba/not-found` among the handlers there is no fallback, and the 404 is Pedestal's.

## Run together with the rest

The routes, the handlers and the interceptors, started as a system and called through a connector:

```
POST /things  {"a":1}
=> 201 {"received":{"a":1},"stamped":true,"id":"49d41270-..."}        (the handler asked for :stamp)

GET /things/42                                                        (the route asked for :stamp)
=> 200 {"id":"42"}

POST /things  {bad
=> 400 {"error":"invalid-json","message":"The request body is not valid JSON.","request-id":"01eae158-..."}
```

## API

| Name | What it does |
|---|---|
| `borba.routes.component/build` | Checks the routes and builds the Pedestal routing fragment |
| `:http/routes` | The Integrant key that does it, with `:routes`, `:handlers` and `:interceptors` as options |

## Design notes

- **A route table is data, and it is checked as data.** The shape, the names and the references are validated before anything is
  built, so the message names the route and what is wrong with it.
- **The fallback is a route, not a hook.** Everything that no route matches is routed to a handler like any other, with the same
  chain, so there is one place that decides what an error looks like.
- **The handler decides the chain.** A route adds interceptors after the ones its handler has, so a route cannot take away what
  every handler is meant to have.

## Development

```bash
make check      # lint, format, conventions, reflection, tests, coverage
make ci         # everything the pipelines enforce
```

See [CONTRIBUTING.md](CONTRIBUTING.md). The repository follows the [Borba standard](https://github.com/AF2B/borba-tooling/blob/main/docs/standard.md).

## License

[MIT](LICENSE)
