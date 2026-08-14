package com.tricrotism.cryon.command

import com.github.benmanes.caffeine.cache.Caffeine
import com.tricrotism.cryon.common.currency.Currency
import com.tricrotism.cryon.common.currency.CurrencyService
import com.tricrotism.cryon.common.currency.TransferResult
import com.tricrotism.cryon.common.locale.MessageService
import com.tricrotism.cryon.common.number.NumberUtils
import com.tricrotism.cryon.common.number.PackedDecimal
import com.tricrotism.cryon.common.text.CommonMessages
import com.tricrotism.cryon.paper.api.command.Arg
import com.tricrotism.cryon.paper.api.command.Command
import com.tricrotism.cryon.paper.api.command.Permission
import com.tricrotism.cryon.paper.api.command.Subcommand
import com.tricrotism.cryon.paper.api.extension.render
import com.tricrotism.cryon.paper.api.scheduler.Schedulers
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.TimeUnit

internal fun MessageService.say(
    sender: CommandSender,
    key: String,
    vararg resolvers: TagResolver,
): Component =
    if (sender is Player) render(sender, key, *resolvers) else render(defaultLocale, key, *resolvers)

/**
 * Shared plumbing for the currency commands.
 *
 * The menu-closing is the part worth explaining. A player standing in a shop GUI is looking at a
 * balance that was rendered when the menu opened, and its buttons were laid out against that number.
 * The moment a command moves their money, every one of those buttons is describing a balance that no
 * longer exists. Click one and it either fails confusingly or, if that menu ever decided anything
 * from the rendered figure rather than from [CurrencyService.withdraw], buys something already paid
 * for. Closing is the cheap, total fix: the next open re-reads.
 *
 * It runs on the affected player's own entity scheduler, because closing an inventory is entity work
 * and the player may well be on another region thread than whoever typed the command.
 */
/**
 * Run [block] on [sender]'s own thread.
 *
 * Every reply in this file lands in a `CompletableFuture` continuation, which runs on whichever
 * thread the database or messenger finished on. Rendering a line resolves the viewer's locale and
 * sending it touches their connection, so both belong on that player's region thread. Console has no
 * region and is safe to write from anywhere.
 */
internal fun reply(sender: CommandSender, block: () -> Unit) {
    if (sender is Player) Schedulers.entity(sender) { block() } else block()
}

internal object CurrencyCommandSupport {

    /** Close [player]'s open menu because their balance just moved under it. */
    fun closeMenu(player: Player) {
        Schedulers.entity(player) { player.closeInventory() }
    }

    fun closeMenu(player: UUID) {
        Bukkit.getPlayer(player)?.let(::closeMenu)
    }

    /** Parse an amount the way players write one (`1k`, `2.5m`), rejecting anything not positive. */
    fun amountOf(input: String): PackedDecimal? = parse(input)?.takeIf { it.signum() > 0 }

    /** As [amountOf], but zero is allowed, for `/currency set`. */
    fun nonNegativeAmountOf(input: String): PackedDecimal? = parse(input)?.takeIf { it.signum() >= 0 }

    private fun parse(input: String): PackedDecimal? =
        runCatching { PackedDecimal.of(NumberUtils.parseBalance(input)) }.getOrNull()

}

/**
 * `/balance [player]`: every registered currency at once.
 *
 * Read-only, so it does not close anything: nothing it shows can be acted on from here.
 */
@Command("balance", "See your balances", "bal", "balances")
class BalanceCommand(
    private val currencies: CurrencyService,
    private val messages: MessageService,
) {

    /**
     * Per-player throttle. Reading balances is one query per scope with no cache in front of it, so
     * a held-down macro turns into a steady stream of queries against the pool that real money
     * operations share. The failure is not a wrong balance, it is a withdrawal waiting behind a
     * hundred `/bal`s for a connection. Self-evicting, so it cannot grow into a per-player leak.
     */
    private val recent = Caffeine.newBuilder()
        .expireAfterWrite(COOLDOWN_SECONDS, TimeUnit.SECONDS)
        .build<UUID, Boolean>()

    private fun throttled(sender: CommandSender): Boolean {
        val player = sender as? Player ?: return false
        if (recent.getIfPresent(player.uniqueId) != null) return true
        recent.put(player.uniqueId, true)
        return false
    }

    @Subcommand
    fun self(sender: CommandSender) {
        val player = sender as? Player ?: return
        if (throttled(player)) return
        show(player, player.uniqueId, player.name)
    }

    @Subcommand
    @Permission("cryon.currency.balance.others")
    fun other(sender: CommandSender, @Arg("player", suggests = "players") name: String) {
        if (throttled(sender)) return
        val target = Bukkit.getPlayerExact(name)
        if (target == null) {
            sender.sendMessage(CommonMessages.error(messages.say(sender, "currency.no_player")))
            return
        }
        show(sender, target.uniqueId, target.name)
    }

    private fun show(sender: CommandSender, player: UUID, name: String) {
        currencies.balances(player).thenAccept { balances ->
            reply(sender) {
                if (balances.isEmpty()) {
                    sender.sendMessage(CommonMessages.info(messages.say(sender, "currency.none")))
                    return@reply
                }
                sender.sendMessage(
                    CommonMessages.info(
                        messages.say(sender, "currency.header", Placeholder.unparsed("player", name))
                    )
                )
                for ((currency, balance) in balances.entries.sortedBy { it.key.id }) {
                    sender.sendMessage(
                        messages.say(
                            sender, "currency.row",
                            Placeholder.unparsed("currency", currency.displayName),
                            Placeholder.unparsed("balance", currency.format(balance)),
                        )
                    )
                }
            }
        }.exceptionally {
            reply(sender) { sender.sendMessage(CommonMessages.error(messages.say(sender, "currency.failed"))) }
            null
        }
    }

    @Suppress("unused")
    fun players(): Collection<String> = Bukkit.getOnlinePlayers().map { it.name }

    private companion object {
        const val COOLDOWN_SECONDS = 2L
    }
}

/**
 * `/pay <player> <currency> <amount>`: hand currency to someone else.
 *
 * The transfer itself is atomic and holds both accounts; see `CurrencyService.transfer`. Nothing here
 * reads a balance to decide anything. The [TransferResult] the transfer answers with *is* the
 * decision, and each of its values gets its own reply: "you were short" and "the store is down"
 * are different facts, and only one of them is the sender's fault.
 */
@Command("pay", "Pay another player")
@Permission("cryon.currency.pay")
class PayCommand(
    private val currencies: CurrencyService,
    private val messages: MessageService,
) {

    @Subcommand
    fun pay(
        sender: CommandSender,
        @Arg("player", suggests = "players") name: String,
        @Arg("currency", suggests = "currencies") currencyId: String,
        @Arg("amount") amount: String,
    ) {
        val player = sender as? Player ?: return
        CurrencyCommandSupport.closeMenu(player)

        val currency = currencies.currency(currencyId)
        if (currency == null) {
            CommandUi.unknown(player, "currency", currencyId, currencies.all().map { it.id }) {
                "/pay $name $it $amount"
            }
            return
        }
        val value = CurrencyCommandSupport.amountOf(amount)
        if (value == null) {
            player.sendMessage(
                CommonMessages.error(
                    messages.render(player, "currency.bad_amount", Placeholder.unparsed("amount", amount))
                )
            )
            return
        }
        val target = Bukkit.getPlayerExact(name)
        if (target == null) {
            CommandUi.unknown(player, "player", name, onlineNamesExcept(player)) {
                "/pay $it $currencyId $amount"
            }
            return
        }
        if (target.uniqueId == player.uniqueId) {
            player.sendMessage(CommonMessages.error(messages.render(player, "currency.self")))
            return
        }

        val recipient = target.uniqueId
        currencies.transfer(currency, player.uniqueId, recipient, value, "pay").thenAccept { result ->
            Schedulers.entity(player) {
                if (result != TransferResult.COMPLETED) {
                    val key =
                        if (result == TransferResult.INSUFFICIENT) "currency.insufficient" else "currency.failed"
                    player.sendMessage(
                        CommonMessages.error(
                            messages.render(
                                player, key,
                                Placeholder.unparsed("currency", currency.displayName),
                                Placeholder.unparsed("amount", currency.format(value)),
                            )
                        )
                    )
                    player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 0.8f)
                    return@entity
                }
                player.sendMessage(
                    CommonMessages.success(
                        messages.render(
                            player, "currency.paid",
                            Placeholder.unparsed("amount", currency.format(value)),
                            Placeholder.unparsed("player", target.name),
                        )
                    )
                )
                player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f)
            }
            if (result != TransferResult.COMPLETED) return@thenAccept
            CurrencyCommandSupport.closeMenu(recipient)
            Bukkit.getPlayer(recipient)?.let { online ->
                Schedulers.entity(online) {
                    online.sendMessage(
                        CommonMessages.success(
                            messages.render(
                                online, "currency.received",
                                Placeholder.unparsed("amount", currency.format(value)),
                                Placeholder.unparsed("player", player.name),
                            )
                        )
                    )
                }
            }
        }.exceptionally {
            Schedulers.entity(player) {
                player.sendMessage(CommonMessages.error(messages.render(player, "currency.failed")))
            }
            null
        }
    }

    @Suppress("unused")
    fun players(): Collection<String> = Bukkit.getOnlinePlayers().map { it.name }

    @Suppress("unused")
    fun currencies(): Collection<String> = currencies.all().map { it.id }

    /** Paying yourself is the one "unknown player" that is really a typo for somebody else. */
    private fun onlineNamesExcept(player: Player): List<String> =
        Bukkit.getOnlinePlayers().filter { it.uniqueId != player.uniqueId }.map { it.name }
}

/**
 * `/currency <list|top|give|take|set>`: administration.
 *
 * Every mutating branch closes the affected player's menu for the reason in
 * [CurrencyCommandSupport], and reports the store's own answer rather than assuming it worked.
 */
@Command("currency", "Inspect and administer currencies", "cur", "eco")
@Permission("cryon.currency.admin")
class CurrencyAdminCommands(
    private val currencies: CurrencyService,
    private val messages: MessageService,
) {

    @Subcommand("list")
    fun list(sender: CommandSender) {
        val all = currencies.all().sortedBy { it.id }
        if (all.isEmpty()) {
            sender.sendMessage(CommonMessages.info(messages.say(sender, "currency.none_registered")))
            return
        }
        for (currency in all) {
            sender.sendMessage(
                messages.say(
                    sender, "currency.list_row",
                    Placeholder.unparsed("currency", currency.id),
                    Placeholder.unparsed("name", currency.displayName),
                    Placeholder.unparsed("scope", currency.scope.name.lowercase()),
                    Placeholder.unparsed("ranked", (currency.leaderboard != null).toString()),
                )
            )
        }
    }

    @Subcommand("top")
    fun top(sender: CommandSender, @Arg("currency", suggests = "currencies") currencyId: String) {
        val currency = resolve(sender, currencyId) { "/currency top $it" } ?: return
        if (currency.leaderboard == null) {
            sender.sendMessage(
                CommonMessages.error(
                    messages.say(
                        sender,
                        "currency.no_leaderboard",
                        Placeholder.unparsed("currency", currency.displayName)
                    )
                )
            )
            return
        }
        val rankings = currencies.leaderboard(currency)
        if (rankings.isEmpty()) {
            sender.sendMessage(CommonMessages.info(messages.say(sender, "currency.empty_leaderboard")))
            return
        }
        sender.sendMessage(
            CommonMessages.info(
                messages.say(sender, "currency.top_header", Placeholder.unparsed("currency", currency.displayName))
            )
        )
        for (ranking in rankings) {
            val name = Bukkit.getPlayer(ranking.player)?.name ?: ranking.player.toString().take(8)
            sender.sendMessage(
                messages.say(
                    sender, "currency.top_row",
                    Placeholder.unparsed("position", ranking.position.toString()),
                    Placeholder.unparsed("player", name),
                    Placeholder.unparsed("balance", currency.format(ranking.balance)),
                )
            )
        }
    }

    @Subcommand("give")
    fun give(
        sender: CommandSender,
        @Arg("player", suggests = "players") name: String,
        @Arg("currency", suggests = "currencies") currencyId: String,
        @Arg("amount") amount: String,
    ) = mutate(sender, "give", name, currencyId, amount) { currency, target, value ->
        currencies.deposit(currency, target, value, "admin:${sender.name}").thenApply { true }
    }

    @Subcommand("take")
    fun take(
        sender: CommandSender,
        @Arg("player", suggests = "players") name: String,
        @Arg("currency", suggests = "currencies") currencyId: String,
        @Arg("amount") amount: String,
    ) = mutate(sender, "take", name, currencyId, amount) { currency, target, value ->
        currencies.withdraw(currency, target, value, "admin:${sender.name}")
    }

    @Subcommand("set")
    fun set(
        sender: CommandSender,
        @Arg("player", suggests = "players") name: String,
        @Arg("currency", suggests = "currencies") currencyId: String,
        @Arg("amount") amount: String,
    ) = mutate(sender, "set", name, currencyId, amount, allowZero = true) { currency, target, value ->
        currencies.set(currency, target, value, "admin:${sender.name}").thenApply { true }
    }

    /** Shared shape: resolve, close the target's menu, apply, then report what actually happened. */
    private fun mutate(
        sender: CommandSender,
        verb: String,
        name: String,
        currencyId: String,
        amount: String,
        allowZero: Boolean = false,
        action: (Currency, UUID, PackedDecimal) -> java.util.concurrent.CompletableFuture<Boolean>,
    ) {
        val currency = resolve(sender, currencyId) { "/currency list" } ?: return
        val value =
            if (allowZero) CurrencyCommandSupport.nonNegativeAmountOf(amount)
            else CurrencyCommandSupport.amountOf(amount)
        if (value == null) {
            sender.sendMessage(
                CommonMessages.error(
                    messages.say(sender, "currency.bad_amount", Placeholder.unparsed("amount", amount))
                )
            )
            return
        }
        val target = Bukkit.getPlayerExact(name)
        if (target == null) {
            CommandUi.unknown(sender, "player", name, Bukkit.getOnlinePlayers().map { it.name }) {
                "/currency $verb $it $currencyId $amount"
            }
            return
        }
        val id = target.uniqueId
        CurrencyCommandSupport.closeMenu(target)
        action(currency, id, value).thenAccept { applied ->
            reply(sender) {
                val key = if (applied) "currency.admin_applied" else "currency.admin_refused"
                val line = messages.say(
                    sender, key,
                    Placeholder.unparsed("amount", currency.format(value)),
                    Placeholder.unparsed("player", target.name),
                )
                sender.sendMessage(if (applied) CommonMessages.success(line) else CommonMessages.error(line))
            }
        }.exceptionally {
            reply(sender) { sender.sendMessage(CommonMessages.error(messages.say(sender, "currency.failed"))) }
            null
        }
    }

    private fun resolve(sender: CommandSender, id: String, retry: (String) -> String): Currency? {
        val currency = currencies.currency(id)
        if (currency == null) CommandUi.unknown(sender, "currency", id, currencies.all().map { it.id }, retry)
        return currency
    }

    @Suppress("unused")
    fun players(): Collection<String> = Bukkit.getOnlinePlayers().map { it.name }

    @Suppress("unused")
    fun currencies(): Collection<String> = currencies.all().map { it.id }
}
