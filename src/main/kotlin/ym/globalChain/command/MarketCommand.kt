package ym.globalchain.command

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import ym.globalchain.GlobalChain
import ym.globalchain.service.ServiceFeedback
import java.util.UUID

class MarketCommand(private val plugin: GlobalChain) : TabExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            requirePlayer(sender) { player ->
                plugin.runtime()?.marketGuiService?.openGlobal(player) ?: plugin.sendMessage(sender, "messages.booting")
            }
            return true
        }
        if (args[0].equals("reload", true)) {
            if (!sender.hasPermission("globalchain.admin")) {
                plugin.sendMessage(sender, "messages.no-permission")
                return true
            }
            val future = plugin.bootstrapRuntime()
            if (future == null) {
                plugin.sendMessage(sender, "messages.reload-busy")
                return true
            }
            plugin.sendMessage(sender, "messages.reload-started")
            future.whenComplete { _, error ->
                if (error != null) {
                    plugin.sendMessage(sender, "messages.reload-failed")
                    return@whenComplete
                }
                plugin.sendMessage(sender, "messages.reload-complete")
            }
            return true
        }
        val runtime = plugin.runtime()
        if (runtime == null) {
            plugin.sendMessage(sender, "messages.booting")
            return true
        }

        when (args[0].lowercase()) {
            "balance" -> requirePlayer(sender) { player ->
                runtime.marketService.balance(player).whenComplete { feedback, error -> deliverFeedback(sender, feedback, error) }
            }
            "open", "browse" -> requirePlayer(sender) { player ->
                runtime.marketGuiService.openGlobal(player)
            }
            "mine" -> requirePlayer(sender) { player ->
                runtime.marketGuiService.openMine(player)
            }
            "mailbox" -> requirePlayer(sender) { player ->
                runtime.marketGuiService.openMailbox(player)
            }
            "sell" -> requirePlayer(sender) { player ->
                val price = args.getOrNull(1)
                if (price == null) {
                    runtime.marketGuiService.openGlobal(player)
                    return@requirePlayer
                }
                runtime.marketService.sell(player, price).whenComplete { feedback, error -> deliverFeedback(sender, feedback, error) }
            }
            "buy" -> requirePlayer(sender) { player ->
                val id = args.getOrNull(1)
                if (id == null) {
                    runtime.marketGuiService.openGlobal(player)
                    return@requirePlayer
                }
                if (id.length < runtime.config.market.commandIdPrefixMinLength) {
                    plugin.sendMessage(sender, "messages.listing-id-ambiguous")
                    return@requirePlayer
                }
                runtime.marketService.buy(player, id).whenComplete { feedback, error -> deliverFeedback(sender, feedback, error) }
            }
            "cancel" -> requirePlayer(sender) { player ->
                val id = args.getOrNull(1)
                if (id == null) {
                    runtime.marketGuiService.openMine(player)
                    return@requirePlayer
                }
                if (id.length < runtime.config.market.commandIdPrefixMinLength) {
                    plugin.sendMessage(sender, "messages.listing-id-ambiguous")
                    return@requirePlayer
                }
                runtime.marketService.cancel(player, id).whenComplete { feedback, error -> deliverFeedback(sender, feedback, error) }
            }
            "claim" -> requirePlayer(sender) { player ->
                val id = args.getOrNull(1)
                if (id != null && id.length < runtime.config.market.commandIdPrefixMinLength) {
                    plugin.sendMessage(sender, "messages.claim-id-ambiguous")
                    return@requirePlayer
                }
                runtime.marketService.claim(player, id).whenComplete { feedback, error -> deliverFeedback(sender, feedback, error) }
            }
            "grant" -> {
                if (!sender.hasPermission("globalchain.admin")) {
                    plugin.sendMessage(sender, "messages.no-permission")
                    return true
                }
                val targetArg = args.getOrNull(1)
                val amountArg = args.getOrNull(2)
                if (targetArg == null || amountArg == null) {
                    plugin.sendComponents(sender, runtime.language.renderLines("messages.help"))
                    return true
                }
                val target = resolveTarget(targetArg)
                if (target == null) {
                    plugin.sendMessage(sender, "messages.internal-error")
                    return true
                }
                runtime.walletService.grant(target.first, target.second, amountArg).whenComplete { feedback, error -> deliverFeedback(sender, feedback, error) }
            }
            else -> plugin.sendComponents(sender, runtime.language.renderLines("messages.help"))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
        return when (args.size) {
            1 -> mutableListOf("open", "browse", "mine", "mailbox", "balance", "sell", "buy", "cancel", "claim", "grant", "reload").filter { it.startsWith(args[0], true) }.toMutableList()
            2 -> when (args[0].lowercase()) {
                "grant" -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) }.toMutableList()
                else -> mutableListOf()
            }
            else -> mutableListOf()
        }
    }

    private fun deliverFeedback(sender: CommandSender, feedback: ServiceFeedback?, throwable: Throwable?) {
        if (throwable != null) {
            plugin.sendError(sender, throwable)
            return
        }
        if (feedback == null) {
            return
        }
        plugin.sendFeedback(sender, feedback)
    }

    private fun requirePlayer(sender: CommandSender, block: (Player) -> Unit) {
        if (sender !is Player) {
            plugin.sendMessage(sender, "messages.player-only")
            return
        }
        block(sender)
    }

    private fun resolveTarget(raw: String): Pair<UUID, String>? {
        Bukkit.getPlayerExact(raw)?.let { return it.uniqueId to it.name }
        return runCatching {
            val uuid = UUID.fromString(raw)
            uuid to uuid.toString()
        }.getOrNull()
    }
}
