# NOTICE

This file contains attribution and license notices for Spindle and the third-party software
redistributed within the `spindle-agent` shaded jar.

## Spindle

Spindle — a self-contained Java bytecode-level distributed tracing agent.
Copyright © 2026 Jonah Fan. Licensed under the [MIT License](LICENSE).

## Third-party software bundled in the shaded agent jar

The `spindle-agent` shaded jar relocates and repackages the following libraries. At build time the
shaded jar retains each library's own `META-INF/LICENSE` / `LICENSE.txt` where one is shipped, and
the Maven Shade Plugin's `ApacheNoticeResourceTransformer` merges any `META-INF/NOTICE` files into
the jar's `META-INF/NOTICE`. In practice only ByteBuddy ships a `NOTICE`, so the merged `META-INF/NOTICE`
contains just its entry; libraries that do not ship a `NOTICE`/`LICENSE` inside their jar (Gson,
SnakeYAML) are attributed here by their known license and upstream link. This file records all of
those attributions in one place.

### ByteBuddy — Apache License 2.0
- <https://bytebuddy.net/>
- Copyright © 2014 – Present Rafael Winterhalter.
- Bundled for runtime bytecode rewriting and relocated to `net.thewesthill.agent.shaded.bytebuddy`.
- Its `META-INF/NOTICE` and `META-INF/LICENSE` are retained in the shaded jar. ByteBuddy bundles
  ASM for the actual bytecode rewriting; ASM carries a separate BSD 3-Clause license, retained at
  `META-INF/licenses/ASM` (see the ASM entry below).

### ASM — BSD 3-Clause License
- <https://asm.ow2.io/>
- Copyright © 2000–2011 INRIA, France Telecom.
- The ASM bytecode manipulation framework is bundled *inside* ByteBuddy (it is not a separate
  Maven dependency of Spindle). ByteBuddy's `META-INF/LICENSE` records the bundling, and ASM's own
  BSD 3-Clause license text is retained in the shaded jar at `META-INF/licenses/ASM`.

### SQLite JDBC — Apache License 2.0
- <https://github.com/xerial/sqlite-jdbc>
- Copyright © the sqlite-jdbc authors (xerial.org).
- Includes the SQLite database engine, which is in the public domain
  (<https://sqlite.org/copyright.html>).
- Used as the embedded trace store. Its license files (`META-INF/maven/org.xerial/sqlite-jdbc/LICENSE`
  and `LICENSE.zentus`) are retained in the shaded jar.

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
- Its `META-INF/LICENSE.txt` (MIT) is retained in the shaded jar.

## License texts

The full texts of the Apache License 2.0, the MIT License, and the BSD 3-Clause License are
available at <https://www.apache.org/licenses/LICENSE-2.0>,
<https://opensource.org/licenses/MIT>, and <https://opensource.org/licenses/BSD-3-Clause>
respectively. In the shaded jar the ByteBuddy/SQLite JDBC Apache 2.0 license, the SLF4J MIT
license, and the ASM BSD license are each retained as shipped (see the entries above).
