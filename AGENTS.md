# Repository Guidelines

## Project Structure & Module Organization
- Source: `src/main/clojure` (primary namespaces under `retroact` and `examples`).
- Tests: `src/test/clojure` (uses `clojure.test`).
- Resources: `src/main/resources`.
- Build tooling: Gradle with Clojurephant (`build.gradle`, `gradlew`).
- Generated/compiled artifacts may appear in `build/` and `bin/`.

## Build, Test, and Development Commands
- `./gradlew build` — Compile Clojure (AOT where configured), run tests, package jar.
- `./gradlew test` — Run unit tests (`clojure.test`).
- `./gradlew clojureRepl` — Start an nREPL with project classpath for interactive dev.
- Example (from REPL):
  - `(require '[retroact.core :refer :all] :reload-all)`
  - `(require '[examples.greeter :refer :all] :reload-all)`
  - `(init-app (greeter-app))`
- Publishing (local/Clojars): `./gradlew publish` (see credentials notes below).

## Coding Style & Naming Conventions
- Language: Clojure 1.10.x; prefer idiomatic, immutable data and pure fns.
- Indentation: 2 spaces; no tabs.
- Naming: kebab-case for vars/fns (`create-comp`), namespaces like `retroact.swing.create-fns`.
  - File names use underscores for dashes: `create_fns.clj` for `create-fns`.
- Avoid anonymous handlers inside render maps; prefer named fns for stable equality.

## Testing Guidelines
- Framework: `clojure.test`.
- Location: `src/test/clojure/...` matching namespaces (e.g., `retroact.test`).
- Run: `./gradlew test` or from REPL: `(require '[retroact.test] :reload)`.
- Aim for tests around diff/patch logic and UI mapping where feasible.

## Commit & Pull Request Guidelines
- Messages: Prefer Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`). Keep subject <= 72 chars.
- PRs: Include a clear description, linked issues, reproduction steps, and—when UI-related—screenshots/GIFs.
- Keep changes focused; update README or examples when behavior changes.

## Security & Publishing Tips
- Clojars publishing requires Gradle properties: `clojarsUsername`, `clojarsPassword` (in `~/.gradle/gradle.properties` or via `-P...`).
- Do not commit secrets. Use local properties files or environment variables.

## Agent-Specific Notes
- This AGENTS.md applies repo-wide. Prefer minimal, surgical patches; follow existing structure and style.
