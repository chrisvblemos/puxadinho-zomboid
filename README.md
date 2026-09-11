# Puxadinho

A server-side Java agent for **Project Zomboid Build 42** that bundles a set of
independent fixes and quality-of-life features for dedicated servers — bug
fixes, server management and persistence. It is applied at server startup with
`-javaagent` and patches a handful of methods with ASM. It requires no mod
framework and no client-side install.

Every fix is a self-contained `Patch` that can be enabled, disabled or removed
independently. See [AGENTS.md](AGENTS.md) for the technical details,
configuration and install instructions.

## Features

| # | Patch | What it does |
|---|-------|--------------|
| 1 | **Zombie duplication fix** | Stops the population manager from accumulating duplicate records for the same zombie, which respawned and snowballed into hundreds of identical clones. |
| 2 | **Ranch animal age fix** | Disables the old-world age gate that guaranteed ranch-spawned animals were created dead once the world passed ~190 in-game days. |
| 3 | **Player statistics** | Persists a time-series of every survivor's progress (kills, hours survived, position, health, infection, inventory, skills) and every death to SQLite, surviving character death. |
| 4 | **World respawn** | Refills a ranch's herd once it has been empty for 48 in-game hours, and replaces abandoned or destroyed vehicles after 7 in-game days. |
| 5 | **Safehouse item protection** | Stops the sandbox dropped-item cleanup from deleting world items dropped inside a safehouse. |

## Quick start

```bash
cd java
./gradlew clean build
```

Output: `java/build/libs/Puxadinho.jar`.

Add it to the server launch command (`-javaagent`):

```bat
- javaagent:"C:\path\to\Puxadinho.jar"
```

`Puxadinho.ini`, created next to the `Saves` folder on first run, controls every
feature. Set `DebugLogging = true` for verbose logging and a startup
build/jar fingerprint.

## License

See [LICENSE](LICENSE).