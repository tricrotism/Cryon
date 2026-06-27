# Cryon Dev Guide

**Kotlin** project for a **Velocity + Paper** network, built as a **feature-loader framework**:
the Cryon core is a Paper plugin that discovers and loads independent **feature jars** (each its
own repo) at boot. Stack: **JDK 25** toolchain, **Kotlin 2.4.20-Beta1**, Paper dev bundle **26.2**.

This repo (the core + published API):

- **`:common`** — platform-neutral framework + utilities. Module system (`Module`, `ModuleManager`,
  `ModuleContext`, `ServiceRegistry`), plus `number` (`LongUtils`, `BigDecimalUtils`, `NumberUtils`,
  `CryonNumber`), `text` (`CryonPalette`, `MessageType`, `CommonMessages`), `locale` (`MessageService`
    + sources), and primitive `extension`s. Adventure on `compileOnly`; no Bukkit/Velocity types, so a
      future `:velocity` loader reuses it. **Published.**
- **`:paper-api`** — Paper-side API feature repos compile against: `PaperModule`/`PaperModuleContext`,
  `CryonPaper` (plugin handle), `item.ItemBuilder`, `scheduler.Schedulers` (Folia-aware),
  `event.Events`, and Paper `extension`s. Bukkit on `compileOnly`. **Published.**
- **`:paper`** — the core plugin / **loader** (`com.tricrotism.cryon.Cryon`). Built with
  `paperweight.userdev` + Shadow + `run-paper`; bundles `:common` + `:paper-api` + kotlin-stdlib so
  loaded features see them. On enable it calls `CryonPaper.init`, registers a `MessageService`, loads
  shared contract jars from `plugins/Cryon/api/`, then scans `plugins/Cryon/modules/` for feature
  jars. See **Module System** and **Utilities**.

Features live in **separate repos** (e.g. `Cryon-Modules/cryon-example-feature/`), compile the API
as `compileOnly`, and build a thin jar dropped into `plugins/Cryon/modules/`. A `:velocity` loader
can be added later against the same `:common` framework with no refactor.

Beyond the module loader there is still **no** DI container, codegen, menu framework, or coroutine
bridge (SQL persistence, Redis messaging, and a `config.yml` now exist — see **Utilities**). This
guide documents only what exists today
plus the universal disciplines we hold across projects. **When you add infrastructure (DI, DB,
KSP, InvUI, a config/feedback abstraction, Folia support, the `:velocity` loader), document it
here in the same pass** — don't let this guide describe infra that doesn't exist, and don't
leave new infra undocumented.

---

## Working Practices

Bias toward caution over speed. Use judgment on trivial tasks.

- **Think first.** State assumptions. If multiple interpretations exist, surface them — don't
  pick silently. If something is unclear, stop and ask.
- **Simplicity first.** Minimum code that solves the problem. No speculative features,
  abstractions for single-use code, configurability, or error handling for impossible cases.
  If you wrote 200 lines and it could be 50, rewrite.
- **Light logic first.** Reach for the lightest tool: a one-line guard over a helper, a helper
  over a class, a typed local over a new field, an existing extension function over a new one,
  a direct `if`/`when` chain over a strategy/registry until 3+ branches truly vary
  independently. When torn between two approaches, write the lighter one; escalate only when it
  provably fails.
- **Surgical changes.** Touch only what you must. Don't refactor what isn't broken or "improve"
  adjacent code. Match existing style. Remove only the imports/symbols *your* change made
  unused; mention pre-existing dead code, don't delete it.
- **Update deprecated usages when you touch a file** — migrate legacy `§` color codes, untyped
  lambdas, IDE-flagged `@Deprecated` Bukkit calls — but only on lines you'd already be reading
  or changing. Don't open a file purely to chase deprecations. If a migration is non-trivial
  (signature change, callsite cascade), ask first.
- **Wire new effects everywhere they apply.** When a feature adds a multiplier, buff, debuff,
  drop chance, or cost modifier that should stack with existing systems, hunt down every
  relevant call site and hook it in (sell paths, drop paths, currency awards, crafting, etc.).
  A new multiplier that only fires in one of four paths is a bug, not a feature. Grep for the
  existing peers and mirror them. If unsure which paths apply, ask before shipping.

---

## Kotlin Style

- `val` over `var`. Data classes for models. Sealed interfaces/classes for result types
  (`sealed interface TransactionResult { data object Success; data class Failure(val reason: Component) }`).
  `object` for singletons, `companion object` for factories/constants.
- **Explicit types on public API** and anywhere inference hurts readability. Null-safety
  (`?.` / `?:`) throughout — no Java-isms (`!!` only when truly invariant).
- **Extension functions are preferred** — check existing extensions before writing a helper;
  put genuinely reusable helpers in an `…extension` file rather than a static util.
- **Scheduling goes through `Schedulers`** (`:paper-api`, Folia-aware) — `async { … }` for
  off-thread work, `global`/`region`/`entity` for main-thread work in the right scope. Don't reach
  for raw `Bukkit.getScheduler()`. No coroutine bridge yet; if one is added, document it here and
  migrate. See **Utilities** and **Thread Safety**.

---

## Code Quality

Apply ordinary code-smell scrutiny when writing or reviewing:

- **Null-safety on external returns** — `Bukkit.getPlayer(...)`, config reads,
  `event.item`, `inventory.getItem(...)`, anything crossing the Bukkit boundary. Internal code
  can trust your own invariants.
- **Resource leaks** — listeners never unregistered, scheduled tasks left running on disable,
  `TextDisplay`/`ItemDisplay` entities not despawned, open inventories not handled.
- **Mutable shared state without thread-safety** (see Thread Safety).
- **Accidentally quadratic loops** — nested iterations over online players, per-block work
  inside per-player work.
- **Broad `catch (e: Exception)` that swallows context.** Rethrow, log with context, or scope
  the catch.
- **Misleading identifiers** (`get…` that mutates, `enabled` that means the opposite).
- **Dead branches / unreachable returns** introduced by your own changes.

Don't flag (intentional or out of scope): many-parameter Bukkit event/command signatures,
hardcoded dependency versions, `TODO` comments, wildcard Bukkit return types.

---

## Commit Messages

Title: `[TICKET]` then a short imperative description. Single line.

- Ticket = Linear ID (`[DEV-915]`). With no ticket, fall back to a scope tag for the area
  touched (`[Build]`, `[Fishing]`, `[Global]`).
- For non-trivial changes: blank line, then a 1–2 paragraph body — **Problem** (concrete: name
  the class/symptom/impact) then **Fix** (the mechanism, key invariant, preserved overrides).
- Trivial commits (dep bumps, cosmetics, reverts) can stay title-only.
- **Never add `Co-Authored-By:` trailers (Claude, Anthropic, AI tooling, anything) or emoji**,
  even if the workflow seems to imply it.

```
[Fishing] Gate rare-catch bonus behind the fishing feature flag

The ranged-spear bonus fired even when fishing was disabled, so admins
couldn't kill it independently of the core sell loop.

Wraps the bonus award in isEnabled(FISHING_RARE) and short-circuits before
the BigDecimal multiply. /admin fishing force bypasses for testing.
```

---

## Feature Flags

**Every new feature gets one. Every independently-meaningful sub-feature gets its own too**
(separate command, broadcast, scheduler, payout path, animation phase, mode toggle). Single
umbrella flags force shutting off the whole feature when one slice breaks.

There is **no flag system wired into Cryon yet.** Until one exists, still design features so a
flag is the natural seam — keep each entry point (commands, event handlers, schedulers, menu
opens) behind a single guard you can later point at a flag check, and keep distinct slices
behind distinct guards. When you add the flag system, register a bare flag ID per slice
(`FISHING`, `FISHING_RARE` — **no** gamemode prefixes), gate every entry point through it, and
document the mechanism here.

---

## Messaging — Adventure + MiniMessage

Paper bundles Adventure. Send `Component`s, **never legacy `§` strings, never Kotlin string
interpolation into messages.** Prefer the helpers in **Utilities** — `CommonMessages` for prefixed
acks, `audience.sendError("…")` extensions, `Mini.format(...)`/`"…".mm()` for palette-aware parsing
— over rolling raw MiniMessage. `Mini` is the project's cached, palette-loaded MiniMessage; never
`MiniMessage.miniMessage()`.

```kotlin
import com.tricrotism.cryon.paper.api.extension.sendError
import com.tricrotism.cryon.paper.api.extension.mm

player.sendError("You don't have enough scales.")            // « Error » prefix + sound-free ack
player.sendMessage("<emerald>Enchantment applied!".mm())
```

For dynamic content use placeholders (`net.kyori.adventure.text.minimessage.tag.resolver.Placeholder`),
**never** `"...$value..."`:

```kotlin
player.sendMessage(Mini.format(
    "<off_white>Caught a <highlight><rarity></highlight> <type> fish! Earned <highlight><amount></highlight> scales.",
    Placeholder.unparsed("rarity", rarity.name),
    Placeholder.unparsed("type", type.name),
    Placeholder.unparsed("amount", amount.toString()),
))
```

`Placeholder.unparsed("k", str)` for plain text, `Placeholder.component("k", component)` for a
prebuilt `Component`, `Placeholder.parsed("k", "<aqua>")` to inject a parsed tag. Standard
MiniMessage tags plus the full `CryonPalette` tag set (`<off_white>`, `<scarlet>`, semantic
`<error>`/`<success>`/…) resolve through `Mini`. For player-facing copy that ships in multiple
languages, pull templates from the `MessageService` by key rather than hardcoding strings — and for
a localized **and** prefixed ack use `messages.send(player, MessageType.ERROR, "key", …)` (see
**Utilities → i18n**).

---

## Item Lore

Prefer `ItemBuilder` (`:paper-api`) — it applies the non-italic (`<!i>`) convention to name/lore for
you and chains flags/glow/attributes/PDC tags:

```kotlin
val item = Material.TRIDENT.toItem()
    .name("<aqua>Fish Spear")
    .lore("<gray>Deals bonus damage to fish.")
    .addLore(Mini.format(
        "<off_white>Level: <highlight><level>/<max>",
        Placeholder.unparsed("level", level.toString()),
        Placeholder.unparsed("max", maxLevel.toString()),
    ))
    .build()
```

Building meta by hand is fine for one-offs, but use `<!i>` on every name/lore line (`ItemBuilder`
does this automatically) and remember lore lines are `Component`s, never `§`-coded strings.

---

## Audio Cues

Silent feedback feels broken. Any time a player-facing action fires (message, menu open, state
toggle, item redeemed, drop awarded), play a sound. Only vanilla `Sound.*` exists right now
(no resource-pack sound set wired).

```kotlin
player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f)
```

- Personal feedback → `player.location` (only the actor hears it). Ambient/broadcast → a world
  `Location`.
- Pitch carries meaning: `1.5f`–`2.0f` positive/rare, `1.0f` neutral, `0.5f`–`0.8f`
  warnings/heavy actions. Volume `1f` standard; `0.5f` for incidental/repeating.
- Match existing feature sounds before inventing new ones — players learn what each means.

---

## Giving Items

Add to the player's inventory; **don't drop on the ground as the "inventory full" fallback** —
anyone nearby can grab it and it can despawn. There's no box-storage helper yet, so handle
overflow deliberately:

```kotlin
val leftover = player.inventory.addItem(item) // map of slot -> what didn't fit
// decide explicitly what to do with leftover: re-try later, hold it, message the player —
// not a blind dropItemNaturally.
```

Drop on the ground (`world.dropItemNaturally`) only for genuinely world-placed drops (block
breaks, deaths). When a give/box helper is added, prefer it over hand-rolled overflow loops and
document it here.

---

## Events & Listeners

Prefer the functional `Events` builder (`:paper-api`) — no `Listener` class, returns a cancellable
`Subscription`, filters run before your handler:

```kotlin
Events.subscribe(PlayerInteractEvent::class.java, EventPriority.HIGHEST)
    .ignoreCancelled()
    .filter { it.hand == EquipmentSlot.HAND }
    .filter { it.item?.type == Material.TRIDENT }
    .handler { event -> /* … */ }
```

Inside a `PaperModule`, the `listen(listener)` helper still covers a classic `@EventHandler`
`Listener` and auto-unregisters it on disable — use that when you want annotation-based handlers.
Either way, filter cheaply first (hand, material, world) and tear down on disable (`Subscription
.unregister()` / `listen` cleanup).

---

## Thread Safety

Shared mutable state (`object`/`companion object` fields touched by more than one thread or
scheduler) must be thread-safe. Use the lightest correct type.

- **`ConcurrentHashMap`** for shared maps — never `HashMap`/`LinkedHashMap` for a static field
  touched off the main thread.
- **`Collections.newSetFromMap(ConcurrentHashMap())`** for shared sets.
- **`merge()` for atomic counter increments** — `getOrDefault + 1 + put` is a TOCTOU race:
  ```kotlin
  rarityCount.merge(rarity, 1) { a, b -> a + b }
  ```
- **`computeIfAbsent` / `putIfAbsent`** for check-set, never `containsKey` + `put`.
- **No Bukkit API off the main thread** — entities, inventories, particles, `TextDisplay.text(…)`
  are Bukkit API and belong on the server thread. Do async work
  (`runTaskAsynchronously`), then hop back with `runTask(plugin) { … }` for the Bukkit calls.
  If Folia support is enabled later, switch world/entity ops to the region schedulers
  (`Bukkit.getRegionScheduler()`, `entity.scheduler`) and document it.
- **Locals inside a single tick can use plain `HashSet`/`ArrayList`** — non-concurrent
  collections are fine and faster when the value never escapes the tick.

---

## Performance

- **Cache lookups before hot loops** — resolve plugin services / config values once above the
  loop, not per iteration.
- **`x shr 4` for block→chunk coords**, not `block.chunk.x` (allocates a `Chunk`).
- **Batch high-frequency work** — don't fire one DB write / recompute / redraw per block-break,
  kill, or sell event. Accumulate deltas in a thread-safe per-player buffer and flush on a
  short interval or a meaningful boundary (logout, level-up, sell-cycle end). One periodic
  flush of N updates beats N individual ones.
- **Math in hot loops:** `BigDecimal.valueOf(long)` over `BigDecimal(double)` (the latter has
  non-deterministic expansion); hoist invariant multiplier lookups and constants above the
  loop; branch out no-op multiplies (`multiplier == 1.0` is the common case); keep per-rank
  multipliers as `BigDecimal` where a `long` could overflow.
- **Avoid accidentally quadratic loops** — don't iterate online players inside a per-player or
  per-block loop.

---

## Comments

No decorative section dividers (`// ── fishing ───────`). Comment the *why* when it isn't
self-evident; don't narrate what the code already says.

---

## Build & Run

JDK 25, Kotlin 2.4.20-Beta1, paperweight dev bundle 26.2. Gradle config-cache, parallel, and
build-cache are on (`gradle.properties`). No unit-test suite — verify manually via a local
server.

- `./gradlew build` — builds all modules; `:paper`'s `build` runs `shadowJar`. Core plugin jar
  lands in `paper/build/libs/` (the shaded jar bundles `:common` + `:paper-api` + kotlin-stdlib —
  **don't relocate kotlin-stdlib**; loaded feature jars resolve `kotlin.*` through it).
- `./gradlew :paper:runServer` — boots a local Paper 26.2 server with the core loaded
  (`-Xms2G -Xmx2G`, EULA auto-agreed). Drop feature jars into the server's
  `plugins/Cryon/modules/` to have them loaded.
- `./gradlew :common:publishToMavenLocal :paper-api:publishToMavenLocal` — publish the API so
  feature repos can compile against it locally (production: `repo.striveservices.org`).
- Plugin versions are declared once in the root `build.gradle.kts` (`apply false`); subprojects
  apply them without versions. `group`/`version` come from `gradle.properties`.
- `:paper`'s `plugin.yml` `${version}` is filled by `processResources` — don't hardcode it.

---

## Module System

Features are **modules**, each shipped as its own jar (its own repo) and **loaded at boot** by the
core. Framework types live in `:common` (`com.tricrotism.cryon.common.module`); the Paper base in
`:paper-api` (`com.tricrotism.cryon.paper.api`).

**The loader** (`Cryon.kt`, `:paper`): on enable it first loads every jar in `plugins/Cryon/api/`
into **one shared `URLClassLoader`** (the cross-module contract layer — see below), then scans
`plugins/Cryon/modules/` for `*.jar` and loads **each in its own isolated `URLClassLoader`** whose
parent is that shared API loader (which itself chains to the core plugin's loader → Paper +
`:common`/`:paper-api` + kotlin-stdlib). It discovers `Module`s via `ServiceLoader`, then runs
`ModuleManager.loadAll(context)` → `enableAll()`. A broken jar is logged and skipped (caught as
`Throwable` — `ServiceConfigurationError` is an `Error`). Loaders are closed on disable (modules
before the shared parent). With an empty `api/` the shared layer is skipped — no behaviour change.

**Core types:**

- **`Module`** (`:common`) — `id` + a two-phase lifecycle: `onLoad(context)` (publish services),
  `onEnable()` (consume peers), `onDisable()`. Discovered by `ServiceLoader`, so impls need a
  **no-arg constructor** and a `META-INF/services/com.tricrotism.cryon.common.module.Module` entry.
- **`ModuleContext`** (`:common`) — handed to `onLoad`; carries `logger` + `services`. Paper's
  `PaperModuleContext` (`:paper-api`) extends it with `plugin`/`server`.
- **`ServiceRegistry`** (`:common`) — the intertwine seam. `register(Api::class, impl)` /
  `get<Api>()` / `find<Api>()`, keyed by interface.
- **`PaperModule`** (`:paper-api`) — base for Paper features. Exposes `plugin`/`server`/`services`/
  `logger`; `listen(listener)` auto-unregisters on disable. Override `onLoad` (call
  `super.onLoad(context)` first), `onEnable`, `onDisable` (call `super.onDisable()`).

**Isolation → intertwine only through shared interfaces.** Feature jars can't see each other's
classes (separate loaders). A feature exposes behaviour by registering an implementation of an
**API interface that lives in a shared artifact loaded by the common parent** — `:common`,
`:paper-api`, or a **contract jar dropped in `plugins/Cryon/api/`** (see **Cross-module contracts**).
Never reference another feature's concrete classes.

**Cross-module contracts — the `api/` layer.** When one feature needs to expose an API to *another
feature in a separate repo*, the contract type must be loaded by the **shared parent**, not bundled
in either feature jar (two bundled copies = two `Class` objects in two loaders = `ClassCastException`
through the registry). Mechanism: ship the interfaces in a thin **`*-api` jar** and drop it in
`plugins/Cryon/api/`; the loader puts every such jar on the one shared classloader that parents all
features, so they resolve the same type. Both the provider and the consumer depend on it
**`compileOnly`** (the runtime copy comes from `api/`). The provider registers
`services.register(FooService::class, impl)` in `onLoad`; the consumer resolves it with
`services.find(FooService::class)` in `onEnable` (use `find`, not `get` — an independent repo may not
be installed). The reference example is `Cryon-Modules/cryon-visibility-api` (contract) consumed by
`cryon-visibility` (impl) — a separate `cryon-spawn` would `compileOnly` the same api jar and add its
own `VisibilityRule`. Don't promote one-off, same-jar interfaces here; reserve `api/` for genuinely
cross-repo contracts. (For a contract you own and ship with the core anyway, `:paper-api` is still
fine — `api/` is for feature-defined contracts that shouldn't live in the core.)

**Order-independence:** `onLoad` runs for *every* module before any `onEnable`, so a peer's
services are always registered by the time you consume them in `onEnable`. No declared load order.

**Runtime management:** `ModuleManager` tracks a `ModuleState` per module
(`REGISTERED`/`LOADED`/`ENABLED`/`DISABLED`/`FAILED`) and supports `enable`/`disable`/`reload(id)` at
runtime — re-enabling reuses the context captured at load. The built-in **`/cryon`** command surfaces
this (`/cryon modules|info|enable|disable|reload <id>`). It's lifecycle-only — no jar reload. The
manager is registered into the `ServiceRegistry`, so a module reads its own live state via
`PaperModule.isEnabled()`.

**Commands track module state.** A `@Command` class registered through `PaperModule.registerCommands(…)`
is gated on `isEnabled()` (the helper passes it as the `available` guard to `AnnotationCommands`), so
a Brigadier command can't be run — or tab-completed — while its module is disabled, and reappears on
re-enable, all without re-registering (the guard is re-evaluated per dispatch). `/cryon enable|disable|reload`
calls `Player.updateCommands()` so clients resync immediately. A disabled command shows the vanilla
"unknown command" rather than a styled message; that's the trade-off for gating at the Brigadier layer.

**Authoring a feature** (see `Cryon-Modules/cryon-example-feature/`): new repo → `compileOnly` the
published `:common` + `:paper-api` (+ Paper API) → `class Foo : PaperModule()` with a no-arg ctor →
add the `META-INF/services` entry → build the thin `jar` → drop into `plugins/Cryon/modules/`.

When you add the `:velocity` loader, give it its own `ModuleManager` + `VelocityModuleContext` over
the same `:common` contract, and document it here.

---

## Utilities

Shared helpers features build on. **Check these before writing your own** — the Kotlin-style rule
(prefer existing extensions) applies. Reach a peer feature's behaviour through the `ServiceRegistry`,
not these.

**Numbers (`:common` `…common.number` + `…common.extension`):**

- `CryonNumber` — effectively-unbounded scaling value (`double` mantissa + `long` exponent) for
  currencies/idle math: fast, ~15–16 sig figs. Operators (`+ - * /`, `compareTo`), `pow(Int)`/
  `pow(Double)`, `sqrt`/`cbrt`, `log10`/`ln`/`log2`/`log(base)` (return `Double`), `abs`/`reciprocal`/
  `min`/`max`, `of(...)`/`tenPow(...)`. Use it (not `BigDecimal`) for anything that can grow past
  `~1e15` on a hot path.
- `LongUtils` (`parseLong`, `parseLongShorthand`), `BigDecimalUtils` (`magnitude`, `log10` — safe
  past `Double` range), `NumberUtils` (`formatBalance`/`formatCommas`/`roman`/`parseBalance`,
  thread-safe formatters), and primitive extensions (`1500L.formatBalance()`, `5.cn`, `n.formatCommas()`).

**Text / messages (`:common` `…common.text`):**

- `Mini` — the project's MiniMessage: non-strict, preloaded with the palette tags, a ~15s Caffeine
  deserialize cache, and legacy (`§`) interop (`toLegacy`/`toComponent`/`stripFormatting`). Use
  `Mini.format(...)` (or `"…".mm()` on Paper), **never** `MiniMessage.miniMessage()`.
- `CryonPalette` — the full named colour set as `TextColor`s **and** tags (`<off_white>`, `<scarlet>`,
  semantic `<error>`/`<success>`/`<info>`/`<warning>`, …). Tune hexes here; add project-specific
  inserting tags by extending `RESOLVER`.
- `CommonMessages` — prefixed acks (`error`/`success`/`info`/`warn`, `errorPlayer`, `notOnline`,
  `notEnoughCurrency`, `noPermission`, `alert`) returning `Component`s. The prefix is a **bold
  coloured icon** per `MessageType` (`✖`/`✔`/`✦`/`⚠`) — language-neutral, so generic acks take no
  locale (`audience.sendError("…")`). The **canned phrases are localized**: bodies resolve through
  `Messages` by `cryon.common.*` key with English inline as fallback, and take a `Locale`; the Paper
  canned extensions (`player.sendNoPermission()`, `player.sendNotEnough(currency)`, …) pass
  `player.resolvedLocale()`. Tune icons/colours in `MessageType`.

**i18n (`:common` `…common.locale`):** **everything user-facing is localizable.** `MessageService`
resolves `(locale, key) → Component` across registered `MessageSource`s with a fallback chain, count
pluralization (`renderPlural`), and hot `reload()`. `Messages` is the static facade (`get`/`getOr`/
`rawOr`) the core installs so `CommonMessages`/`MessageType` localize without a handle.

**Auto-scanner — don't register sources by hand.** The loader auto-discovers bundles:

- a feature jar's `lang/<locale>.properties` is registered when the jar loads (`LangScanner.fromJar`);
- `plugins/Cryon/lang/<locale>.properties` (`DirectoryMessageSource`) is registered first, so admin
  overrides win over jar defaults; both English fallbacks lose to either.

So: ship `lang/en_US.properties` etc. in your feature jar and they just work. Send in a player's
locale with `messages.send(player, key, …)`, or localized **and** prefix-styled with
`messages.send(player, MessageType.ERROR, key, …)`. Override the built-in `cryon.common.*` keys in
any bundle. Missing keys render `⟨key⟩`, never silent.

**Items (`:paper-api` `…paper.api.item` + `…extension`):** `ItemBuilder` — name/lore (auto `<!i>`,
palette-parsed), flags, glow, `enchant`, attributes, PDC `tag`s, `meta {}` escape hatch. Extensions:
`Material.toItem()`, `ItemStack.toBuilder()`/`modify { }` (edit existing stacks), `getTag`/`setTag`/
`hasTag`/`removeTag` (PDC), `isEmpty()`, `withAmount()`.

**Scheduling (`:paper-api` `…paper.api.scheduler`):** `Schedulers` wraps Paper's threaded-region
schedulers — `global`/`region(loc)`/`entity(e)`/`async`, each with `*Later`/`*Timer`. Pick the scope
owning the data; never the global main thread for world/entity work. No Bukkit API in `async`.

**Events (`:paper-api` `…paper.api.event`):** `Events.subscribe(Type::class.java, priority).filter{…}
.handler{…}` returns a cancellable `Subscription`; `expireAfter(n)` self-unregisters. Handler
exceptions are logged, not propagated.

**Commands — annotation framework over Paper Brigadier** (`:paper-api` `…paper.api.command`).
**Cloud does not work on this Paper build** — cloud-bukkit's `ItemStackParser` reflects for an
`ItemInput` method that doesn't exist on 26.2, so any cloud manager throws at construction. Instead
we ship a thin reflection-based `@Command` layer registered through Paper's native Brigadier
`Commands` registrar (via `LifecycleEvents.COMMANDS`). **Never `plugin.yml` `commands:` /
`CommandMap` / `Commands.create()` / Cloud.**

```kotlin
@Command("cryon", "Module manager")
@Permission("cryon.admin")
class ModuleCommands(private val modules: ModuleManager) {
    @Subcommand fun overview(sender: CommandSender) = list(sender)               // /cryon
    @Subcommand("enable")
    fun enable(sender: CommandSender, @Arg("id", suggests = "ids") id: String) { … } // /cryon enable <id>
    fun ids(): Collection<String> = modules.ids()                               // tab-complete suggester
}
// core: AnnotationCommands.register(event.registrar(), ModuleCommands(manager), …)
```

`@Command`/`@Subcommand`/`@Permission`/`@Arg(name, suggests)`/`@Greedy`. The `CommandSender` param is
injected; `@Arg` names the Brigadier argument (types `String`/`Int`/`Boolean`) and a `suggests`
method gives completions. Built-ins: **`/cryon`** (module manager, `cryon.admin`) and **`/language`**
(`/lang`). Bodies wrap localized content in `CommonMessages` for the icon prefix. **Feature modules
register via `PaperModule.registerCommands(handler…)` in `onLoad`**, not the raw lifecycle call — the
helper registers in the COMMANDS window and passes `register(registrar, handler, available = ::isEnabled)`
so the command is gated on the module being enabled (see **Module System → Commands track module state**).
The core's own commands use the plain `register(registrar, vararg handlers)` (always available).

**Cross-server infra (`:common` `…common.data` / `…common.net`):** opt-in, config-gated.

- `Database` (`PostgresDatabase`, HikariCP) — async SQL: `query(sql, …) { rs -> }` / `update(sql, …)`
  return `CompletableFuture`s off the main thread. No ORM; run your own SQL. **Never block the main
  thread** — these already hop off it; don't `.get()` on the main thread.
- `Messenger` (`RedisMessenger`, Lettuce) — cross-server `publish`/`subscribe` + request/response
  (`request`/`handle`). String payloads; encode richer data yourself.
- Both are registered into the `ServiceRegistry` when enabled (`services.find<Database>()` /
  `find<Messenger>()` — use `find`, they may be absent). Client libs aren't shaded; they load at
  runtime via `plugin.yml` `libraries:`. Config lives in the core's `config.yml`
  (`database.*`, `redis.*`), both `enabled: false` by default — Cryon boots fine without them.

**Player locale — persistent & cross-server.** Resolution is **`Player.resolvedLocale()` = stored
override ?: client `locale()`**; all message helpers use it. The client locale is automatic and
already consistent across servers (the client reports it). A **chosen override** (`player.setLanguage(de)`)
persists to SQL and broadcasts a Redis invalidation so every server updates; `PlayerLocaleStore`
caches it in memory (loaded on join) for synchronous reads. The store is a `LocaleStore` interface:
the core installs `PlayerLocaleStore` when SQL+Redis are configured, else a `MemoryLocaleStore` —
overrides still work via `/language`, but per-server and **reset on restart** (no persistence, no
cross-server sync). Either way a store is always installed; resolution falls back to the client
locale for anyone without an override.

### Architecture — what doesn't exist yet

No DI container, codegen, menu framework, or coroutine bridge. (Persistence, cross-server messaging,
the `@Command` framework, and a `config.yml` now exist — see above.) The core registers its own
commands today; letting **feature modules** contribute `@Command` classes (the core collecting them
in the `LifecycleEvents.COMMANDS` handler) is the natural next step. As the network grows, add the
relevant infrastructure **and document it here in the same pass** — keep this guide and the code in
lockstep.

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
| `CryonNumber` for values that grow past ~1e15            | `BigDecimal` on hot incremental-math paths                     |
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
