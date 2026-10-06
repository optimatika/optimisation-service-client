# Changelog

All notable changes to `optimisation-service-client` are listed here. The
format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
the project uses [Semantic Versioning](https://semver.org/).

## [1.1.0] – unreleased

### Added

- **Licence key.** `OptClientV1(URI, String)` and
  `OptClientV1.newInstance(String, String)` make a client that sends the key
  with every request, as `Authorization: Bearer <key>`. On Optimatika's shared
  instance a key in force gets the full solver suite, and its solves are billed
  to the key's subscription. A deployment of your own ignores the header. Send a
  key only over HTTPS.
- **Streamed status.** Where the service supports it, `putOnQueue` keeps the
  request open and the service sends each change of status over it, so
  `pollResult` answers from what has already arrived. A connection that is cut
  is reopened by the next `pollResult`, with the key. Against a service that
  does not stream, each call is one request, as before.

### Changed

- `pollResult` throws `IOException` when the service does not know the solve –
  it has expired, or a reconnect reached another instance. It used to return
  the error response as if it were a status.

### Deprecated

- `abortAll()`, and the map keys `QUEUED` and `ONGOING` it returned. Servers no
  longer offer abort-all: it reached only one of a service's instances, and let
  one client stop every other client's solves. `abortAll()` now throws
  `UnsupportedOperationException`. Abandon each solve with `abort(String)`, or
  by cancelling the `Future` from `OptModel`. All three go in 2.0.

## [1.0.0] – 2026-10-01

First release: `OptClientV1` for the Optimisation Service's REST API (`v1`),
and `OptModel` for building a model and solving it on the service, with no
dependency on ojAlgo.

[1.1.0]: https://github.com/optimatika/optimisation-service-client/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/optimatika/optimisation-service-client/releases/tag/v1.0.0
