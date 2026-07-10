<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/logo-dark.svg">
  <img alt="Spindle logo" src="assets/logo-light.svg" width="120" height="120">
</picture>

# Spindle

[![Project Status: Active – The project has reached a stable, usable state and is being actively developed.](https://www.repostatus.org/badges/latest/active.svg)](https://www.repostatus.org/#active)
[![Java](https://img.shields.io/badge/Java-17.0.2-orange?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Build](https://img.shields.io/badge/Maven-3.9.16-c71a36?logo=apache-maven&logoColor=white)](https://maven.apache.org/)
[![GitHub release](https://img.shields.io/github/v/release/Jonah-Fan/spindle?logo=github)](https://github.com/Jonah-Fan/spindle/releases)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

Spindle is a self-contained Java bytecode-level distributed tracing agent. Attach one shaded jar with `-javaagent` and Spindle records call chains — method, JDBC, thread-pool, and Spring MVC spans — into an embedded SQLite database, then serves them back through a built-in HTTP viewer. No external collector, backend, or dashboard required: one jar, open a browser, see your traces.

Spindle is free and open source software.

> [!IMPORTANT]
> The goal of this README is to provide a basic, structured introduction to Spindle for new users. The inline Javadoc on each class (start with `net.thewesthill.agent.TraceAgent`) is the authoritative reference for lifecycle, configuration, and internals.

# Table of contents
- [How it works](#how-it-works)
  - [Modules](#modules)
  - [Configurations](#configurations)
  - [Runtime](#runtime)
- [Getting started](#getting-started)
  - [Download](#download)
  - [Attaching the agent](#attaching-the-agent)
  - [Viewing traces](#viewing-traces)
  - [Annotating code with @Trace](#annotating-code-with-trace)
- [Building from source](#building-from-source)
  - [Installing dependencies](#installing-dependencies)
  - [Cloning the repository](#cloning-the-repository)
  - [Compiling](#compiling)
  - [Location of the artifacts](#location-of-the-artifacts)
  - [Running and testing the demo](#running-and-testing-the-demo)
- [Configuration reference](#configuration-reference)
- [Asking questions and reporting issues](#asking-questions-and-reporting-issues)
- [Contributing code](#contributing-code)
- [Additional help and resources](#additional-help-and-resources)
- [License](#license)

# How it works
Spindle is a JVM instrumentation agent built on [ByteBuddy](https://bytebuddy.net/). At startup it installs bytecode advice onto configured methods; every call to a woven method opens a span, nests it under the current parent (tracked in a `ThreadLocal` stack), and on return records the span to an in-memory queue. A background reporter thread drains that queue and persists spans to SQLite. Optionally, an embedded HTTP server renders the recorded traces as HTML and JSON.

> [!IMPORTANT]
> Spindle's instrumentation never throws into business code. Every advice entry/exit point is wrapped so that any failure disables tracing for that path rather than failing your application — instrumentation must never break the host.

## Modules
Spindle is comprised of three Maven modules, each extending core functionality:

- **`spindle-api`** — the zero-dependency tracing API. Application code depends on this alone for the `@Trace` annotation and related markers. It carries no ByteBuddy or SQLite dependencies, so it stays light on your application classpath.
- **`spindle-agent`** — the JVM agent. Woven with ByteBuddy, it owns span collection (ThreadLocal stack), W3C Trace Context propagation, the SQLite reporter, and the embedded HTTP viewer. Builds to a shaded fat jar plus a tiny `spindle-agent-ctx.jar` pushed to the bootstrap classloader for cross-thread context propagation.
- **`spindle-demo`** — a Spring Boot application instrumented by the agent, exercising MVC, JDBC, thread-pool, and `@Trace` spans so you can see Spindle in action end to end.

> [!TIP]
> You can see exactly which interceptor rules the agent installed by watching the startup log line `[spindle] INFO installed N interceptor rule(s)`.

## Configurations
Spindle is configured two ways: through the agent argument string on the command line (`-javaagent:spindle-agent.jar=key=value,...`), and through an interceptor-rules YAML that declares which methods to trace. See [Configuration reference](#configuration-reference) for the full list of knobs. The agent ships sensible built-in defaults, so a no-argument attach traces Spring MVC, JDBC, and thread-pool work out of the box.

> [!NOTE]
> The set of methods Spindle instruments is determined by the interceptor rules that resolve at startup. Without a custom YAML the agent falls back to the bundled `defaults-interceptors.yml`.

## Runtime
Rather than recording traces in the calling thread, Spindle separates the hot path from storage. It runs:

- **Intercepted business threads** — open and close spans on the ThreadLocal stack, then hand finished span events to a bounded in-memory queue. This is the only work on your request threads; all I/O happens off-thread.
- **A `spindle-reporter` daemon thread** — the single consumer of the queue, the only thread that touches SQLite. It drains spans in batches, writes them in one transaction, and sleeps when idle.
- **A `spindle-viewer` daemon thread pool** (only when the viewer is enabled) — serves the recorded traces over HTTP.
- **A `spindle-shutdown` hook thread** — on JVM exit, flushes remaining spans and closes the connection and viewer in reverse order.

> [!TIP]
> Spindle propagates trace context across thread boundaries (e.g. `ThreadPoolExecutor`) by snapshotting the current `TraceContext` and replaying it on the worker thread. This is why `TraceContextHolder` ships in a separate bootstrap jar — the JDK's own `ThreadPoolExecutor` classloader can only see bootstrap types.

# Getting started
Follow these steps to attach Spindle to a running JVM and view traces. You can also [build Spindle locally from source](#building-from-source).

## Download
Grab the latest release from [GitHub Releases](https://github.com/Jonah-Fan/spindle/releases). The dist zip bundles the two jars you need, or download them directly:

- `spindle-agent.jar` — the shaded agent jar; pass it to `-javaagent`.
- `spindle-agent-ctx.jar` — the bootstrap context jar; keep it next to the agent jar (the agent manifest references it via `Boot-Class-Path`).

Spindle is **not** published to Maven Central; the GitHub release is the source of artifacts.

## Attaching the agent
Pass the shaded agent jar on the JVM command line. With the demo jar built, from the repository root:

```bash
java -javaagent:spindle-agent/target/spindle-agent.jar -jar spindle-demo/target/spindle-demo.jar
```

> [!IMPORTANT]
> The agent jar is self-contained: ByteBuddy, Gson, SnakeYAML, and the SQLite driver are shaded under `net.thewesthill.agent.shaded.*`, so attaching Spindle never conflicts with versions already on your application classpath.

You should see the startup banner:

```
[spindle] INFO Spindle started. db=trace.db viewer=http://localhost:8787/
```

## Viewing traces
Open the viewer URL printed in the banner in a browser. The viewer serves:

- `/` — an HTML list of recent traces (one row per trace, with a link to each detail page and a JSON endpoint).
- `/trace/<traceId>` — an HTML detail page rendering the span tree for one trace.
- `/api/traces` — the recent traces as a JSON array.
- `/api/trace/<traceId>` — a single trace and its spans as JSON.
- `/healthz` — a liveness probe returning `ok`.

> [!TIP]
> Disable the viewer for production with `viewer=off`: `-javaagent:spindle-agent.jar=viewer=off`. The agent still records spans to SQLite; you just browse them with any SQLite client.

## Annotating code with @Trace
For code that isn't already traced by a built-in rule (Spring MVC, JDBC, thread pools), declare a span with the `@Trace` annotation from `spindle-api`:

```java
import net.thewesthill.trace.Trace;

public class UserService {
  @Trace("UserService.findById")
  public User findById(Long id) { ... }

  @Trace                          // name defaults to the method signature
  public void validateId(Long id) { ... }
}
```

The annotation is a compile-time-only dependency — your application imports `spindle-api` and nothing else from Spindle.

# Building from source
The following steps build Spindle from the source code in this repository.

## Installing dependencies
Spindle builds with **Java 17+**. The repository ships a Maven Wrapper (`mvnw`), so you do not need
a system Maven — just a JDK 17 or later. Install it, then verify:

```bash
java -version
./mvnw -version
```

> [!TIP]
> The wrapper downloads the pinned Maven 3.9.16 on first use, and Maven then fetches all build
> dependencies (ByteBuddy, SQLite JDBC, Gson, SnakeYAML, Spring Boot) from Maven Central, so no
> manual dependency installation is needed.

## Cloning the repository
Clone this repository into your development directory.

```bash
git clone https://github.com/Jonah-Fan/spindle.git spindle
cd spindle
```

## Compiling
From the repository root, build every module:

```bash
./mvnw clean verify
```

This compiles `spindle-api`, `spindle-agent`, and `spindle-demo`, runs the tests, then shades the agent and repackages the demo. CI builds on JDK 17 and 21 to cover both the supported minimum and current LTS.

## Location of the artifacts
After a successful build, the artifacts land at:

```
spindle-agent/target/spindle-agent.jar        # shaded agent jar (attach with -javaagent)
spindle-agent/target/spindle-ctx.jar      # bootstrap context jar (referenced by Boot-Class-Path)
spindle-demo/target/spindle-demo.jar         # runnable Spring Boot demo
```

> [!IMPORTANT]
> Do not delete `spindle-agent-ctx.jar` — the agent manifest points at it via `Boot-Class-Path` so that a single `TraceContextHolder` class is shared across the bootstrap and application classloaders. Without it, cross-thread context propagation silently breaks.

## Running and testing the demo
Launch the demo with the agent attached:

```bash
java -javaagent:spindle-agent/target/spindle-agent.jar -jar spindle-demo/target/spindle-demo.jar
```

Once the Spring Boot banner reports `Started DemoApplication`, exercise the endpoints to generate traces:

```bash
curl localhost:8080/users/1
curl localhost:8080/users/1/async   # crosses a thread boundary — exercises context propagation
curl localhost:8080/users/1/error   # records an error span
```

Then open `http://localhost:8787/` in a browser. The output of the viewer's JSON endpoint starts with:

```json
[
  {
    "traceId": "...",
    "rootName": "public ... UserController.async(...)",
    "rootKind": "MVC",
    ...
  }
]
```

# Configuration reference
Spindle reads configuration from the `-javaagent:` argument string as `key=value` pairs separated by commas. All keys are optional; missing or malformed values fall back to defaults.

| Key        | Default            | Description                                                             |
|------------|--------------------|-------------------------------------------------------------------------|
| `db`       | `trace.db`         | Path to the SQLite database file.                                       |
| `port`     | `8787`             | TCP port for the embedded HTTP viewer.                                  |
| `viewer`   | `on`               | Set to `off` to disable the viewer (spans still recorded).              |
| `config`   | _(none)_           | Explicit path to an interceptor-rules YAML; overrides the lookup order. |
| `logDir`   | `./logs`           | Directory for the rotating agent log files.                             |
| `logLevel` | `info`             | `debug`, `info`, `warn`, or `error`.                                    |
| `logSize`  | `52428800` (50 MB) | Rotating log file size in bytes.                                        |
| `logCount` | `100`              | Number of rotated log files to retain.                                  |

The interceptor-rules YAML declares which methods to trace. In development the loader looks, in order, for an explicit `config=` path, then `./spindle-interceptors.yml` on disk, then `/spindle-interceptors.yml` on the classpath, finally the bundled `/defaults-interceptors.yml`. Production skips the on-disk lookups and uses only the explicit path or the bundled defaults.

> [!TIP]
> Inspect `spindle-agent/src/main/resources/defaults-interceptors.yml` for the full rule syntax — type matchers (`named`, `nameEndsWith`, `anyOfSuperType`, `declaresMethod`), method matchers (`named`, `anyOfNames`, `isAnnotatedWith`, `takesArguments`), and the advice class each rule binds.

# Asking questions and reporting issues
Open a GitHub issue for bug reports and feature requests. Please include the Spindle version, the `-javaagent` arguments you used, the relevant startup log lines, and a minimal reproducer. For suspected security vulnerabilities, follow the private reporting process in [SECURITY.md](SECURITY.md) instead of opening a public issue.

# Contributing code
Contributions are welcome. Please open an issue first to discuss the change you intend to make, then submit a pull request against the default branch. See [CONTRIBUTING.md](CONTRIBUTING.md) for build steps, test expectations, and the instrumentation conventions. In short: keep instrumentation failures non-propagating — any new advice must catch and record its own errors rather than throwing into the host application.

# Additional help and resources
- The Javadoc on `net.thewesthill.agent.TraceAgent` is the entry point for the agent's lifecycle and internals.
- `spindle-agent/src/main/resources/defaults-interceptors.yml` is a working example of the interceptor-rule DSL.

# License
Spindle is distributed under the terms of the [MIT License](LICENSE).
