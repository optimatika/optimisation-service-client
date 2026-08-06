# Optimisation Service Client

A Java client for [Optimatika's](https://optimatika.se/) optimisation service. Build an LP, QP or MIP model in code, submit it to a remote solver, and get the result back — all with plain Java and zero dependencies.

The optimisation service does not require this – this client simply tries to make things simpler for users of that service. The optimisation server is a paid for service available at AWS, Azure and GCP. Without access to a server instance, this client code is of no use.

## Design

- **No dependencies.** The entire client is plain Java using only the standard library. No JSON library, no HTTP framework, no build plugins beyond the compiler.
- **Simple types.** The API uses `BigDecimal`, `String`, `List`, `Map`, and `Future`. No custom exception hierarchies, no generic type parameters, no framework annotations.
- **Minimal code.** Eight source files, under 1400 lines total. The model builder, HTTP client, and result types are all in a single package.
- **Java 11+.** Not required to be on the latest Java version.

## Usage

```java
// Connect to the service
OptClientV1 client = OptClientV1.newInstance("https://your-service-host");

// Build a model
OptModel model = client.newModel();

OptVariable x = model.newRealVariable("x").lower(0);
OptVariable y = model.newRealVariable("y").lower(0);

model.newConstraint("budget").set(x, 1).set(y, 1).upper(10);

model.objective().set(x, 3).set(y, 5);

// Solve remotely (asynchronous)
OptResult result = model.maximise().get();

System.out.println("Optimal: " + result.isOptimal());
System.out.println("Value:   " + result.getValue());
System.out.println("x = " + x.doubleValue());
System.out.println("y = " + y.doubleValue());
```

## Classes

| Class | Role |
|---|---|
| `OptClientV1` | HTTP client — submits models and polls for results |
| `OptModel` | Model builder — variables, constraints, objective, serialisation |
| `OptVariable` | Decision variable (real, integer, or binary) |
| `OptConstraint` | Linear/quadratic constraint with bounds |
| `OptObjective` | Objective function (linear/quadratic) |
| `OptExpression` | Base for constraints and objective — holds coefficients |
| `OptResult` | Immutable result — feasible/optimal status, objective value, solution vector |

## Two layers

The client is split into two distinct layers:

- **`OptClientV1`** is a thin HTTP client wrapper. It knows how to submit bytes to the server and parse the JSON response, but knows nothing about what a model looks like. You can use it standalone to submit MPS files or any other format the server supports.
- **`OptModel`** is a model builder. It provides the fluent API for variables, constraints, and objectives, handles serialisation, and manages the poll loop and result mapping. It delegates all HTTP communication to an `OptClientV1` instance passed to its constructor.

This separation means you can use the HTTP client directly without the model builder, or replace the model builder without touching the HTTP layer.

The `putOnQueue` and `pollResult` method signatures match ojAlgo's `Optimisation.ModelSubmitter` and `Optimisation.ResultPoller` functional interfaces, so they can be passed directly as method references.

## How it works

1. You build a model using `OptModel` and its fluent API.
2. Calling `maximise()` or `minimise()` serialises the model and POSTs it to the server.
3. The client polls the server until the solver finishes.
4. The result is parsed, solution values are written back to the variables, and the `Future<OptResult>` completes.

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
for the next model in the queue. `cancel(false)` abandons only the local side —
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

`abortAll()` does the same to everything the server currently holds, and reports
how many solves it stopped, split into `QUEUED` and `ONGOING`. It affects every
client of that server, not just the one calling it.

The server solves a fixed number of models at once and queues the rest, so a
model submitted while the server is at capacity is accepted and given a key but
produces no result until a slot frees up. Only a full queue is refused outright.

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

## Building

```sh
mvn package
```

## License

This software is released into the public domain — see [UNLICENSE](UNLICENSE).
