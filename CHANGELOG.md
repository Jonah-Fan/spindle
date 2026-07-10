# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-07-10

### Added
- Self-contained bytecode-level distributed tracing agent built on ByteBuddy. Attach one shaded
  jar with `-javaagent` and Spindle records call chains into an embedded SQLite database, then
  serves them through a built-in HTTP viewer. No external collector, backend, or dashboard required.
- `spindle-api`: a zero-dependency `@Trace` annotation that application code depends on to opt
  methods into tracing.
- `spindle-agent`: the JVM agent. W3C Trace Context propagation across thread boundaries, a
  single-writer SQLite reporter, and an embedded HTTP viewer.
- Built-in HTTP trace viewer with routes `/`, `/trace/<id>`, `/api/traces`, `/api/trace/<id>`,
  `/healthz`.
- Bundled interceptor rules for `@Trace`, Servlet, Spring MVC, JDBC, ORM (iBatis / Hibernate),
  and thread-pool spans (`spindle-agent/src/main/resources/defaults-interceptors.yml`).
- `spindle-demo`: a Spring Boot application instrumented by the agent, exercising MVC, JDBC,
  thread-pool, and `@Trace` spans end to end.
- Baseline automated test suite: unit tests for `AgentConfig`, `W3CPropagator`, and the
  config/matcher compilation pipeline, plus an integration smoke test covering the
  reporter → SQLite → viewer → HTTP chain.
- Distributed via GitHub Releases (`spindle-agent.jar`, `spindle-agent-ctx.jar`, `spindle-1.0.0.zip`).

### Fixed
- `TraceAgent.shutdown()` now logs the failing resource's class name instead of
  `getClass().getSigners()` (which returned `Object[]` / `null`).
- The shaded agent jar now bundles a relocated `slf4j-nop` provider so that the `slf4j-api` pulled
  in by SQLite JDBC finds a binding at runtime, eliminating the "No SLF4J providers were found"
  startup warning.
- Third-party `NOTICE` files are now merged into the shaded jar via
  `ApacheNoticeResourceTransformer`, restoring attribution that the shade filter had stripped.
