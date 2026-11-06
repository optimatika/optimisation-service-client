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
  is reopened by the next `pollResult`, with the key. `abort` closes the
  stream first, so that an abort that reaches another instance still tells the
  one solving it that nobody follows the solve – where nothing in between keeps
  the stream open, as Cloud Run does. Against a service that does not stream,
  each call is one request, as before.

### Changed

- `cancel(true)` on the `Future` from `OptModel` no longer waits for the
  abort: it is sent in the background. If it reaches a service instance that
  does not know the solve, it is sent again after 1 s, 2 s, 4 s and so on, at
  most 2 minutes apart, for up to 10 minutes. If none gets there, a warning is
  logged through `System.Logger`, and the solve runs on until it finishes or
  reaches its time limit. The model is never submitted again.
- `pollResult` throws `IOException` when the service does not know the solve –
  it has expired, or a reconnect reached another instance. It used to return
  the error response as if it were a status.
- The `Future` from `OptModel` completes exceptionally, with an `IOException`
  as the cause, when the service cannot be reached, does not accept the model,
  or no longer knows the solve. It used to complete with a result that was not
  feasible, as if the solver had failed. The model is not submitted again;
  that is up to you. A model the service refuses, for its size say, still
  completes with a result that is not feasible.

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
