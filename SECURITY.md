# Security Policy

## Supported versions

Spindle is pre-1.x. Security fixes are applied only to the latest release line.

| Version | Supported |
|---------|-----------|
| 1.0.x   | ✅         |
| < 1.0   | ❌         |

## Reporting a vulnerability

Please **do not** open a public GitHub issue for a suspected security vulnerability.

Instead, email **jonah-fan@outlook.com** with `[spindle security]` in the subject line and include:

- a description of the issue and its impact,
- the Spindle version, the `-javaagent` arguments you used, and the relevant startup log lines,
- a minimal reproducer if possible.

You should receive an acknowledgement within **72 hours**. If a vulnerability is confirmed, a fix
and a release will be coordinated, and the reporter will be credited unless they prefer otherwise.

## Threat model and operational guidance

Spindle is a JVM agent that rewrites host bytecode and runs an embedded HTTP server. Keep the
following in mind when deploying it:

- **In-process execution.** The agent runs inside the host JVM and therefore has the host
  process's privileges. Any way for instrumentation advice to throw into host application code is
  treated as a correctness *and* security bug — by design, advice catches and records its own
  errors and never propagates them. Please report any case where an exception leaks out of Spindle
  into the instrumented application.
- **HTTP viewer binds to localhost.** The viewer listens on `localhost:8787` by default and serves
  recorded trace data, which may include method names, arguments, and stack traces. **Do not
  expose the viewer to untrusted networks.** In production, disable it entirely with
  `-javaagent:spindle-agent.jar=viewer=off`; spans are still recorded to the SQLite database and
  can be inspected offline.
- **Embedded database.** The trace database is a local SQLite file written by the agent. Place it
  on a path with appropriate filesystem permissions for the sensitivity of the trace data it will
  hold.
