# Optimisation Service Client

[![Maven Central](https://img.shields.io/maven-central/v/se.optimatika/optimisation-service-client)](https://central.sonatype.com/artifact/se.optimatika/optimisation-service-client)

A Java client for the [Optimisation Service](https://optimatika.se/optimisation-service/). Build an LP, QP or MIP model in code, submit it to the service — Optimatika's public shared instance to get started and test, your own deployment in production — and get the result back, all with plain Java and zero dependencies.

This client is the recommended way for Java developers to interact with the Optimisation Service. It is not required — the service also accepts models over plain REST in MPS or LP format — but it is the simplest path from code to solution.

The client needs a service to talk to. Optimatika's shared instance, `https://service-api.optimatika.se`, is free and needs no sign-up; without a licence key it runs ojAlgo's own solvers on models of up to 1,000 variables or constraints. Or run the service yourself: the image is public and can be pulled without credentials. A licence key from the [Optimatika Subscription](https://optimatika.se/subscription/) unlocks the full solver suite and additional capacity, on either.

## Design

- **No dependencies.** The entire client is plain Java using only the standard library. No JSON library, no HTTP framework, no build plugins beyond the compiler.
- **Simple types.** The API uses `BigDecimal`, `String`, `List`, `Map`, and `Future`. No custom exception hierarchies, no generic type parameters, no framework annotations.
- **Minimal code.** Eight source files in a single package: the model builder, the HTTP client, and the result types.
- **Java 11+.** Not required to be on the latest Java version.

## Maven coordinates

```xml
<dependency>
    <groupId>se.optimatika</groupId>
    <artifactId>optimisation-service-client</artifactId>
    <version>1.1.0</version>
</dependency>
```

## Usage

```java
// Connect to the service
OptClientV1 client = OptClientV1.newInstance("https://your-service-host");

// Build a model
OptModel model = client.newModel();

OptVariable x = model.newRealVariable("x").lower(0);
OptVariable y = model.newRealVariable("y").lower(0);

model.newConstraint("budget").set(x, 1).set(y, 1).upper(10);

model.getObjective().set(x, 3).set(y, 5);

// Solve remotely (asynchronous)
OptResult result = model.maximise().get();

System.out.println("Optimal: " + result.isOptimal());
System.out.println("Value:   " + result.doubleValue());
System.out.println("x = " + x.doubleValue());
System.out.println("y = " + y.doubleValue());
```

## Classes

| Class | Role |
|---|---|
| `OptClientV1` | HTTP client — submits models and polls for results |
| `OptModel` | Model builder — variables, constraints, objective, serialisation |
| `OptVariable` | Decision variable (real, integer, or binary) |
| `OptConstraint` | Linear constraint with bounds |
| `OptObjective` | Objective function (linear/quadratic) |
| `OptExpression` | Base for constraints and objective — holds coefficients |
| `OptResult` | Immutable result — feasible/optimal status, objective value, solution vector |

## Two layers

The client is split into two distinct layers:

- **`OptClientV1`** is a thin HTTP client wrapper. It knows how to submit bytes to the server and parse the JSON response, but knows nothing about what a model looks like. You can use it standalone to submit MPS files or any other format the server supports.
- **`OptModel`** is a model builder. It provides the fluent API for variables, constraints, and objectives, handles serialisation, and manages the poll loop and result mapping. It delegates all HTTP communication to an `OptClientV1` instance passed to its constructor.

This separation means you can use the HTTP client directly without the model builder, or replace the model builder without touching the HTTP layer.

`OptModel` is written much like ojAlgo's `ExpressionsBasedModel`. To move a model from one to the other, see [ExpressionsBasedModel and OptModel, side by side](https://optimatika.se/optimisation-service/docs/expressionsbasedmodel-vs-optmodel/).

The `putOnQueue` and `pollResult` method signatures match ojAlgo's `Optimisation.ModelSubmitter` and `Optimisation.ResultPoller` functional interfaces, so they can be passed directly as method references.

## How it works

1. You build a model using `OptModel` and its fluent API.
2. Calling `maximise()` or `minimise()` serialises the model and POSTs it to the server.
3. The client polls the server until the solver finishes.
4. The result is parsed, solution values are written back to the variables, and the `Future<OptResult>` completes.

Where the server supports it, the submit request stays open and the server streams the status over it, so polls are answered from what has already arrived instead of with a request each. If the connection drops, the next poll reconnects with the key. Against a server that does not stream, every poll is a request, as before. Nothing in your code changes either way. A reconnect that reaches a server that does not know the solve — it has expired, or the request landed on another instance — throws `IOException` from `pollResult`. Through `OptModel`, the `Future` then completes exceptionally with it, as it does when the service can't be reached: `get()` throws `ExecutionException` with the `IOException` as its cause. Resubmitting is up to you.

Models can also be submitted directly as MPS files via `OptClientV1.putOnQueue()`.

## Aborting

A solve that runs longer than you are willing to wait can be abandoned. Through
`OptModel`, cancel the `Future`:

```java
Future<OptResult> future = model.minimise();

// ... later, having decided not to wait any longer
future.cancel(true);
```

`cancel(true)` aborts the solve on the server too, freeing the worker it occupies
for the next model in the queue. The abort is sent in the background. On a
service that runs as several instances it can reach one that doesn't know the
solve; it is then sent again, at growing intervals, for up to 10 minutes, and a
warning is logged if none gets there. The model is never resubmitted.
`cancel(false)` abandons only the local side —
polling stops and the `Future` reports cancelled, but the server keeps solving,
and since `OptModel` does not expose the queue key that solve cannot be reached
again.

The same thing on the HTTP client, where the key is visible:

```java
Map<String, Object> submitted = client.putOnQueueParsed(mpsData, "MPS", false);
String key = (String) submitted.get(OptClientV1.KEY);

client.abortParsed(key);
```

Either way the solve goes straight to `DONE` with no result — the partial solution the
solver had reached is discarded — so a client polling it stops polling and finds
nothing to read. Other solves are untouched, whether they are already running or
still waiting in the queue. Aborting a solve that has already finished is a
no-op: it keeps its result.

The server solves a fixed number of models at once and queues the rest, so a
model submitted while the server is at capacity is accepted and given a key but
produces no result until a slot frees up. Only a full queue is refused outright.
`getServiceEnvironment()` reports that number, and how many solves are running and
queued right now, under `capacity`.

## Licence key

On Optimatika's shared instance, a request with a licence key in force gets the full solver suite and is billed to the key's subscription; without one it runs with the limits of no key. Give the client the key, and it sends it with every request as `Authorization: Bearer <key>` – over HTTPS only:

```java
OptClientV1 client = OptClientV1.newInstance("https://your-service-host", licenceKey);
```

A deployment of your own ignores the header: its key is configured on the deployment.

## Use with ojAlgo

If you use [ojAlgo](https://www.ojalgo.org/), you can wire this client into `ExpressionsBasedModel` so that calling `submit()` solves remotely:

```java
OptClientV1 client = OptClientV1.newInstance("https://your-service-host");

Optimisation.Environment environment = Optimisation.newEnvironment();
environment.setRemoteSolver(client::putOnQueue, client::pollResult);

ExpressionsBasedModel model = environment.newModel();
// ... build your model ...

Future<Optimisation.Result> future = model.submit(Optimisation.Sense.MIN);
Optimisation.Result result = future.get();
```

## Examples

Complete, runnable examples — one file per problem type. Each builds a model, solves it on the Optimisation Service, and verifies the result. They run against the shared instance, without a key, unless `SERVICE_HOST` names another service (see [Building and testing](#building-and-testing)).

| Problem | Type | Source |
|---|---|---|
| Diet problem | LP | [DietProblemTest](src/test/java/se/optimatika/optimisation/service/client/example/DietProblemTest.java) |
| Maximum flow | LP | [MaximumFlowTest](src/test/java/se/optimatika/optimisation/service/client/example/MaximumFlowTest.java) |
| Shortest path | LP | [ShortestPathTest](src/test/java/se/optimatika/optimisation/service/client/example/ShortestPathTest.java) |
| Newsvendor (stochastic) | LP | [NewsvendorTest](src/test/java/se/optimatika/optimisation/service/client/example/NewsvendorTest.java) |
| Portfolio optimisation (Markowitz) | QP / MIQP | [PortfolioOptimisationTest](src/test/java/se/optimatika/optimisation/service/client/example/PortfolioOptimisationTest.java) |
| Knapsack | MILP | [KnapsackTest](src/test/java/se/optimatika/optimisation/service/client/example/KnapsackTest.java) |
| Assignment | MILP | [AssignmentProblemTest](src/test/java/se/optimatika/optimisation/service/client/example/AssignmentProblemTest.java) |
| Bin packing | MILP | [BinPackingTest](src/test/java/se/optimatika/optimisation/service/client/example/BinPackingTest.java) |
| Travelling salesman (MTZ) | MILP | [TSPTest](src/test/java/se/optimatika/optimisation/service/client/example/TSPTest.java) |
| Vehicle routing (capacitated) | MILP | [VRPTest](src/test/java/se/optimatika/optimisation/service/client/example/VRPTest.java) |

## Changes

What changed in each release is in [CHANGELOG.md](CHANGELOG.md).

## Building and testing

```sh
mvn package
```

The tests run against Optimatika's shared instance, without a licence key, so the build needs network access. To run them against another service, such as a container of your own, set `SERVICE_HOST`:

```sh
docker run --rm -p 8080:8080 ghcr.io/optimatika/optimisation-service
SERVICE_HOST=http://localhost:8080 mvn test
```

- `mvn test -DexcludedGroups=integration` runs only the tests that need no service: they check the client against a stand-in of its own.
- `AbortAndCapacityTest` fills a server's workers and queue, so it needs a server to itself. Against the shared instance it skips itself; run it with `SERVICE_HOST`.
- `testTooManyVariables` checks the size limit the server reports. A licensed server reports none, and there it skips itself.

`InstanceCheck`, among the tests, is a program rather than a test. It checks an Optimisation Service instance the way this client uses it — your own deployment, or Optimatika's shared instance when no URL is given. It reports what the instance says about itself, checks that it solves and refuses models as it should, that it streams status and can abort a solve, and, given a licence key, whether the instance goes by the key and what the key gets. On a licensed instance its solves count as usage, typically well under one core-minute:

```sh
mvn -q test-compile
java -cp target/classes:target/test-classes se.optimatika.optimisation.service.client.InstanceCheck [instance URL] [licence key]
```

Set `OPTIMATIKA_LICENCE_KEY` instead of giving the key, to keep it out of your shell's history.

## License

This software is released into the public domain — see [UNLICENSE](UNLICENSE).
