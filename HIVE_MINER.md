# Hive Miner 26.2

This branch is based on IzumiiKonata Baritone commit `e24f5781` and adds a safety-first two-client mining controller.

## Guarantees

- Travel is non-destructive: `allowBreak`, `allowPlace`, parkour, and parkour placement are disabled and reasserted once per second.
- Mining is restricted to an immutable per-cell snapshot. The client controller blocks every break outside that coordinate and blocks every placement packet during a managed task.
- Baritone Builder uses the same coordinate allowlist as the packet gate, so it does not plan forbidden blocks and then stall against the gate.
- Doors, fence gates, and trapdoors remain usable while placement is blocked.
- Chests, barrels, machines, torches, ladders, beds, shulker boxes, signs, hanging signs, and discovered block entities are excluded from snapshots.
- Two clients split work on the longest horizontal axis and subdivide their slices into non-overlapping 12x12 cells.
- No movement and no block progress for 120 seconds requeues the cell. Three failed passes mark only that cell blocked and continue the job.
- In-game death disarms breaking, respawns, and resumes through non-destructive travel.
- A crashed client is relaunched with bounded 15/30/60-second backoff and receives only its persisted slice.

## Build

Java 25 is required for Minecraft 26.2.

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew test :fabric:remapJar --no-daemon
```

The Fabric artifact is written to:

```text
fabric/build/libs/baritone-fabric-26.2-SNAPSHOT.jar
```

The build is self-contained. Managed-client sources are in `src/hive/java` and tests are in `src/hive/test`.

## Controller

`hive-controller` contains the Discord/localhost controller used on `elysia`. It preserves the existing AFK manager and manages mining clients separately.

Important environment values:

```dotenv
TASK_CONTROL_HOST=127.0.0.1
TASK_CONTROL_PORT=47391
TASK_CONTROL_TOKEN=replace-with-a-random-token
TASK_AUTO_RESUME=true
```

Each Minecraft process must receive matching values plus its bot id:

```text
TASK_BOT_ID=kira
TASK_CONTROL_TOKEN=...
```

Active assignments are persisted as `data/native-task-<bot>-active.json`; completed assignments are marked inactive rather than deleted.

## Live Diagnostics

The controller logs stage, position, completed cells, remaining snapshot blocks, and blockers whenever they change. Minecraft logs use the `[HiveMiner]` prefix for joins, stage transitions, cell snapshots, retries, death handling, and completion.

The narrator `flite` warning under Xvfb is non-fatal and unrelated to mining.
