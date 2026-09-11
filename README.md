# Puxadinho

A server-side Java agent that fixes the Build 42 Project Zomboid bug where the
zombie population manager accumulates duplicate records for the same zombie,
producing identical clones that respawn and snowball into hundreds of zombies.

The agent is loaded at server startup with `-javaagent` and patches a handful of
methods with ASM. It requires no mod framework and no client-side install.

See [AGENTS.md](AGENTS.md) for the build and install instructions.

## Quick start

```bash
cd java
./gradlew clean build
```

Then add to `ProjectZomboidServer.bat`:

```bat
- javaagent:"C:\path\to\Puxadinho.jar"
```

## License

See [LICENSE](LICENSE).
