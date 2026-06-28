# Cryon Dev Guide

**Kotlin** **Velocity + Paper** network built as a **feature-loader framework**: the Cryon core is a
Paper plugin that discovers and loads independent **feature jars** (each its own repo) at boot.
Stack: **JDK 25**, **Kotlin 2.4.20-Beta1**, Paper dev bundle **26.2**.

Modules in this repo (core + published API):

- **`:common`** — platform-neutral framework: module system (`Module`, `ModuleManager`,
  `ModuleContext`, `ServiceRegistry`), `number` (`PackedDecimal`, `LongUtils`, `BigDecimalUtils`,
  `NumberUtils`), `text` (`Mini`, `CryonPalette`, `MessageType`, `CommonMessages`), `locale`
  (`MessageService` + sources), `data`/`net` (SQL + Redis), primitive `extension`s. Adventure
  `compileOnly`; no Bukkit/Velocity types (a future `:velocity` loader reuses it). **Published.**
- **`:paper-api`** — what feature repos compile against: `PaperModule`/`PaperModuleContext`,
  `CryonPaper`, `item.ItemBuilder`, `scheduler.Schedulers` (Folia-aware), `event.Events`,
  `command` (annotation framework), Paper `extension`s. Bukkit `compileOnly`. **Published.**
- **`:paper`** — the core plugin / **loader** (`com.tricrotism.cryon.Cryon`). paperweight.userdev +
  Shadow + run-paper; bundles `:common` + `:paper-api` + kotlin-stdlib for loaded features.

Features live in **separate repos** (e.g. `Cryon-Modules/cryon-example-feature/`), `compileOnly` the
API, and ship a thin jar dropped into `plugins/Cryon/modules/`.

**No** DI container, codegen, menu framework, or coroutine bridge yet. **When you add infrastructure
(DI, KSP, InvUI, Folia, the `:velocity` loader), document it here in the same pass** — keep this
guide and the code in lockstep.

---

## Working Practices

Bias toward caution over speed; use judgment on trivial tasks.

- **Think first / ask.** State assumptions. Surface multiple interpretations — don't pick silently.
  Unclear → stop and ask.
- **Simplicity first.** Minimum code that solves the problem. No speculative features, single-use
  abstractions, configurability, or error handling for impossible cases. 200 lines that could be 50 → rewrite.
- **Light logic first.** Lightest tool wins: one-line guard over helper, helper over class, typed
  local over field, existing extension over new one, `if`/`when` over strategy/registry until 3+
  branches truly vary independently. Escalate only when the lighter form provably fails.
- **Surgical changes.** Touch only what you must. Don't refactor or "improve" adjacent code. Match
  existing style. Remove only the symbols *your* change made unused; mention pre-existing dead code, don't delete it.
- **Update deprecations only on lines you already touch** (legacy `§`, untyped lambdas, `@Deprecated`
  Bukkit calls). Don't open a file just to chase them. Non-trivial migration (signature/callsite cascade) → ask first.
- **Wire new effects everywhere they apply.** A multiplier/buff/drop-chance/cost modifier must hook
  every relevant call site (sell, drop, currency, crafting). Firing in one of four paths is a bug.
  Grep the peers and mirror them; unsure → ask.

---

## Kotlin Style

- `val` over `var`. Data classes for models. Sealed interfaces/classes for result types. `object`
  singletons, `companion object` factories/constants.
- **Explicit types on public API** and where inference hurts readability. Null-safety (`?.`/`?:`)
  throughout — no Java-isms; `!!` only when truly invariant.
- **Prefer extension functions** — check existing ones first; reusable helpers go in an `…extension`
  file, not a static util.
- **Schedule through `Schedulers`** (Folia-aware), never raw `Bukkit.getScheduler()`.

---

## Code Quality

Apply ordinary code-smell scrutiny:

- **Null-safety on external returns** — `Bukkit.getPlayer`, config reads, `event.item`,
  `inventory.getItem`, anything crossing the Bukkit boundary. Internal invariants you can trust.
- **Resource leaks** — unregistered listeners, tasks left running on disable, `TextDisplay`/`ItemDisplay`
  not despawned, open inventories unhandled.
- **Mutable shared state without thread-safety** (see Thread Safety).
- **Accidentally quadratic loops** — per-block work inside per-player work, etc.
- **Broad `catch (e: Exception)` that swallows context** — rethrow, log with context, or scope it.
- **Misleading identifiers** (`get…` that mutates, `enabled` meaning the opposite).
- **Dead branches / unreachable returns** your change introduced.

Don't flag: many-param Bukkit event/command signatures, hardcoded dep versions, `TODO`s, wildcard Bukkit returns.

---

## Commit Messages

Single-line title: `[TICKET]` + short imperative. Ticket = Linear ID (`[DEV-915]`); none → scope tag
(`[Build]`, `[Fishing]`, `[Global]`).

- Non-trivial: blank line, then **Problem** (concrete: class/symptom/impact) then **Fix** (mechanism,
  key invariant, preserved overrides).
- Trivial commits (dep bumps, cosmetics, reverts) → title only.
- **Never `Co-Authored-By:` trailers (Claude/Anthropic/AI/anything) or emoji.**

```
[Fishing] Gate rare-catch bonus behind the fishing feature flag

The ranged-spear bonus fired even when fishing was disabled, so admins
couldn't kill it independently of the core sell loop.

Wraps the bonus award in isEnabled(FISHING_RARE) and short-circuits before
the BigDecimal multiply.
```

---

## Feature Flags

**Every feature gets one; every independently-meaningful sub-feature gets its own** (command,
broadcast, scheduler, payout path, animation phase, mode toggle). Umbrella flags force killing the
whole feature when one slice breaks.

**No flag system exists yet.** Design for the seam: each entry point (commands, handlers, schedulers,
menu opens) behind a single guard, distinct slices behind distinct guards. When you build the system,
use bare flag IDs (`FISHING`, `FISHING_RARE` — **no** gamemode prefixes), gate every entry point, and document it here.

---

## Messaging — Adventure + MiniMessage

Send `Component`s — **never legacy `§` strings, never string interpolation into messages.** Use the
helpers: `CommonMessages` acks, `audience.sendError("…")`, `Mini.format(...)`/`"…".mm()`. `Mini` is
the cached, palette-loaded MiniMessage; **never `MiniMessage.miniMessage()`**.

```kotlin
player.sendError("You don't have enough scales.")            // « Error » prefix
player.sendMessage("<emerald>Enchantment applied!".mm())
```

Dynamic content uses placeholders, **never `"...$value..."`**:

```kotlin
player.sendMessage(
    Mini.format(
        "<off_white>Caught a <highlight><rarity></highlight> fish! Earned <highlight><amount></highlight> scales.",
        Placeholder.unparsed("rarity", rarity.name),
        Placeholder.unparsed("amount", amount.toString()),
    )
)
```

`Placeholder.unparsed`/`.component`/`.parsed`. Palette tags (`<off_white>`, `<scarlet>`, semantic
`<error>`/`<success>`/…) resolve through `Mini`. Multi-language copy pulls from `MessageService` by
key (never hardcode English); localized + prefixed ack → `messages.send(player, MessageType.ERROR, "key", …)`.

---

## Item Lore

Prefer `ItemBuilder` — auto-applies non-italic (`<!i>`) to name/lore and chains flags/glow/attributes/PDC:

```kotlin
val item = Material.TRIDENT.toItem()
    .name("<aqua>Fish Spear")
    .lore("<gray>Deals bonus damage to fish.")
    .build()
```

Hand-building meta is fine for one-offs, but every name/lore line needs `<!i>`, and lore lines are `Component`s, never
`§`-coded.

---

## Audio Cues

Silent feedback feels broken — play a `Sound.*` on every player-facing action (message, menu, toggle,
redeem, drop). Only vanilla sounds exist.

```kotlin
player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f)
```

- Personal feedback → `player.location`. Ambient/broadcast → a world `Location`.
- Pitch: `1.5f`–`2.0f` positive/rare, `1.0f` neutral, `0.5f`–`0.8f` warnings/heavy. Volume `1f`
  standard, `0.5f` incidental/repeating.
- Match existing feature sounds before inventing new ones.

---

## Giving Items

`inventory.addItem` and **handle overflow deliberately** — never `dropItemNaturally` as the
"inventory full" fallback (anyone can grab it; it despawns).

```kotlin
val leftover = player.inventory.addItem(item) // slot -> what didn't fit; decide explicitly
```

`world.dropItemNaturally` only for genuine world drops (block breaks, deaths).

---

## Events & Listeners

Prefer the functional `Events` builder — no `Listener` class, returns a cancellable `Subscription`,
filters run before the handler:

```kotlin
Events.subscribe(PlayerInteractEvent::class.java, EventPriority.HIGHEST)  // or Events.subscribe<PlayerInteractEvent>(…)
    .ignoreCancelled()
    .filter { it.hand == EquipmentSlot.HAND }
    .handler { event -> /* … */ }
```

Inside a `PaperModule`, `listen(listener)` covers a classic `@EventHandler` `Listener` and
auto-unregisters on disable. Either way: filter cheaply first, tear down on disable.

---

## Thread Safety

Shared mutable state (`object`/`companion` fields touched by >1 thread/scheduler) must be thread-safe; lightest correct
type:

- **`ConcurrentHashMap`** for shared maps; **`Collections.newSetFromMap(ConcurrentHashMap())`** for sets.
- **`merge()` for counter increments** (`getOrDefault + 1 + put` is a TOCTOU race):
  `rarityCount.merge(rarity, 1) { a, b -> a + b }`
- **`computeIfAbsent`/`putIfAbsent`** for check-set, never `containsKey` + `put`.
- **No Bukkit API off the main thread** — entities, inventories, particles, `TextDisplay.text(…)`.
  Do async work, then hop back. Folia: use region/entity schedulers and document it.
- **Locals that never escape a tick** can use plain `HashSet`/`ArrayList`.

---

## Performance

- **Cache lookups before hot loops** — resolve services/config once above the loop.
- **`x shr 4` for block→chunk coords**, not `block.chunk.x` (allocates a `Chunk`).
- **Batch high-frequency work** — accumulate deltas in a thread-safe per-player buffer, flush on
  interval or boundary (logout, level-up, sell-cycle end), not one DB write per event.
- **Hot-loop math:** `BigDecimal.valueOf(long)` over `BigDecimal(double)`; hoist invariant lookups;
  branch out no-op multiplies (`multiplier == 1.0`); keep per-rank multipliers as `BigDecimal` where a `long` could
  overflow.
- **No accidentally quadratic loops** — don't iterate online players inside per-player/per-block work.

These target hot loops (per-tick/block/player). Cold paths (boot, admin commands, file events) follow **simplicity first
** instead.

---

## Comments

No decorative dividers (`// ── fishing ──`). Comment the *why* when non-obvious; don't narrate what the code says.

---

## Build & Run

JDK 25, Kotlin 2.4.20-Beta1, paperweight dev bundle 26.2. Gradle config-cache/parallel/build-cache on. No unit tests —
verify on a local server.

- `./gradlew build` — all modules; `:paper`'s `build` runs `shadowJar` → `paper/build/libs/`. The
  shaded jar bundles `:common` + `:paper-api` + kotlin-stdlib — **don't relocate kotlin-stdlib**
  (features resolve `kotlin.*` through it).
- `./gradlew :paper:runServer` — local Paper 26.2 with the core loaded; drop feature jars into `plugins/Cryon/modules/`.
- `./gradlew :common:publishToMavenLocal :paper-api:publishToMavenLocal` — publish the API locally
  (production: `repo.striveservices.org`).
- Versions declared once in root `build.gradle.kts` (`apply false`); subprojects apply without
  versions. `group`/`version` from `gradle.properties`. `plugin.yml` `${version}` filled by `processResources` — don't
  hardcode.

---

## Module System

Features are **modules**, each a jar (own repo) loaded by the core. Framework types in `:common`
(`…common.module`); Paper base in `:paper-api`.

**Loader (`Cryon.kt` → `ModuleLoader`, `…cryon.module`):** on enable, loads every jar in
`plugins/Cryon/api/` into **one shared `URLClassLoader`** (the contract layer), then loads each
`modules/*.jar` in its **own isolated `URLClassLoader`** parented to that shared loader (→ core →
Paper + `:common`/`:paper-api` + kotlin-stdlib). Discovers `Module`s via `ServiceLoader`, then
`loadAll(context)` → `enableAll()`. Broken jar logged and skipped (caught as `Throwable` —
`ServiceConfigurationError` is an `Error`). Loaders closed on disable (modules before parent). Empty `api/` → shared
layer skipped.

**Cache copies:** each feature jar is copied into a private `plugins/Cryon/.module-cache/` and loaded
from the copy, so the original in `modules/` is never file-locked (matters on Windows) and can be
deleted/replaced while running — the basis for hot-swap. Closing a loader frees its file handle and
drops its lang bundle/listeners; reclaiming classes still depends on the module not leaking refs (usual reload caveat).
Cache wiped on boot and disable.

**Core types:**

- **`Module`** (`:common`) — `id` + two-phase lifecycle: `onLoad(context)` (publish services),
  `onEnable()` (consume peers), `onDisable()`. `ServiceLoader`-discovered → needs **no-arg ctor** +
  `META-INF/services/com.tricrotism.cryon.common.module.Module` entry.
- **`ModuleContext`** (`:common`) — `logger` + `services`; Paper's `PaperModuleContext` adds `plugin`/`server`.
- **`ServiceRegistry`** (`:common`) — intertwine seam. `register(Api::class, impl)` / `get<Api>()` /
  `find<Api>()` (reified variants exist), keyed by interface.
- **`PaperModule`** (`:paper-api`) — base exposing `plugin`/`server`/`services`/`logger`; `listen(listener)`
  auto-unregisters on disable. Override `onLoad` (call `super` first), `onEnable`, `onDisable` (call `super`).

**Isolation → intertwine only through shared interfaces.** Feature jars can't see each other's
classes. Expose behaviour by registering an impl of an **API interface in a shared artifact** (`:common`,
`:paper-api`, or an `api/` contract jar). Never reference another feature's concrete classes.

**Cross-module contracts — the `api/` layer.** To expose an API to a feature in *another repo*, the
contract type must load from the **shared parent**, not be bundled in either jar (two copies = two
`Class` objects = `ClassCastException`). Ship the interfaces in a thin **`*-api` jar** in
`plugins/Cryon/api/`; both provider and consumer depend on it **`compileOnly`**. Provider
`services.register(FooService::class, impl)` in `onLoad`; consumer `services.find(FooService::class)`
in `onEnable` (**`find`, not `get`** — the other repo may be absent). Reference: `cryon-visibility-api`
consumed by `cryon-visibility`. Reserve `api/` for genuinely cross-repo contracts; a contract the core ships can live in
`:paper-api`.

**Order-independence:** every module's `onLoad` runs before any `onEnable`, so peer services are always available in
`onEnable`. No declared load order.

**Runtime lifecycle.** `ModuleManager` tracks `ModuleState` (`REGISTERED`/`LOADED`/`ENABLED`/`DISABLED`/`FAILED`)
and supports `enable`/`disable`/`reload(id)` (re-enable reuses the load-time context), plus
`load(id, context)` and `unregister(id)` for single-module hot-swap churn. Surfaced via `/cryon
modules|info|enable|disable|reload <id>`. Registered into `ServiceRegistry` so a module reads its own
state via `PaperModule.isEnabled()`. **Main-thread only.**

**Failure isolation — a feature must never crash the server.** Every seam where the framework invokes
feature code catches **`Throwable`** (not just `Exception` — a stale/mislinked jar throws `Error`s
like `NoSuchMethodError`): module `onLoad`/`onEnable`/`onDisable` (failure → `FAILED`, server
continues), jar reads, command registration inside the COMMANDS lifecycle handler (both
`PaperModule.registerCommands` and the core's — Paper rethrows lifecycle exceptions fatally, so they
must be guarded), `Events` handlers, and the watcher thread. A `FAILED` module is in-memory only and
doesn't auto-retry; it clears on a successful `/cryon enable|reload <id>`, a reload of its jar, or
restart. The one thing outside our control is a feature that bypasses these helpers and registers its
own raw Paper lifecycle handler — route command registration through `registerCommands`, and keep the
published `:paper-api` binary-compatible (`@JvmOverloads` on defaulted params) so old feature jars still link.

**Hot-swap (jar level).** `ModuleLoader` adds/removes whole jars at runtime: `/cryon load <jar>`
(load + enable a jar in `modules/`), `/cryon unload <id>` (disable + unregister every module in that
jar, close its loader; the jar file stays — delete to remove permanently), `/cryon scan` (load newly
dropped jars). A hot-loaded jar's modules all `onLoad` before any `onEnable`. Unload is whole-jar
(can't partially close a loader). On unload the loader also calls `ServiceRegistry.unregisterByClassLoader`
to drop services the jar published (so a reload re-registers cleanly and peers can't resolve a
dead-loader instance). These call `Player.updateCommands()` to resync command trees.

**`api/` reload (cascade).** The `api/` contract layer parents every module loader, so it can't be
swapped alone (running modules stay linked to the old contract classes). `/cryon reload-api` does the
only coherent thing: unload **all** modules → close + reload the `api/` loader → reload every module
that was loaded, preserving the global two-phase order. Briefly takes all features down. Use it after
replacing an `api/` jar.

**Auto hot-reload (dev).** Two daemon `WatchService`s (`ModuleWatcher`) — one on `modules/` (per-jar
load/reload/unload on file events) and one on `api/` (any change → a full `reload-api` cascade) —
debounced and hopped to the main thread. **Config-gated** by `modules.auto-reload` in the core
`config.yml`, **defaulting to `!production`** (`production: true` by default): a `production: false`
dev server hot-reloads automatically, production doesn't, either overridable. The `/cryon
load|unload|scan|reload-api` commands work regardless.

**Runtime command-registration caveat.** Paper only allows registering a COMMANDS lifecycle handler
during the bootstrap/enable window, so a module **loaded or reloaded at runtime** (hot-swap, `/cryon
load`, `reload-api`) cannot (re)register its command tree — `PaperModule.registerCommands` catches
this, logs a warning, and lets the module enable anyway; its commands refresh on the next server
reload/restart. Everything else (listeners, services, schedulers) re-wires live. A proper fix (the
core owning one COMMANDS handler that all modules contribute to) is the documented next step.

**Commands track module state.** A `@Command` class registered via `PaperModule.registerCommands(…)`
is gated on `isEnabled()` (passed as `available` to `AnnotationCommands`), so it can't run or
tab-complete while disabled and reappears on re-enable without re-registering (guard re-evaluated per
dispatch). `/cryon enable|disable|reload` calls `Player.updateCommands()`. A disabled command shows
vanilla "unknown command" — the trade-off for gating at the Brigadier layer.

**Authoring a feature:** new repo → `compileOnly` published `:common` + `:paper-api` (+ Paper API) →
`class Foo : PaperModule()` no-arg ctor → add `META-INF/services` entry → build the thin jar → drop into `modules/`.

When you add the `:velocity` loader, give it its own `ModuleManager` + `VelocityModuleContext` over the same `:common`
contract, and document it here.

---

## Utilities

Shared helpers — **check these before writing your own.** Reach a peer feature's behaviour through `ServiceRegistry`,
not these.

**Numbers (`…common.number`/`…extension`):**

- `PackedDecimal` is the effectively-unbounded scaling value for currencies/idle math: a base-10
  number (14-digit signed mantissa + power-of-ten exponent) packed into a single `Long`, so as a
  `@JvmInline value class` it is **zero-allocation** and ~5x the throughput of a boxed mantissa/exponent
  class. ~14 significant figures, range ~10^±32767 (the exponent saturates). Operators,
  `pow`/`sqrt`/`cbrt`/`log*`, `of(...)`/`tenPow(...)`, `magnitude` (decimal order of magnitude). Use it
  (not `BigDecimal`) for anything past `~1e15` on a hot path.
- `LongUtils`, `BigDecimalUtils` (`magnitude`/`log10` past `Double` range), `NumberUtils`
  (`formatBalance`/`formatCommas`/`roman`/`parseBalance`, thread-safe), primitive extensions (`1500L.formatBalance()`,
  `5.pd`).

**Text (`…common.text`):**

- `Mini` — non-strict MiniMessage, palette-preloaded, ~15s Caffeine cache, legacy interop. Use
  `Mini.format(...)`/`"…".mm()`, **never `MiniMessage.miniMessage()`**.
- `CryonPalette` — named colours as `TextColor`s **and** tags (`<off_white>`, semantic `<error>`/`<success>`/…). Tune
  hexes / extend `RESOLVER` here.
- `CommonMessages` — prefixed acks (`error`/`success`/`info`/`warn`, `notOnline`, `notEnoughCurrency`,
  `noPermission`, …) returning `Component`s. Prefix is a bold icon per `MessageType` (`✖`/`✔`/`✦`/`⚠`,
  language-neutral). Canned bodies localized via `Messages` by `cryon.common.*` key; Paper extensions
  (`player.sendNoPermission()`, …) pass `resolvedLocale()`.

**i18n (`…common.locale`): everything user-facing is localizable.** `MessageService` resolves
`(locale, key) → Component` across `MessageSource`s with a fallback chain, `renderPlural`, hot
`reload()`. `Messages` is the static facade. **Auto-scanned — don't register by hand:** a jar's
`lang/<locale>.properties` registers on load; `plugins/Cryon/lang/` (admin override) registers first
and wins. Send via `messages.send(player, key, …)` or `messages.send(player, MessageType.ERROR, key, …)`. Missing keys
render `⟨key⟩`.

**Items (`…paper.api.item`/`…extension`):** `ItemBuilder` — name/lore (auto `<!i>`, palette-parsed),
flags, glow, `enchant`, attributes, PDC `tag`s, `meta {}`. Extensions: `Material.toItem()`,
`ItemStack.toBuilder()`/`modify {}`, `get/set/has/removeTag` (PDC), `isEmpty()`, `withAmount()`.

**Scheduling (`…paper.api.scheduler`):** `Schedulers` — `global`/`region(loc)`/`entity(e)`/`async`,
each with `*Later`/`*Timer`. Pick the scope owning the data; no Bukkit API in `async`.

**Events (`…paper.api.event`):** `Events.subscribe(Type::class.java, priority)` (or reified
`subscribe<Type>()`)`.filter{…}.handler{…}` → cancellable `Subscription`; `expireAfter(n)` self-unregisters. Handler
exceptions logged, not propagated.

**Commands — annotation framework over Paper Brigadier (`…paper.api.command`).** **Cloud is broken on
26.2** (cloud-bukkit's `ItemStackParser` reflects a missing method). Use the `@Command` layer
registered via `LifecycleEvents.COMMANDS`. **Never `plugin.yml commands:` / `CommandMap` / `Commands.create()` / Cloud.
**

```kotlin
@Command("cryon", "Module manager")
@Permission("cryon.admin")
class ModuleCommands(private val modules: ModuleManager) {
    @Subcommand
    fun overview(sender: CommandSender) = list(sender)
    @Subcommand("enable")
    fun enable(sender: CommandSender, @Arg("id", suggests = "ids") id: String) {
        …
    }
    fun ids(): Collection<String> = modules.ids()   // suggester
}
```

`@Command`/`@Subcommand`/`@Permission`/`@Arg(name, suggests)`/`@Greedy`. `CommandSender` injected;
`@Arg` types `String`/`Int`/`Boolean`. Built-ins: `/cryon` (`cryon.admin`), `/language` (`/lang`).
**Feature modules register via `PaperModule.registerCommands(…)` in `onLoad`** (gated on `isEnabled`);
core commands use the plain `register(registrar, vararg handlers)` (always available).

**Cross-server infra (`…common.data`/`…common.net`): opt-in, config-gated.**

- `Database` (`PostgresDatabase`, HikariCP) — async SQL: `query`/`update` return `CompletableFuture`s
  off-thread. No ORM. **Never `.get()` on the main thread.**
- `Messenger` (`RedisMessenger`, Lettuce) — cross-server `publish`/`subscribe` + `request`/`handle`. String payloads.
- Both registered into `ServiceRegistry` when enabled (`services.find<Database>()`/`find<Messenger>()`
  — use `find`, may be absent). Client libs load at runtime via `plugin.yml` `libraries:`. `config.yml`
  holds `database.*`, `redis.*` (both `enabled: false` by default), plus `production` (default `true`)
  and `modules.auto-reload` (defaults to `!production`).

**Player locale — persistent & cross-server.** `Player.resolvedLocale()` = stored override ?: client
`locale()`; all helpers use it. A chosen override (`player.setLanguage(de)`) persists to SQL +
broadcasts a Redis invalidation; `PlayerLocaleStore` caches it in memory for sync reads. The core
installs `PlayerLocaleStore` when SQL+Redis are configured, else `MemoryLocaleStore` (overrides work
but per-server, reset on restart). A store is always installed; falls back to client locale without an override.

**Not yet:** DI container, codegen, menu framework, coroutine bridge. Add infrastructure **and document it here in the
same pass.**

---

## Summary — Do / Don't

| Do                                                       | Don't                                                          |
|----------------------------------------------------------|----------------------------------------------------------------|
| `CommonMessages.error(…)` / `audience.sendError(…)`      | `player.sendMessage("§c§lError §7> …")`                        |
| `Mini.format("…", Placeholder…)` / `"…".mm()`            | `MiniMessage.miniMessage()`; `"…$value…"`                      |
| `MessageService` keys for multi-language copy            | Hardcoded English strings in features                          |
| `<!i>` in lore (or `ItemBuilder`, which does it)         | Raw `§o` italic prefix in lore                                 |
| `ItemBuilder` / `Material.toItem()`                      | Hand-rolled `editMeta` for every item                          |
| `Schedulers.async/global/region/entity`                  | raw `Bukkit.getScheduler()`                                    |
| `Events.subscribe(...).filter{}.handler{}`               | ad-hoc `Listener` plumbing for one handler                     |
| `PackedDecimal` for values that grow past ~1e15          | `BigDecimal` on hot incremental-math paths                     |
| `@Command`/`@Subcommand` + `AnnotationCommands.register` | `plugin.yml commands:` / `CommandMap` / Cloud (broken on 26.2) |
| `player.resolvedLocale()` for messages                   | `player.locale()` directly (ignores overrides)                 |
| `services.find<Database>()` / `find<Messenger>()`        | `get<…>()` assuming the infra is enabled                       |
| Consume the DB/Redis `CompletableFuture` async           | `.get()` on a DB future on the main thread                     |
| Play a `Sound.*` on player-facing actions                | Silent state changes / redeems                                 |
| `inventory.addItem` + handle overflow deliberately       | `dropItemNaturally` as the "didn't fit" path                   |
| Explicit types; `val` over `var`                         | Java-isms; needless `var`; gratuitous `!!`                     |
| Typed lambda params (`{ event: PlayerInteractEvent ->`)  | Untyped params relying on inference where it hurts             |
| `ConcurrentHashMap` for shared static state              | `HashMap`/`HashSet` for shared static state                    |
| `merge()` for shared-map counter increments              | `getOrDefault + 1 + put`                                       |
| `computeIfAbsent` / `putIfAbsent`                        | `containsKey` + `put`                                          |
| Bukkit API only on the server thread                     | Bukkit API in `runTaskAsynchronously`                          |
| Cache service/config lookups before hot loops            | Re-resolving them inside loops                                 |
| `x shr 4` for block→chunk coords                         | `block.chunk.x` / `block.chunk.z`                              |
| A guard/flag per distinct slice of behavior              | One umbrella guard covering many slices                        |
| Bare flag IDs (`FISHING`)                                | Gamemode-prefixed flag IDs (`A_FISHING`)                       |
| `[TICKET]` imperative commit titles                      | `Co-Authored-By:` trailers / emoji in commits                  |
