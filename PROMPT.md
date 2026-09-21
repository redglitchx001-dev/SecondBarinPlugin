# PROMPT.md — The SecondBrain Project Prompt

> **Recovered from `SecondBrain-FullSource-v4.zip` → `SecondBrainPaper/cerinte.txt` and now versioned
> at the repo root, so it can no longer be lost inside a build artifact.**
> This is the standing brief for any AI assistant (or human) working on this plugin.
> Read it before changing code.
>
> Versiunea în română (originală, verbatim) mai jos, urmată de traducerea în engleză.

---

## 🇷🇴 Original (verbatim, Romanian)

```
[PROMPT FOR THE NEXT AI ASSISTANT]

Te rog să acționezi ca un expert în dezvoltare Java și API-ul PaperMC (Minecraft 1.21.x).
Utilizatorul lucrează la pluginul "SecondBrain", un sistem de NPC-uri controlate de Inteligența Artificială.

CONTEXTUL PROIECTULUI (Foarte important de știut înainte să modifici):
1. FĂRĂ DEPENDENȚE: Acest plugin a fost scris special cu regula "ZERO DEPENDENȚE". Nu folosește Citizens2, ProtocolLib sau alte librării. Folosește STRICT API-ul nativ Paper 1.21.
2. CUM SUNT FĂCUȚI NPC-II: Pentru că Minecraft nu permite crearea de "Fake Players" cu skin-uri fără NMS/pachete, acest plugin folosește entități native (tip `Villager`) care sunt setate ca invulnerabile, fără inteligență (setAI(false)), mute (setSilent(true)) și cărora li se pune un nume custom (hologramă). Există un task (în `NPCManager.java`) care îi face să-și întoarcă capul către cel mai apropiat jucător.
3. CONEXIUNEA AI: Pluginul folosește `HttpClient` nativ din Java 11+ (în clasa `LLMClient.java`) pentru a face apeluri POST către un API compatibil OpenAI. Endpoint-ul default este `https://ai.xnetwork.ro/v1` cu modelul `gpt-oss-20b`.
4. SISTEMUL DE CHAT: `ChatListener.java` captează ce scriu jucătorii, verifică distanța față de NPC-uri, și trimite istoricul la AI.
5. SETĂRI DINAMICE (Toggles): Are comenzi pentru a opri complet AI-ul (`/sb toggle chat false`) sau pentru a forța NPC-ul să răspundă DOAR dacă i se zice numele (`/sb toggle nameonly true`).

SARCINA TA CA AI:
- Când utilizatorul îți va cere să adaugi o funcție nouă, asigură-te că scrii cod compatibil cu Java 21 și Paper 1.21.
- Păstrează arhitectura "fără dependențe" cu excepția cazului în care utilizatorul se răzgândește și permite folosirea de NMS/Citizens explicit.
- Dacă utilizatorul îți cere să faci NPC-ul "ca un jucător real cu skin", explică-i că acest lucru necesită pachete de rețea (NMS), ceea ce e instabil prin Reflection în 1.21 fără paperweight-userdev sau librării externe. Dacă insistă, scrie codul cu mare atenție la Mappings-urile Mojang specifice 1.21.
```

---

## 🇬🇧 English translation

```
[PROMPT FOR THE NEXT AI ASSISTANT]

Please act as an expert in Java development and the PaperMC API (Minecraft 1.21.x).
The user is working on the plugin "SecondBrain", an AI-controlled NPC system.

PROJECT CONTEXT (Very important to know before you change anything):
1. NO DEPENDENCIES: This plugin was written specifically under the rule "ZERO DEPENDENCIES". It does not use
   Citizens2, ProtocolLib or any other library. It uses STRICTLY the native Paper 1.21 API.
2. HOW THE NPCs ARE MADE: Because Minecraft does not allow creating "Fake Players" with skins without
   NMS/packets, this plugin uses native entities (`Villager` type) that are set invulnerable, without AI
   (setAI(false)), silent (setSilent(true)) and are given a custom name (hologram). There is a task
   (in `NPCManager.java`) that makes them turn their head toward the nearest player.
3. THE AI CONNECTION: The plugin uses the native `HttpClient` from Java 11+ (in `LLMClient.java`) to make POST
   calls to an OpenAI-compatible API. The default endpoint is `https://ai.xnetwork.ro/v1` with the model `gpt-oss-20b`.
4. THE CHAT SYSTEM: `ChatListener.java` captures what players type, checks the distance to NPCs, and sends the
   history to the AI.
5. DYNAMIC SETTINGS (Toggles): It has commands to turn the AI off completely (`/sb toggle chat false`) or to force
   the NPC to answer ONLY when its name is said (`/sb toggle nameonly true`).

YOUR JOB AS THE AI:
- When the user asks you to add a new feature, make sure you write code compatible with Java 21 and Paper 1.21.
- Keep the "no dependencies" architecture, unless the user changes their mind and explicitly allows NMS/Citizens.
- If the user asks you to make the NPC "like a real player with a skin", explain that this requires network packets
  (NMS), which is unstable through Reflection in 1.21 without paperweight-userdev or external libraries. If they
  insist, write the code with great care for the 1.21-specific Mojang mappings.
```

---

## ✅ Compliance status (verified against the current `v5.0.0` source)

| Rule | Where it lives | Status |
|------|----------------|--------|
| Zero dependencies (no Citizens / ProtocolLib / NMS / Reflection) | `pom.xml` → only `paper-api` (provided); imports are Paper + Adventure + Gson (both bundled by Paper) + JDK | ✅ |
| NPCs = native entities, invulnerable, `setAI(false)`, `setSilent(true)`, custom name tag | `npc/NPCManager.java` → `spawnEntity(...)` | ✅ |
| Head-turning task toward the nearest player | `npc/NPCManager.java` → `startLookTask()` | ✅ |
| AI via native `java.net.http.HttpClient`, OpenAI-compatible `/chat/completions` | `llm/LLMClient.java` | ✅ |
| Default endpoint `https://ai.xnetwork.ro/v1`, model `gpt-oss-20b` | `config/ConfigManager.java` + `resources/config.yml` | ✅ |
| Chat listener does distance check and sends history | `listener/ChatListener.java` + `chat/ChatService.java` | ✅ |
| Dynamic toggles (`/sb toggle chat false`, `/sb toggle nameonly true`) | `command/SecondBrainCommand.java` + the GUI | ✅ |
| Java 21 / Paper 1.21.x | `pom.xml` (`<java.version>21`), `plugin.yml` (`api-version: '1.21'`) | ✅ |

**No rule in this prompt is currently violated — nothing in the code needs to be rewritten to comply.**

---

## 🧭 Standing rules extracted from the prompt

These are also summarised in [`AGENTS.md`](AGENTS.md) so that any future assistant session starts from them.

1. **Zero dependencies.** Anything new must work with the Paper API alone. No Citizens, no ProtocolLib, no
   NMS, no reflection, no third-party jars in `pom.xml` (unless the user explicitly changes this rule).
2. **NPCs stay native entities** — invulnerable, no AI, silent, custom name tag, driven by a scheduler task.
3. **AI calls go through the native Java `HttpClient`**, async, never blocking the server thread.
4. **Java 21 + Paper 1.21.x only.** Keep the code compiling with `mvn package`.
5. **Keep the toggles working** — a server owner must always be able to switch chat AI off or force
   name-only responses, from both commands and the GUI.
6. **If asked for "real player NPCs with skins": explain the NMS/reflection trade-off first.** Only write
   packet-level code if the user explicitly insists, and then follow the 1.21 Mojang mappings carefully.
