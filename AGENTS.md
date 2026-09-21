# AGENTS.md

Instructions for any AI assistant working in this repository.

## Read this first

**`PROMPT.md`** is the project's standing brief (original Romanian `cerinte.txt` + English translation).
It defines the architecture rules of SecondBrain. Read it before touching code, and do not violate it
unless the user explicitly relaxes a rule.

## Non-negotiable rules

1. **ZERO DEPENDENCIES.** `pom.xml` may only depend on `io.papermc.paper:paper-api` (`provided`).
   No Citizens2, no ProtocolLib, no NMS, no reflection, no shading of external jars.
   Allowed non-JDK imports: Paper API, Adventure (Kyori) and Gson — both ship with Paper.
2. **NPCs are native Bukkit entities**, not fake players: spawned via `World#spawnEntity`,
   `setInvulnerable(true)`, `setPersistent(true)`, `setAI(false)` + `setSilent(true)` for `LivingEntity`,
   a custom name tag, and the `secondbrain_npc` scoreboard tag for cleanup.
   Head-turning toward nearby players is handled by the scheduler task in `npc/NPCManager.java`.
3. **AI traffic uses the native `java.net.http.HttpClient`** from `llm/LLMClient.java`, on a daemon thread
   pool — never on the main server thread. Only OpenAI-compatible `/chat/completions` endpoints.
   Defaults: `https://ai.xnetwork.ro/v1`, model `gpt-oss-20b`.
4. **Java 21 and Paper 1.21.x only.** `plugin.yml` keeps `api-version: '1.21'`. Everything must still
   build with `mvn package` (→ `target/SecondBrain-<version>.jar`) and keep
   `.github/workflows/build.yml` green.
5. **Chat flow stays sane.** `listener/ChatListener.java` only captures chat, then hops to the main thread;
   distant/routed decisions live in `chat/ChatService.java`. History must stay thread-safe.
6. **Toggles must keep working:** `/sb toggle chat false` disables AI chat entirely,
   `/sb toggle nameonly true` makes NPCs answer only when named. Same for the GUI items.
7. **Persistence:** `npcs.yml` (NPC definitions + per-NPC overrides) and `memories.yml` (conversations).
   Never break loading of older files — missing values mean "inherit global default".

## If the user asks for "real player NPCs with skins"

Explain first: this needs network packets (NMS) or an external library (Citizens). Doing it via reflection
in 1.21 is unstable without `paperweight-userdev`. Only implement it if the user explicitly insists, and
then follow the 1.21 Mojang mappings with great care. Offering the existing workaround
(`/sb settype <name> ARMOR_STAND` + a glowing name tag) is usually the better answer.

## Repo hygiene

- `SecondBrain-FullSource-v4.zip` is a legacy shipped artifact kept for reference (it still contains the
  original `cerinte.txt`, now mirrored in `PROMPT.md`). Not part of the build.
- `ci/build.yml.example` is the copy-paste source for `.github/workflows/build.yml`.
- Do not commit `target/`, jars or `plugins/` runtime data.
