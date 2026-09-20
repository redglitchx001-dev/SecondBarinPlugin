# Changelog

## v5.0.0
**The "one hundred million times better" update.**

### Added
- 6 NPC entity types (Villager, Zombie, Skeleton, Witch, Pillager, Armor Stand),
  villager professions and baby mode — all persisted per NPC.
- Persistent conversation memory (`memories.yml`) with autosave every 5 minutes.
- Per-NPC settings: chat on/off, name-only, look-at-players, name tag, glow,
  hearing radius, entity type, profession, baby — each can inherit the global default
  or override it (`/sb set <name> <setting> <on|off|default>`).
- 7 global toggles: `chat`, `nameonly`, `look`, `nametags`, `glow`, `typing`, `debug`.
- New commands: `gui`, `rename`, `removeall`, `near`, `tp`, `move`, `prompt`,
  `setradius`, `settype`, `setprofession`, `set`, `focus`, `unfocus`, `test`,
  `seturl`, `setmodel`, `status`, `stats`, `save`, `version`, paginated `help`.
- Focus mode: right-click an NPC (or `/sb focus`) to talk without saying its name.
- "NPC is thinking..." action bar while waiting for the AI.
- Per-player chat cooldown with bypass permission.
- `/sb stats` (replies, errors, avg latency, uptime) and `/sb status`
  (endpoint/model/key/limits) plus a one-click connection tester in the GUI.
- 9 inventory menus: main, paginated NPC list, NPC editor, global settings,
  entity picker, profession picker, statistics, connection status, confirm dialogs.
- Full tab completion for every subcommand, NPC name, setting, type and profession.
- Debug logging mode (`/sb toggle debug`).
- GitHub Actions CI that builds and uploads the jar on every push.

### Changed
- LLM calls now run on a dedicated thread pool; histories are thread-safe snapshots.
- Replies always reach the player who asked, even outside broadcast radius.
- Configurable `max-tokens`, `temperature`, broadcast radius multiplier and
  max NPCs answering a single message.
- Orphaned NPC entities are cleaned up automatically on startup.
- Removed the unused Citizens2 dependency (true zero-dependency build).

### Fixed
- Race conditions from mutating chat history on async threads.
- Bukkit API access from async chat threads (chat routing now runs synchronously).
- Duplicate NPC names, dangling entities after crashes, GUI item theft.

## v4.0.0 (previous, shipped as zip)
- Custom dependency-free engine, basic GUI, dynamic toggles.
