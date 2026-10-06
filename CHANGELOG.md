# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Route interceptors by keyword: `{:interceptors [:auth/jwt :rate-limit]}` are looked up in `:interceptors`, the map that
  `borba-interceptors-component` builds, and an interceptor map is used as it is. Until now only maps were taken, and the
  keywords the interceptors component documents were not resolved.
- `:name` in the options of a route, so that a handler can have more than one route.
- Every route is checked when the system starts: its shape, the method, the handler key, the interceptors and the options, and a
  method and path or a name that two routes share. A failure names the route and says why.
- A request that no route matches is sent to the handler `:borba/not-found` of `borba-handlers-component`, which answers it with
  a typed 404: for an unknown path, for the root, and for a method a path does not have.
- `borba.routes.component/build`, which builds the routes without starting a system.
- A test suite with 100% of the lines covered.

### Changed

- **Breaking:** the value of `:http/routes` is a Pedestal routing fragment, where it was the expanded routing table, so that it
  goes to `io.pedestal.connector/with-routes` of Pedestal 0.8.
- **Breaking:** the handlers are the `{:chain :handler}` that `borba-handlers-component` 2 builds, where they were vectors of
  interceptors with the handler last.
- **Breaking:** a handler that is used by two routes needs a `:name` on each, because the name of a route is its handler key
  unless it has one, and the names are unique.
- **Breaking:** moves to Pedestal 0.8.2 and Integrant 1.0, where a reference must be a qualified keyword.
- The component logs through `tools.logging` instead of printing, and no longer depends on a logging backend.
- The published library is named `io.github.af2b/borba-routes-component`.

## [1.0.0] - 2026-03-30

First release: the `:http/routes` Integrant component, which builds a Pedestal route table from route tuples and the chains of
the handlers.

[Unreleased]: https://github.com/AF2B/borba-routes-component/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/AF2B/borba-routes-component/releases/tag/v1.0.0
