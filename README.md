# 🧠 SecondBrain v5.0 — AI NPCs for Paper 1.21.x

> Original Fabric mod by **sailex428**: https://github.com/sailex428/SecondBrain
> Ported & massively upgraded for **Paper 1.21.x** with any **OpenAI-compatible** API.

SecondBrain spawns AI-powered NPCs into your Minecraft world. Walk up to one, say its
name in chat, and it answers using the LLM of your choice — with **zero dependencies**:
no Citizens2, no ProtocolLib, no NMS. Just the native Paper API.

---

## ✨ What's new in v5.0

| Area | Upgrades |
|------|----------|
| 🧩 NPCs | 6 entity types (Villager, Zombie, Skeleton, Witch, Pillager, Armor Stand), villager professions, baby mode, glowing, name-tag toggle, per-NPC chat radius |
| 🧠 Memory | Conversations are **persisted to disk** (`memories.yml`) and survive restarts; per player↔NPC history with configurable length |
| ⚙️ Settings | 7 global toggles + 6 per-NPC toggles, all switchable **in-game** via commands or the GUI; per-NPC overrides can be reset to global with one click |
| 🖥️ GUI | 9 menus: main menu, paginated NPC list, per-NPC editor, global settings, entity picker, profession picker, stats, connection status, confirm dialogs |
| 💬 Chat | Cooldown system, "NPC is thinking..." action bar, focus mode (right-click an NPC to talk without saying its name), multiple NPCs can hear one message |
| 📊 Ops | `/sb stats` (replies, errors, avg latency, uptime), `/sb status`, connection tester, debug logging, full tab-completion for everything |
| 🛡️ Stability | Thread-safe history handling, async-safe LLM calls, orphan-entity cleanup on startup, busy-guards so one NPC never answers twice |

---

## ✅ Installation

1. Build the jar (or grab the latest artifact from the **Actions** tab / Releases)
2. Drop `SecondBrain-5.0.0.jar` into your server's `plugins/` folder
3. Start the server — `plugins/SecondBrain/config.yml` is generated
4. Set your API key in-game with `/sb setkey <key>` (or edit `config.yml`)
5. Create your first NPC: `/sb create Steve`

---

## 📋 Commands

**Aliases:** `/secondbrain`, `/sb`, `/brain`, `/ai`

### Basics
| Command | Description | Permission |
|---------|-------------|-----------|
| `/sb` | Open the main GUI | `secondbrain.use` |
| `/sb help [page]` | Paginated help | `secondbrain.use` |
| `/sb list [page]` | List all NPCs with status | `secondbrain.use` |
| `/sb info <name>` | Full NPC detail view | `secondbrain.use` |
| `/sb near [radius]` | NPCs around you with distance & direction | `secondbrain.use` |
| `/sb prompt <name>` | Show an NPC's system prompt | `secondbrain.use` |
| `/sb focus <name>` | Talk to an NPC without saying its name | `secondbrain.use` |
| `/sb unfocus` | Stop focusing | `secondbrain.use` |
| `/sb status` | Endpoint, model, masked key, limits | `secondbrain.use` |
| `/sb stats` | Replies, errors, latency, uptime | `secondbrain.use` |
| `/sb version` | Plugin version | `secondbrain.use` |

### NPC management (admin)
| Command | Description |
|---------|-------------|
| `/sb create <name>` | Create an NPC at your location |
| `/sb remove <name>` | Delete an NPC |
| `/sb removeall` | Delete every NPC (GUI confirm / console needs `confirm`) |
| `/sb rename <old> <new>` | Rename an NPC |
| `/sb move <name>` | Move an NPC to your location |
| `/sb tp <name>` | Teleport yourself to an NPC |
| `/sb setprompt <name> <text>` | Change personality/prompt |
| `/sb setradius <name> <blocks>` | Per-NPC hearing radius (2–200) |
| `/sb settype <name> <type>` | VILLAGER, ZOMBIE, SKELETON, WITCH, PILLAGER, ARMOR_STAND |
| `/sb setprofession <name> <prof>` | Villager profession (FARMER, LIBRARIAN, ...) |
| `/sb set <name> <setting> <on\|off\|default>` | Per-NPC toggle: `chat`, `nameonly`, `look`, `nametag`, `glow`, `baby` |
| `/sb clearmemory <name\|all>` | Wipe conversation history |
| `/sb test <name> <message>` | Test an NPC's reply without walking to it |

### Server settings (admin)
| Command | Description |
|---------|-------------|
| `/sb toggle <setting> [on\|off]` | Global toggles: `chat`, `nameonly`, `look`, `nametags`, `glow`, `typing`, `debug` |
| `/sb setkey <key>` | Set the API key |
| `/sb seturl <url>` | Set the AI endpoint |
| `/sb setmodel <model>` | Set the model |
| `/sb save` | Force-save NPCs + memories |
| `/sb reload` | Reload config.yml |

---

## 🖥️ The GUI

Run `/sb`:

- **NPCs** → paginated list. Left-click a card = editor, Shift-click = teleport, **Q** = delete (with confirm).
- **Create NPC** → type the name in chat; it spawns where you stand.
- **Global Settings** → every toggle as a clickable item, plus API key / URL / model editors.
- **Statistics** → live counters; **Connection** → endpoint info + one-click connection test.

The **NPC editor** puts every per-NPC setting in one chest:
toggles (left-click = toggle, right-click = reset to global default), radius +/− buttons,
prompt & rename via chat input, entity-type & profession pickers, teleport/move,
memory clear and delete (both behind confirm screens).

💡 **Tip:** right-click any NPC in the world to *focus* it — your chat goes straight to
that NPC for the next 60 seconds, no name needed. Sneak + right-click opens its editor.

---

## ⚙️ config.yml (excerpt)

```yaml
llm:
  url: "https://ai.xnetwork.ro/v1"   # any OpenAI-compatible endpoint
  model: "gpt-oss-20b"
  api-key: "PUT_YOUR_API_KEY_HERE"
  timeout: 30
  max-history: 20        # remembered messages per conversation
  max-tokens: 250
  temperature: 0.8

npc:
  chat-radius: 15.0
  respond-only-to-name: true
  chat-enabled: true
  look-at-players: true
  glow: false
  default-entity-type: VILLAGER
  max-npcs-per-message: 2

chat:
  cooldown: 2.0          # seconds between AI messages per player
  typing-indicator: true
  focus-duration: 60
```

Every message sent to players lives under `messages:` and is fully editable.

---

## 🔑 Permissions

| Permission | Default | Grants |
|-----------|---------|--------|
| `secondbrain.use` | op | GUI, list/info/near/status/stats, talking & focus |
| `secondbrain.admin` | op | Everything: create/edit/delete NPCs, toggles, keys, reload |
| `secondbrain.bypass.cooldown` | op | Ignore the chat cooldown |
| `secondbrain.*` | op | All of the above |

---

## 🔨 Building from source

Requirements: **Java 21 JDK**, **Maven 3.8+**

```bash
mvn package
# -> target/SecondBrain-5.0.0.jar
```

A ready-made GitHub Actions workflow lives in `ci/build.yml.example`. To enable it,
copy it to `.github/workflows/build.yml` and push — every commit will then be built
automatically with the jar attached as a build artifact (see the **Actions** tab).

---

## 📁 Data files

| File | Purpose |
|------|---------|
| `plugins/SecondBrain/config.yml` | All settings & messages |
| `plugins/SecondBrain/npcs.yml` | NPC definitions + per-NPC settings |
| `plugins/SecondBrain/memories.yml` | Saved conversations (auto-saved every 5 min + on shutdown) |

---

## 🧭 Project rules (read before contributing)

The plugin's original brief — the "**ZERO DEPENDENCIES**" contract — lives in [`PROMPT.md`](PROMPT.md)
(Romanian original + English translation, recovered from the v4 source zip and now versioned).
[`AGENTS.md`](AGENTS.md) summarises it as hard rules for AI assistants and contributors:
no Citizens / ProtocolLib / NMS, native Paper entities only, native `HttpClient` for AI calls,
Java 21 + Paper 1.21.x, toggles always working. `PROMPT.md` also tracks the current compliance status.

---

## 📜 License

Based on SecondBrain by **sailex428**, licensed under **LGPL-3.0**. See `LICENSE.md`.
This plugin phones home to the AI endpoint *you* configure — no other telemetry.
