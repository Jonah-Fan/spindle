# Contributing to Spindle

Thanks for your interest in improving Spindle. This guide covers the build, the test expectations,
and the conventions every change should follow.

## Prerequisites

- **Java 17 or later** (the project compiles with `--release 17`).
- **Maven 3.9 or later**.

## Build and test

From the repository root:

```bash
mvn -B -ntp clean verify
```

`verify` runs the unit tests and the integration smoke test. Please make sure the whole reactor is
green before opening a pull request — there is no separate "tests-only" gate.

If you changed POM files and want to exercise the release toolchain locally (enforcer, source/javadoc
jars) **without** uploading anything:

```bash
mvn -B -ntp -P release clean package
```

## Before you write code

- **Open an issue first** for anything beyond a small fix, so the approach can be agreed before you
  invest in a change. Small typos and obvious bugs can go straight to a pull request.
- Keep pull requests **focused**: one concern per PR makes review faster and history cleaner.
- Target the default branch (`master`).

## Instrumentation conventions

Spindle's cardinal rule is that it must **never harm the host application**:

- Any new or modified advice must **catch and record its own errors** rather than throwing into the
  instrumented code. If an exception can escape Spindle into the host, that is a bug.
- Keep the hot path (advice that runs on every matched method call) allocation-light and
  non-blocking. The collector is designed to drop spans under pressure rather than stall a business
  thread — preserve that property.

## Testing

- Add unit tests for new pure-logic behavior (config parsing, matchers, propagation). The existing
  `AgentConfigTest`, `W3CPropagatorTest`, and `ConfigAndMatcherTest` show the style.
- Integration tests that attach the agent to a real JVM are valuable but can be timing-sensitive;
  prefer the in-JVM pipeline style used by `TracePipelineSmokeTest` (timeout-bounded polling, an
  isolated temp SQLite db) over spawning processes, so CI stays stable.

## Documentation and style

- Public and package-private classes carry Javadoc throughout the agent — mirror that density for
  anything you add. The inline Javadoc on `net.thewesthill.agent.TraceAgent` is the authoritative
  reference for lifecycle and internals.
- Match the surrounding code's formatting and naming. The repo uses 2-space indentation in the
  POMs and the existing Java style elsewhere.
- If you add a user-facing knob or behavior, update the README configuration reference and add a
  `CHANGELOG.md` entry under the unreleased section.

## Licensing

By contributing, you agree that your changes will be licensed under the project's
[MIT License](LICENSE). Third-party attributions are recorded in [NOTICE.md](NOTICE.md); if you add
a shaded dependency, list it there.
