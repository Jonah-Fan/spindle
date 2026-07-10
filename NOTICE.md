# NOTICE

This file contains attribution and license notices for Spindle and the third-party software
redistributed within the `spindle-agent` shaded jar.

## Spindle

Spindle — a self-contained Java bytecode-level distributed tracing agent.
Copyright © 2026 Jonah Fan. Licensed under the [MIT License](LICENSE).

## Third-party software bundled in the shaded agent jar

The `spindle-agent` shaded jar relocates and repackages the following libraries. Their copyright
notices are merged into the jar's `META-INF/NOTICE` at build time via the Maven Shade Plugin's
`ApacheNoticeResourceTransformer`. This file records the attributions here as well.

### ByteBuddy — Apache License 2.0
- <https://bytebuddy.net/>
- Copyright © 2014 – Present Rafael Winterhalter.
- Bundled for runtime bytecode rewriting and relocated to `net.thewesthill.agent.shaded.bytebuddy`.

### SQLite JDBC — Apache License 2.0
- <https://github.com/xerial/sqlite-jdbc>
- Copyright © the sqlite-jdbc authors (xerial.org).
- Includes the SQLite database engine, which is in the public domain
  (<https://sqlite.org/copyright.html>).
- Used as the embedded trace store.

### Gson — Apache License 2.0
- <https://github.com/google/gson>
- Copyright © 2008 Google Inc.
- Used to serialize the viewer's JSON responses; relocated to
  `net.thewesthill.agent.shaded.gson`.

### SnakeYAML — Apache License 2.0
- <https://bitbucket.org/snakeyaml/snakeyaml>
- Copyright © Andrey Somov and contributors.
- Used to parse the interceptor-rule YAML; relocated to
  `net.thewesthill.agent.shaded.snakeyaml`.

### SLF4J — MIT License
- <http://www.slf4j.org/>
- Copyright © 2004–present QOS.ch.
- `slf4j-api` (pulled in transitively by SQLite JDBC) and the `slf4j-nop` binding are bundled and
  relocated to `net.thewesthill.agent.shaded.slf4j` so the agent's logging stays self-contained and
  silent by default.

## License texts

The full texts of the Apache License 2.0 and the MIT License are available at
<https://www.apache.org/licenses/LICENSE-2.0> and <https://opensource.org/licenses/MIT> respectively.
