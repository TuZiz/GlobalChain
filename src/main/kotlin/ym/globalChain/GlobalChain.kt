package ym.globalchain

import net.kyori.adventure.platform.bukkit.BukkitAudiences
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin
import ym.globalchain.command.MarketCommand
import ym.globalchain.config.PluginConfig
import ym.globalchain.config.PluginConfigLoader
import ym.globalchain.config.ResourceBootstrap
import ym.globalchain.data.MarketRepository
import ym.globalchain.gui.MarketInventoryHolder
import ym.globalchain.data.MarketRepositoryFactory
import ym.globalchain.gui.MarketGuiService
import ym.globalchain.lang.LanguageService
import ym.globalchain.listener.MarketGuiListener
import ym.globalchain.listener.PendingItemGuardListener
import ym.globalchain.listener.PlayerLifecycleListener
import ym.globalchain.listener.PromptInputListener
import ym.globalchain.menu.MenuLoader
import ym.globalchain.menu.MenuRegistry
import ym.globalchain.platform.PlatformScheduler
import ym.globalchain.service.MarketService
import ym.globalchain.service.RecoveryService
import ym.globalchain.service.ServiceFeedback
import ym.globalchain.service.WalletService
import ym.globalchain.util.MarketKeys
import ym.globalchain.util.TrackedItemService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level

class GlobalChain : JavaPlugin() {

    private val runtimeRef = AtomicReference<PluginRuntime?>()
    private val booting = AtomicBoolean(false)
    private lateinit var bootstrapExecutor: ExecutorService
    private lateinit var audiences: BukkitAudiences
    private lateinit var platformScheduler: PlatformScheduler
    private lateinit var marketKeys: MarketKeys
    private lateinit var trackedItemService: TrackedItemService

    override fun onEnable() {
        bootstrapExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "GlobalChain-Bootstrap").apply { isDaemon = true }
        }
        audiences = BukkitAudiences.create(this)
        platformScheduler = PlatformScheduler(this)
        marketKeys = MarketKeys(this)
        trackedItemService = TrackedItemService(marketKeys)

        val command = MarketCommand(this)
        getCommand("gmarket")?.setExecutor(command)
        getCommand("gmarket")?.tabCompleter = command
        server.pluginManager.registerEvents(PendingItemGuardListener(this, trackedItemService), this)
        server.pluginManager.registerEvents(PlayerLifecycleListener(this), this)
        server.pluginManager.registerEvents(MarketGuiListener(this), this)
        server.pluginManager.registerEvents(PromptInputListener(this), this)

        bootstrapRuntime()
    }

    override fun onDisable() {
        HandlerList.unregisterAll(this)
        runtimeRef.getAndSet(null)?.close()
        if (::audiences.isInitialized) {
            audiences.close()
        }
        if (::bootstrapExecutor.isInitialized) {
            bootstrapExecutor.shutdownNow()
        }
    }

    fun trackedItems(): TrackedItemService = trackedItemService

    fun scheduler(): PlatformScheduler = platformScheduler

    fun runtime(): PluginRuntime? = runtimeRef.get()

    fun isReady(): Boolean = runtimeRef.get() != null

    fun bootstrapRuntime(): CompletableFuture<PluginRuntime>? {
        if (!booting.compareAndSet(false, true)) {
            return null
        }
        return CompletableFuture
            .supplyAsync({
                ResourceBootstrap.ensureDefaults(this, dataFolder.toPath())
                val pluginConfig = PluginConfigLoader.load(this, dataFolder.toPath())
                val language = LanguageService.load(dataFolder.toPath(), pluginConfig.language)
                val menuRegistry = MenuRegistry(MenuLoader.loadAll(dataFolder.toPath().resolve("menus")))
                val repository = MarketRepositoryFactory.create(dataFolder.toPath(), pluginConfig, trackedItemService)
                val economy = setupEconomy()
                val walletService = WalletService(pluginConfig, economy)
                val marketService = MarketService(this, pluginConfig, repository, walletService, trackedItemService)
                val recoveryService = RecoveryService(this, pluginConfig.market.recoveryCheckIntervalSeconds, repository, walletService, trackedItemService)
                val marketGuiService = MarketGuiService(this, pluginConfig, language, menuRegistry, marketService, walletService)
                PluginRuntime(
                    config = pluginConfig,
                    language = language,
                    menuRegistry = menuRegistry,
                    repository = repository,
                    walletService = walletService,
                    marketService = marketService,
                    recoveryService = recoveryService,
                    marketGuiService = marketGuiService,
                )
            }, bootstrapExecutor)
            .whenComplete { runtime, error ->
                booting.set(false)
                if (error != null) {
                    logger.log(Level.SEVERE, "Failed to bootstrap GlobalChain runtime.", error)
                    return@whenComplete
                }
                val previous = runtimeRef.getAndSet(runtime)
                previous?.close()
                logger.info("GlobalChain runtime initialized for server-id=${runtime.config.serverId}.")
                platformScheduler.runGlobal {
                    Bukkit.getOnlinePlayers().forEach { player ->
                        val holder = player.openInventory.topInventory.holder as? MarketInventoryHolder
                        if (holder != null && holder.menuId != "loading") {
                            runtime.marketGuiService.openMenu(player, holder.menuId)
                        }
                        runtime.recoveryService.reconcilePlayer(player)
                    }
                }
            }
    }

    fun sendMessage(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        val runtime = runtime()
        if (runtime == null) {
            dispatchToSender(sender) {
                sender.sendMessage("GlobalChain is still booting.")
            }
            return
        }
        sendComponents(sender, runtime.language.renderLines(key, placeholders))
    }

    fun sendFeedback(sender: CommandSender, feedback: ServiceFeedback) {
        val runtime = runtime()
        if (runtime == null) {
            dispatchToSender(sender) {
                sender.sendMessage("GlobalChain is still booting.")
            }
            return
        }
        sendComponents(sender, runtime.language.renderLines(feedback.key, feedback.placeholders, feedback.componentPlaceholders))
    }

    fun sendComponents(sender: CommandSender, components: List<net.kyori.adventure.text.Component>) {
        dispatchToSender(sender) {
            audiences.sender(sender).sendMessage(components.first())
            if (components.size > 1) {
                components.drop(1).forEach { audiences.sender(sender).sendMessage(it) }
            }
        }
    }

    fun sendError(sender: CommandSender, error: Throwable) {
        logger.log(Level.SEVERE, "GlobalChain operation failed.", error)
        sendMessage(sender, "messages.internal-error")
    }

    fun dispatchToSender(sender: CommandSender, task: () -> Unit) {
        if (sender is Player) {
            platformScheduler.runOnPlayer(sender, task)
        } else {
            platformScheduler.runGlobal(task)
        }
    }

    private fun setupEconomy(): Economy {
        checkNotNull(server.pluginManager.getPlugin("Vault")) { "Vault plugin is required for GlobalChain." }
        val registration = server.servicesManager.getRegistration(Economy::class.java)
        checkNotNull(registration?.provider) { "No Vault economy provider is registered." }
        return registration.provider
    }
}

data class PluginRuntime(
    val config: PluginConfig,
    val language: LanguageService,
    val menuRegistry: MenuRegistry,
    val repository: MarketRepository,
    val walletService: WalletService,
    val marketService: MarketService,
    val recoveryService: RecoveryService,
    val marketGuiService: MarketGuiService,
) : AutoCloseable {

    override fun close() {
        recoveryService.close()
        walletService.close()
        repository.close()
    }
}
