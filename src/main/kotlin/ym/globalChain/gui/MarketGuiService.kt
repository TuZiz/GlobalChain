package ym.globalchain.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import ym.globalchain.GlobalChain
import ym.globalchain.config.PluginConfig
import ym.globalchain.lang.LanguageService
import ym.globalchain.menu.MenuButtonDefinition
import ym.globalchain.menu.MenuDefinition
import ym.globalchain.menu.MenuRegistry
import ym.globalchain.model.ListingPage
import ym.globalchain.model.ListingSort
import ym.globalchain.model.ListingSummary
import ym.globalchain.model.MailboxPage
import ym.globalchain.model.SaleDraft
import ym.globalchain.service.MarketService
import ym.globalchain.service.ServiceFeedback
import ym.globalchain.service.WalletService
import ym.globalchain.util.CompatibilityDebug
import ym.globalchain.util.ItemNaming
import ym.globalchain.util.ItemStackCodec
import ym.globalchain.util.MoneyFormats
import ym.globalchain.util.TimeFormats
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class MarketGuiService(
    private val plugin: GlobalChain,
    private val config: PluginConfig,
    private val language: LanguageService,
    private val menuRegistry: MenuRegistry,
    private val marketService: MarketService,
    private val walletService: WalletService,
) {

    private val codec = ItemStackCodec()
    private val sessions = ConcurrentHashMap<UUID, MenuSession>()
    private val prompts = ConcurrentHashMap<UUID, PromptState>()

    fun openMenu(player: Player, menuId: String) {
        val current = sessions[player.uniqueId] ?: MenuSession(menuId = menuId)
        val selectedFilterToken = if (menuId == MENU_MAILBOX) null else current.selectedFilterToken
        load(
            player,
            current.copy(
                menuId = menuId,
                returnMenuId = null,
                selectedFilterToken = selectedFilterToken,
                saleDraft = null,
                selectedListing = null,
                slotMapping = emptyMap(),
                listingCache = emptyMap(),
            ),
        )
    }

    fun openGlobal(player: Player) {
        openMenu(player, MENU_GLOBAL)
    }

    fun openMine(player: Player) {
        openMenu(player, MENU_MINE)
    }

    fun openMailbox(player: Player) {
        openMenu(player, MENU_MAILBOX)
    }

    fun openFilterMenu(player: Player) {
        val current = sessions[player.uniqueId] ?: MenuSession(menuId = MENU_GLOBAL)
        val sourceMenuId = resolveSourceMenuId(current)
        load(
            player,
            current.copy(
                menuId = MENU_FILTER,
                returnMenuId = sourceMenuId,
                saleDraft = null,
                selectedListing = null,
                slotMapping = emptyMap(),
                listingCache = emptyMap(),
            ),
        )
    }

    fun openSortMenu(player: Player) {
        val current = sessions[player.uniqueId] ?: MenuSession(menuId = MENU_GLOBAL)
        val sourceMenuId = resolveSourceMenuId(current)
        load(
            player,
            current.copy(
                menuId = MENU_SORT,
                returnMenuId = sourceMenuId,
                saleDraft = null,
                selectedListing = null,
                slotMapping = emptyMap(),
                listingCache = emptyMap(),
            ),
        )
    }

    fun openSellConfirm(player: Player) {
        val current = sessions[player.uniqueId] ?: MenuSession(menuId = MENU_GLOBAL)
        val sourceMenuId = resolveSourceMenuId(current)
        plugin.scheduler().supplyOnPlayer(player) {
            val item = player.inventory.itemInMainHand
            if (item.type == Material.AIR || item.amount <= 0) {
                null
            } else {
                val clean = item.clone()
                val encoded = codec.encode(clean)
                CompatibilityDebug.logSnapshot(plugin, config.debug.compatibilityMode, "sell-confirm-preview", clean, encoded)
                SaleDraft(
                    itemData = encoded,
                    itemName = ItemNaming.name(clean),
                    materialKey = clean.type.key.toString(),
                    amount = clean.amount,
                )
            }
        }.whenComplete { draft, error ->
            if (error != null) {
                plugin.sendError(player, error)
                return@whenComplete
            }
            if (draft == null) {
                plugin.sendMessage(player, "messages.holding-empty")
                return@whenComplete
            }
            load(
                player,
                current.copy(
                    menuId = MENU_SELL_CONFIRM,
                    returnMenuId = sourceMenuId,
                    saleDraft = draft,
                    selectedListing = null,
                    slotMapping = emptyMap(),
                    listingCache = emptyMap(),
                ),
            )
        }
    }

    fun openMineEditor(player: Player, listing: ListingSummary) {
        val current = sessions[player.uniqueId] ?: MenuSession(menuId = MENU_MINE)
        load(
            player,
            current.copy(
                menuId = MENU_MINE_EDIT,
                saleDraft = null,
                selectedListing = listing,
                slotMapping = emptyMap(),
                listingCache = emptyMap(),
            ),
        )
    }

    fun handleClick(player: Player, holderId: UUID, slot: Int) {
        val session = sessions[player.uniqueId] ?: return
        if (session.holderId != holderId) {
            return
        }
        val menu = menuRegistry.require(session.menuId)
        val token = menu.tokenBySlot[slot] ?: return
        val button = menu.buttons[token] ?: return
        executeClickActions(player, session, menu, button, slot, 0)
    }

    fun consumeChat(player: Player, message: String): Boolean {
        val prompt = prompts.remove(player.uniqueId) ?: return false
        val content = message.trim()
        plugin.scheduler().runOnPlayer(player) {
            if (content.equals("cancel", true)) {
                plugin.sendMessage(player, "messages.prompt-cancelled")
                reopen(player)
                return@runOnPlayer
            }
            when (prompt.type) {
                PromptType.SEARCH -> {
                    val current = sessions[player.uniqueId] ?: MenuSession(prompt.resumeMenuId)
                    val next = current.copy(
                        menuId = prompt.resumeMenuId,
                        page = 1,
                        searchTerm = content.ifBlank { null },
                    )
                    plugin.sendMessage(player, "messages.search-updated", mapOf("query" to (next.searchTerm ?: "-")))
                    load(player, next)
                }
                PromptType.SELL_PRICE -> {
                    marketService.sell(player, content).whenComplete { feedback, error ->
                        deliverFeedback(player, feedback, error)
                        reopen(player)
                    }
                }
                PromptType.SELL_CONFIRM_PRICE -> {
                    val current = sessions[player.uniqueId] ?: MenuSession(prompt.resumeMenuId)
                    val parsedMinor = MoneyFormats.parseMinorUnits(content, config.currency.scale)
                    if (parsedMinor == null || parsedMinor <= 0L) {
                        plugin.sendMessage(player, "messages.invalid-price", mapOf("scale" to config.currency.scale.toString()))
                        reopen(player)
                        return@runOnPlayer
                    }
                    val nextDraft = current.saleDraft?.copy(priceMinor = parsedMinor)
                    sessions[player.uniqueId] = current.copy(
                        menuId = MENU_SELL_CONFIRM,
                        saleDraft = nextDraft,
                    )
                    plugin.sendMessage(
                        player,
                        "messages.sell-confirm-price-set",
                        mapOf("price" to MoneyFormats.formatMinorUnits(parsedMinor, config.currency.scale)),
                    )
                    reopen(player)
                }
                PromptType.EDIT_PRICE -> {
                    val listingId = prompt.selectedListingId ?: sessions[player.uniqueId]?.selectedListing?.listingId
                    if (listingId == null) {
                        plugin.sendMessage(player, "messages.listing-not-found")
                        openMine(player)
                        return@runOnPlayer
                    }
                    val parsedMinor = MoneyFormats.parseMinorUnits(content, config.currency.scale)
                    marketService.reprice(player, listingId.toString(), content).whenComplete { feedback, error ->
                        if (error == null && parsedMinor != null) {
                            val current = sessions[player.uniqueId]
                            if (current?.selectedListing?.listingId == listingId) {
                                sessions[player.uniqueId] = current.copy(
                                    selectedListing = current.selectedListing.copy(priceMinor = parsedMinor),
                                )
                            }
                        }
                        deliverFeedback(player, feedback, error)
                        reopen(player)
                    }
                }
            }
        }
        return true
    }

    fun cleanup(playerId: UUID) {
        prompts.remove(playerId)
        sessions.remove(playerId)
    }

    private fun reopen(player: Player) {
        val session = sessions[player.uniqueId] ?: MenuSession(MENU_GLOBAL)
        load(player, session)
    }

    private fun load(player: Player, rawSession: MenuSession) {
        val session = normalizeSession(rawSession)
        val requestId = UUID.randomUUID()
        sessions[player.uniqueId] = session.copy(requestId = requestId)
        plugin.scheduler().runOnPlayer(player) {
            player.openInventory(openStaticMenu(player, menuRegistry.require(MENU_LOADING), session, emptyMap(), emptyMap()))
        }

        val balanceFuture = walletService.balance(player.uniqueId, player.name)
        val pageFuture: CompletableFuture<MenuPayload> = when (session.menuId) {
            MENU_MAILBOX -> marketService.mailboxPage(player, session.page, dynamicSlotCount(menuRegistry.require(MENU_MAILBOX))).thenApply { MenuPayload.Mailbox(it) }
            MENU_MINE, MENU_GLOBAL -> marketService.browsePage(
                player = player,
                page = session.page,
                mineOnly = session.menuId == MENU_MINE,
                searchTerm = session.searchTerm,
                categoryPrefixes = activeFilterPrefixes(menuRegistry.require(session.menuId), session.selectedFilterToken),
                sort = selectedSort(session.selectedSortToken),
                pageSize = dynamicSlotCount(menuRegistry.require(session.menuId)),
            ).thenApply { MenuPayload.Listings(it) }
            MENU_FILTER, MENU_SORT, MENU_SELL_CONFIRM -> CompletableFuture.completedFuture(MenuPayload.Static)
            else -> CompletableFuture.completedFuture(MenuPayload.Static)
        }

        balanceFuture.thenCombine(pageFuture) { balance, payload ->
            balance to payload
        }.whenComplete { result, error ->
            if (error != null) {
                plugin.sendError(player, error)
                plugin.scheduler().runOnPlayer(player) {
                    val topHolder = player.openInventory.topInventory.holder as? MarketInventoryHolder
                    if (topHolder != null && topHolder.menuId == MENU_LOADING) {
                        player.closeInventory()
                    }
                }
                return@whenComplete
            }
            plugin.scheduler().runOnPlayer(player) {
                runCatching {
                    val current = sessions[player.uniqueId] ?: return@runCatching
                    if (current.requestId != requestId) {
                        return@runCatching
                    }
                    when (val payload = result.second) {
                        is MenuPayload.Listings -> openListingMenu(player, current, payload.page, result.first)
                        is MenuPayload.Mailbox -> openMailboxMenu(player, current, payload.page, result.first)
                        MenuPayload.Static -> when (current.menuId) {
                            MENU_MINE_EDIT -> openMineEditMenu(player, current, result.first)
                            MENU_SELL_CONFIRM -> openSellConfirmMenu(player, current, result.first)
                            else -> openPureStaticMenu(player, current, result.first)
                        }
                    }
                }.onFailure { throwable ->
                    plugin.sendError(player, throwable)
                    val topHolder = player.openInventory.topInventory.holder as? MarketInventoryHolder
                    if (topHolder != null && topHolder.menuId == MENU_LOADING) {
                        player.closeInventory()
                    }
                }
            }
        }
    }

    private fun openListingMenu(player: Player, session: MenuSession, page: ListingPage, balance: Long) {
        val menu = menuRegistry.require(session.menuId)
        val placeholders = basePlaceholders(menu, session, balance, page.totalCount)
        val inventory = openStaticMenu(player, menu, session, placeholders, emptyMap())
        val token = menu.firstTokenByRole("listing-entry") ?: error("Menu ${menu.id} missing listing-entry dynamic role.")
        val template = menu.buttons[token] ?: error("Menu ${menu.id} missing button for token $token")
        val dynamicSlots = menu.slots(token)
        val mapping = linkedMapOf<Int, UUID>()
        val now = System.currentTimeMillis()
        page.entries.forEachIndexed { index, listing ->
            if (index >= dynamicSlots.size) {
                return@forEachIndexed
            }
            val slot = dynamicSlots[index]
            val item = codec.decode(listing.itemData)
            decorateDynamicItem(
                item = item,
                template = template,
                placeholders = listingPlaceholders(menu, listing, now, placeholders),
            )
            inventory.setItem(slot, item)
            mapping[slot] = listing.listingId
        }
        sessions[player.uniqueId] = session.copy(
            holderId = (inventory.holder as MarketInventoryHolder).id,
            slotMapping = mapping,
            totalCount = page.totalCount,
            listingCache = page.entries.associateBy { it.listingId },
        )
        player.openInventory(inventory)
    }

    private fun openMailboxMenu(player: Player, session: MenuSession, page: MailboxPage, balance: Long) {
        val menu = menuRegistry.require(MENU_MAILBOX)
        val placeholders = basePlaceholders(menu, session, balance, page.totalCount)
        val inventory = openStaticMenu(player, menu, session, placeholders, emptyMap())
        val token = menu.firstTokenByRole("mailbox-entry") ?: error("Menu ${menu.id} missing mailbox-entry dynamic role.")
        val template = menu.buttons[token] ?: error("Menu ${menu.id} missing button for token $token")
        val dynamicSlots = menu.slots(token)
        val mapping = linkedMapOf<Int, UUID>()
        page.entries.forEachIndexed { index, entry ->
            if (index >= dynamicSlots.size) {
                return@forEachIndexed
            }
            val slot = dynamicSlots[index]
            val item = codec.decode(entry.itemData)
            decorateDynamicItem(
                item = item,
                template = template,
                placeholders = placeholders + mapOf(
                    "item" to ItemNaming.name(item),
                    "amount" to entry.amount.toString(),
                    "id" to shortId(entry.claimId),
                ),
            )
            inventory.setItem(slot, item)
            mapping[slot] = entry.claimId
        }
        sessions[player.uniqueId] = session.copy(holderId = (inventory.holder as MarketInventoryHolder).id, slotMapping = mapping, totalCount = page.totalCount)
        player.openInventory(inventory)
    }

    private fun openMineEditMenu(player: Player, session: MenuSession, balance: Long) {
        val listing = session.selectedListing ?: run {
            openMine(player)
            return
        }
        val menu = menuRegistry.require(MENU_MINE_EDIT)
        val base = basePlaceholders(menu, session, balance, 1)
        val placeholders = listingPlaceholders(menuRegistry.require(MENU_MINE), listing, System.currentTimeMillis(), base)
        val inventory = openStaticMenu(player, menu, session, placeholders, emptyMap())
        val token = menu.firstTokenByRole("selected-listing") ?: error("Menu ${menu.id} missing selected-listing dynamic role.")
        val template = menu.buttons[token] ?: error("Menu ${menu.id} missing button for token $token")
        val slot = menu.slots(token).firstOrNull() ?: error("Menu ${menu.id} missing slot for token $token")
        val item = codec.decode(listing.itemData)
        decorateDynamicItem(item, template, placeholders)
        inventory.setItem(slot, item)
        sessions[player.uniqueId] = session.copy(
            holderId = (inventory.holder as MarketInventoryHolder).id,
            slotMapping = emptyMap(),
            totalCount = 1,
            listingCache = mapOf(listing.listingId to listing),
        )
        player.openInventory(inventory)
    }

    private fun openSellConfirmMenu(player: Player, session: MenuSession, balance: Long) {
        val draft = session.saleDraft ?: run {
            openMenuById(player, session.returnMenuId ?: MENU_GLOBAL)
            return
        }
        val menu = menuRegistry.require(MENU_SELL_CONFIRM)
        val base = basePlaceholders(menu, session, balance, 1)
        val placeholders = saleDraftPlaceholders(draft, base)
        val inventory = openStaticMenu(player, menu, session, placeholders, staticHighlights(session))
        val token = menu.firstTokenByRole("sell-draft-preview") ?: error("Menu ${menu.id} missing sell-draft-preview dynamic role.")
        val template = menu.buttons[token] ?: error("Menu ${menu.id} missing button for token $token")
        val slot = menu.slots(token).firstOrNull() ?: error("Menu ${menu.id} missing slot for token $token")
        val item = codec.decode(draft.itemData)
        decorateDynamicItem(item, template, placeholders)
        inventory.setItem(slot, item)
        sessions[player.uniqueId] = session.copy(
            holderId = (inventory.holder as MarketInventoryHolder).id,
            slotMapping = emptyMap(),
            totalCount = 1,
        )
        player.openInventory(inventory)
    }

    private fun openPureStaticMenu(player: Player, session: MenuSession, balance: Long) {
        val menu = menuRegistry.require(session.menuId)
        val placeholders = basePlaceholders(menu, session, balance, 0)
        val inventory = openStaticMenu(player, menu, session, placeholders, staticHighlights(session))
        sessions[player.uniqueId] = session.copy(holderId = (inventory.holder as MarketInventoryHolder).id, slotMapping = emptyMap(), totalCount = 0)
        player.openInventory(inventory)
    }

    private fun staticHighlights(session: MenuSession): Map<String, Boolean> {
        val sourceHighlight = when (session.returnMenuId) {
            MENU_GLOBAL -> "G"
            MENU_MINE -> "M"
            MENU_MAILBOX -> "B"
            else -> null
        }
        return when (session.menuId) {
            MENU_FILTER -> sourceHighlight?.let { mapOf(it to true) } ?: emptyMap()
            MENU_SORT -> buildMap {
                if (sourceHighlight != null) put(sourceHighlight, true)
                val sortToken = session.selectedSortToken
                if (sortToken != null) put(sortToken, true)
            }
            MENU_SELL_CONFIRM -> sourceHighlight?.let { mapOf(it to true) } ?: emptyMap()
            else -> emptyMap()
        }
    }

    private fun openStaticMenu(
        player: Player,
        menu: MenuDefinition,
        session: MenuSession,
        placeholders: Map<String, String>,
        extraHighlights: Map<String, Boolean>,
    ): Inventory {
        val holder = MarketInventoryHolder(UUID.randomUUID(), menu.id)
        val inventory = Bukkit.createInventory(holder, menu.size, language.renderLegacyRaw(menu.title, placeholders))
        holder.backingInventory = inventory
        menu.buttons.values.forEach { button ->
            if (button.dynamicRole != null) {
                return@forEach
            }
            val renderButton = renderButton(button, menu, session)
            val highlighted = extraHighlights[button.token]
                ?: (session.selectedFilterToken == button.token && (button.filterPrefixes.isNotEmpty() || button.defaultFilter))
            val item = createStaticButton(renderButton, placeholders, highlighted)
            menu.slots(button.token).forEach { slot ->
                inventory.setItem(slot, item.clone())
            }
        }
        return inventory
    }

    private fun createStaticButton(button: MenuButtonDefinition, placeholders: Map<String, String>, highlighted: Boolean): ItemStack {
        val material = button.materials.firstNotNullOfOrNull { Material.matchMaterial(it) } ?: Material.BARRIER
        val item = ItemStack(material, button.amount)
        val meta = item.itemMeta
        if (meta != null) {
            button.name?.let { meta.setDisplayName(language.renderLegacyRaw(it, placeholders)) }
            if (button.lore.isNotEmpty()) {
                meta.setLore(language.renderLegacyRawLines(button.lore, placeholders))
            }
            if (shouldHideTooltip(button)) {
                meta.setHideTooltip(true)
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
            item.itemMeta = meta
        }
        if (button.glow || highlighted) {
            item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1)
        }
        return item
    }

    private fun shouldHideTooltip(button: MenuButtonDefinition): Boolean {
        return button.click.isEmpty() && button.lore.isEmpty() && (button.name == null || button.name.isBlank())
    }

    private fun decorateDynamicItem(item: ItemStack, template: MenuButtonDefinition, placeholders: Map<String, String>) {
        val meta = item.itemMeta
        if (meta != null) {
            template.name?.let { meta.setDisplayName(language.renderLegacyRaw(it, placeholders)) }
            if (template.lore.isNotEmpty()) {
                meta.setLore(language.renderLegacyRawLines(template.lore, placeholders))
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS)
            item.itemMeta = meta
        }
        if (template.glow) {
            item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1)
        }
    }

    private fun executeClickActions(
        player: Player,
        session: MenuSession,
        menu: MenuDefinition,
        button: MenuButtonDefinition,
        slot: Int,
        index: Int,
    ) {
        if (index >= button.click.size) {
            return
        }
        val action = button.click[index].trim()
        if (action.isEmpty()) {
            executeClickActions(player, session, menu, button, slot, index + 1)
            return
        }
        if (action.startsWith("delay:", ignoreCase = true)) {
            val ticks = action.substringAfter(':').trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            plugin.scheduler().runOnPlayerLater(player, ticks) {
                executeClickActions(player, session, menu, button, slot, index + 1)
            }
            return
        }

        val clickedId = session.slotMapping[slot]
        val shouldStop = handleAction(player, session, menu, button, slot, clickedId, action)
        if (!shouldStop) {
            executeClickActions(player, session, menu, button, slot, index + 1)
        }
    }

    private fun handleAction(
        player: Player,
        session: MenuSession,
        menu: MenuDefinition,
        button: MenuButtonDefinition,
        slot: Int,
        clickedId: UUID?,
        rawAction: String,
    ): Boolean {
        val action = resolveActionPlaceholders(rawAction, player, session, clickedId)
        return when {
            action.equals("close", true) -> {
                player.closeInventory()
                false
            }
            action.startsWith("sound:", true) -> {
                val sound = action.substringAfter(':').trim()
                runCatching {
                    player.playSound(player.location, org.bukkit.Sound.valueOf(sound.uppercase()), 1f, 1f)
                }
                false
            }
            action.startsWith("tell:", true) -> {
                val message = action.substringAfter(':').trim()
                plugin.sendComponents(player, listOf(language.renderRaw(message)))
                false
            }
            action.startsWith("player:", true) -> {
                val command = action.substringAfter(':').trim().removePrefix("/")
                player.performCommand(command)
                true
            }
            action.startsWith("console:", true) -> {
                val command = action.substringAfter(':').trim().removePrefix("/")
                plugin.server.dispatchCommand(plugin.server.consoleSender, command)
                true
            }
            action.startsWith("menu:", true) -> {
                val target = action.substringAfter(':').trim()
                openMenuById(player, target)
                true
            }
            action.startsWith("internal:", true) -> {
                handleInternalAction(player, session, menu, button, slot, clickedId, action.substringAfter(':').trim())
            }
            else -> false
        }
    }

    private fun handleInternalAction(
        player: Player,
        session: MenuSession,
        menu: MenuDefinition,
        button: MenuButtonDefinition,
        slot: Int,
        clickedId: UUID?,
        action: String,
    ): Boolean {
        return when (action.lowercase()) {
            "open_global" -> {
                openGlobal(player)
                true
            }
            "open_mine" -> {
                openMine(player)
                true
            }
            "open_mailbox" -> {
                openMailbox(player)
                true
            }
            "prompt_search" -> {
                prompts[player.uniqueId] = PromptState(PromptType.SEARCH, session.menuId)
                player.closeInventory()
                plugin.sendMessage(player, "messages.search-prompt")
                true
            }
            "prompt_sell", "open_sell_confirm" -> {
                openSellConfirm(player)
                true
            }
            "prompt_sell_confirm_price" -> {
                prompts[player.uniqueId] = PromptState(PromptType.SELL_CONFIRM_PRICE, MENU_SELL_CONFIRM)
                player.closeInventory()
                plugin.sendMessage(player, "messages.sell-confirm-price-prompt")
                true
            }
            "prompt_edit_price" -> {
                val listing = session.selectedListing ?: return true
                prompts[player.uniqueId] = PromptState(PromptType.EDIT_PRICE, session.menuId, listing.listingId)
                player.closeInventory()
                plugin.sendMessage(
                    player,
                    "messages.edit-price-prompt",
                    mapOf("price" to MoneyFormats.formatMinorUnits(listing.priceMinor, config.currency.scale)),
                )
                true
            }
            "reset_filters" -> {
                load(
                    player,
                    session.copy(
                        page = 1,
                        searchTerm = null,
                        selectedFilterToken = defaultFilterToken(menu),
                        selectedListing = null,
                    ),
                )
                true
            }
            "refresh" -> {
                load(player, session.copy())
                true
            }
            "open_filter_menu", "cycle_filter" -> {
                openFilterMenu(player)
                true
            }
            "open_sort_menu" -> {
                openSortMenu(player)
                true
            }
            "prev_page" -> {
                if (session.page > 1) {
                    load(player, session.copy(page = session.page - 1))
                }
                true
            }
            "next_page" -> {
                val dynamicCount = dynamicSlotCount(menu)
                if (session.page * dynamicCount < session.totalCount) {
                    load(player, session.copy(page = session.page + 1))
                }
                true
            }
            "select_filter" -> {
                if (button.filterPrefixes.isNotEmpty() || button.defaultFilter) {
                    val selectedToken = menu.tokenBySlot[slot] ?: button.token
                    if (session.menuId == MENU_FILTER) {
                        val targetMenuId = session.returnMenuId ?: MENU_GLOBAL
                        load(
                            player,
                            session.copy(
                                menuId = targetMenuId,
                                returnMenuId = null,
                                page = 1,
                                selectedFilterToken = selectedToken,
                                selectedListing = null,
                                slotMapping = emptyMap(),
                                listingCache = emptyMap(),
                            ),
                        )
                    } else {
                        load(player, session.copy(page = 1, selectedFilterToken = selectedToken))
                    }
                }
                true
            }
            "select_sort" -> {
                val selectedToken = menu.tokenBySlot[slot] ?: button.token
                if (session.menuId == MENU_SORT) {
                    val targetMenuId = session.returnMenuId ?: MENU_GLOBAL
                    load(
                        player,
                        session.copy(
                            menuId = targetMenuId,
                            returnMenuId = null,
                            page = 1,
                            selectedSortToken = selectedToken,
                            selectedListing = null,
                            slotMapping = emptyMap(),
                            listingCache = emptyMap(),
                        ),
                    )
                } else {
                    load(player, session.copy(page = 1, selectedSortToken = selectedToken))
                }
                true
            }
            "back_to_filter_source" -> {
                val targetMenuId = session.returnMenuId ?: MENU_GLOBAL
                load(
                    player,
                    session.copy(
                        menuId = targetMenuId,
                        returnMenuId = null,
                        selectedListing = null,
                        slotMapping = emptyMap(),
                        listingCache = emptyMap(),
                    ),
                )
                true
            }
            "back_to_source" -> {
                openMenuById(player, session.returnMenuId ?: MENU_GLOBAL)
                true
            }
            "confirm_sell" -> {
                val draft = session.saleDraft ?: return true
                marketService.sellDraft(player, draft).whenComplete { feedback, error ->
                    deliverFeedback(player, feedback, error)
                    openMenuById(player, session.returnMenuId ?: MENU_GLOBAL)
                }
                true
            }
            "buy_entry" -> {
                val listingId = clickedId ?: return true
                marketService.buy(player, listingId.toString()).whenComplete { feedback, error ->
                    deliverAndRefresh(player, feedback, error, session.menuId)
                }
                true
            }
            "edit_entry" -> {
                val listingId = clickedId ?: return true
                val listing = session.listingCache[listingId] ?: return true
                openMineEditor(player, listing)
                true
            }
            "cancel_entry" -> {
                val listingId = clickedId ?: return true
                marketService.cancel(player, listingId.toString()).whenComplete { feedback, error ->
                    deliverAndRefresh(player, feedback, error, session.menuId)
                }
                true
            }
            "cancel_selected" -> {
                val listingId = session.selectedListing?.listingId ?: return true
                marketService.cancel(player, listingId.toString()).whenComplete { feedback, error ->
                    deliverAndRefresh(player, feedback, error, session.menuId)
                }
                true
            }
            "back_to_mine" -> {
                load(player, session.copy(menuId = MENU_MINE, selectedListing = null))
                true
            }
            "claim_entry" -> {
                val claimId = clickedId ?: return true
                marketService.claim(player, claimId.toString()).whenComplete { feedback, error ->
                    deliverAndRefresh(player, feedback, error, session.menuId)
                }
                true
            }
            else -> false
        }
    }

    private fun openMenuById(player: Player, menuId: String) {
        when (menuId) {
            MENU_GLOBAL -> openGlobal(player)
            MENU_MINE -> openMine(player)
            MENU_MAILBOX -> openMailbox(player)
            MENU_FILTER -> openFilterMenu(player)
            MENU_SORT -> openSortMenu(player)
            MENU_SELL_CONFIRM -> openSellConfirm(player)
            MENU_MINE_EDIT -> load(player, (sessions[player.uniqueId] ?: MenuSession(MENU_MINE)).copy(menuId = MENU_MINE_EDIT))
            else -> openMenu(player, menuId)
        }
    }

    private fun deliverAndRefresh(player: Player, feedback: ServiceFeedback?, error: Throwable?, menuId: String) {
        deliverFeedback(player, feedback, error)
        when (menuId) {
            MENU_MINE -> openMine(player)
            MENU_MINE_EDIT -> openMine(player)
            MENU_MAILBOX -> openMailbox(player)
            else -> openGlobal(player)
        }
    }

    private fun deliverFeedback(player: Player, feedback: ServiceFeedback?, error: Throwable?) {
        if (error != null) {
            plugin.sendError(player, error)
            return
        }
        if (feedback != null) {
            plugin.sendFeedback(player, feedback)
        }
    }

    private fun normalizeSession(raw: MenuSession): MenuSession {
        val menu = browseContextMenu(raw)
        val validFilters = menu.buttons.values.filter { it.filterPrefixes.isNotEmpty() || it.defaultFilter }.map { it.token }.toSet()
        val selectedFilter = raw.selectedFilterToken?.takeIf { validFilters.contains(it) } ?: defaultFilterToken(menu)
        val selectedSort = raw.selectedSortToken?.takeIf { sortTokens().contains(it) } ?: DEFAULT_SORT_TOKEN
        return raw.copy(
            page = raw.page.coerceAtLeast(1),
            searchTerm = raw.searchTerm?.trim()?.takeIf { it.isNotEmpty() },
            selectedFilterToken = selectedFilter,
            selectedSortToken = selectedSort,
        )
    }

    private fun basePlaceholders(menu: MenuDefinition, session: MenuSession, balance: Long, totalCount: Int): Map<String, String> {
        val dynamicCount = dynamicSlotCount(menu).coerceAtLeast(1)
        val totalPages = if (totalCount <= 0) 1 else ((totalCount - 1) / dynamicCount) + 1
        val contextMenu = browseContextMenu(session)
        return mapOf(
            "page" to session.page.toString(),
            "total" to totalCount.toString(),
            "pages" to totalPages.toString(),
            "search" to (session.searchTerm ?: "-"),
            "filter" to (selectedFilterName(contextMenu, session.selectedFilterToken) ?: "全部"),
            "sort" to selectedSortName(session.selectedSortToken),
            "balance" to MoneyFormats.formatMinorUnits(balance, config.currency.scale),
            "currency" to config.currency.name,
            "symbol" to config.currency.symbol,
        )
    }

    private fun defaultFilterToken(menu: MenuDefinition): String? {
        return menu.buttons.values.firstOrNull { it.defaultFilter }?.token
            ?: menu.buttons.values.firstOrNull { it.filterPrefixes.isEmpty() && it.click.any { click -> click.contains("select_filter", true) } }?.token
            ?: menu.buttons.values.firstOrNull { it.filterPrefixes.isNotEmpty() }?.token
    }

    private fun selectedFilterName(menu: MenuDefinition, token: String?): String? {
        val button = token?.let(menu.buttons::get) ?: return null
        return button.name?.let { language.renderPlainRaw(it) } ?: token
    }

    private fun activeFilterPrefixes(menu: MenuDefinition, token: String?): List<String> {
        return token?.let(menu.buttons::get)?.filterPrefixes.orEmpty()
    }

    private fun selectedSort(sortToken: String?): ListingSort {
        return when (sortToken ?: DEFAULT_SORT_TOKEN) {
            SORT_PRICE_LOW_TOKEN -> ListingSort.PRICE_LOW
            SORT_PRICE_HIGH_TOKEN -> ListingSort.PRICE_HIGH
            SORT_EXPIRING_TOKEN -> ListingSort.EXPIRING
            else -> ListingSort.NEWEST
        }
    }

    private fun selectedSortName(sortToken: String?): String {
        return when (selectedSort(sortToken)) {
            ListingSort.NEWEST -> "最新上架"
            ListingSort.PRICE_LOW -> "价格最低"
            ListingSort.PRICE_HIGH -> "价格最高"
            ListingSort.EXPIRING -> "即将到期"
        }
    }

    private fun sortTokens(): Set<String> = setOf(DEFAULT_SORT_TOKEN, SORT_PRICE_LOW_TOKEN, SORT_PRICE_HIGH_TOKEN, SORT_EXPIRING_TOKEN)

    private fun renderButton(button: MenuButtonDefinition, menu: MenuDefinition, session: MenuSession): MenuButtonDefinition {
        if (!button.click.any { it.equals("internal: cycle_filter", true) || it.equals("internal:cycle_filter", true) }) {
            return button
        }
        val token = session.selectedFilterToken ?: defaultFilterToken(menu)
        return token?.let(menu.buttons::get) ?: button
    }

    private fun filterTokens(menu: MenuDefinition): List<String> {
        return menu.buttons.values
            .filter { it.filterPrefixes.isNotEmpty() || it.defaultFilter }
            .map { it.token }
    }

    private fun saleDraftPlaceholders(
        draft: SaleDraft,
        base: Map<String, String>,
    ): Map<String, String> {
        val priceMinor = draft.priceMinor
        val feeMinor = priceMinor?.let(marketService::feeMinor) ?: 0L
        val payoutMinor = priceMinor?.minus(feeMinor)?.coerceAtLeast(0L) ?: 0L
        val expireAt = System.currentTimeMillis() + (config.market.listingExpireSeconds * 1000L)
        return base + mapOf(
            "item" to draft.itemName,
            "type" to listingType(browseContextMenu(MenuSession(menuId = MENU_GLOBAL)), draft.materialKey),
            "amount" to draft.amount.toString(),
            "price" to (priceMinor?.let { MoneyFormats.formatMinorUnits(it, config.currency.scale) } ?: "未设置"),
            "retailprice" to (priceMinor?.let { retailPrice(it, draft.amount) } ?: "未设置"),
            "fee" to MoneyFormats.formatMinorUnits(feeMinor, config.currency.scale),
            "payout" to MoneyFormats.formatMinorUnits(payoutMinor, config.currency.scale),
            "expiretime" to TimeFormats.formatTimestamp(expireAt),
        )
    }

    private fun listingPlaceholders(
        menu: MenuDefinition,
        listing: ym.globalchain.model.ListingSummary,
        now: Long,
        base: Map<String, String>,
    ): Map<String, String> {
        val type = listingType(menu, listing.materialKey)
        return base + mapOf(
            "item" to listing.itemName,
            "seller" to listing.sellerName,
            "player" to listing.sellerName,
            "type" to type,
            "amount" to listing.amount.toString(),
            "price" to MoneyFormats.formatMinorUnits(listing.priceMinor, config.currency.scale),
            "retailprice" to retailPrice(listing.priceMinor, listing.amount),
            "currency" to config.currency.name,
            "symbol" to config.currency.symbol,
            "uploadTime" to TimeFormats.formatTimestamp(listing.createdAt),
            "expireTime" to if (listing.expireAt > 0L) TimeFormats.formatTimestamp(listing.expireAt) else "永久",
            "expireTimeLeft" to if (listing.expireAt > 0L) TimeFormats.formatRemaining(now, listing.expireAt) else "永久",
            "id" to shortId(listing.listingId),
        )
    }

    private fun listingType(menu: MenuDefinition, materialKey: String): String {
        val key = materialKey.lowercase()
        val state = menu.buttons.values.firstOrNull { state ->
            !state.defaultFilter && state.filterPrefixes.isNotEmpty() && state.filterPrefixes.any { key.startsWith(it.lowercase()) }
        }
        return state?.name?.let(language::renderPlainRaw) ?: "杂项"
    }

    private fun retailPrice(totalMinor: Long, amount: Int): String {
        if (amount <= 0) {
            return MoneyFormats.formatMinorUnits(totalMinor, config.currency.scale)
        }
        val scale = config.currency.scale
        val decimal = BigDecimal.valueOf(totalMinor)
            .divide(BigDecimal.valueOf(amount.toLong()), scale, RoundingMode.HALF_UP)
        return decimal.setScale(scale, RoundingMode.HALF_UP).toPlainString()
    }

    private fun dynamicSlotCount(menu: MenuDefinition): Int {
        val role = if (menu.id == MENU_MAILBOX) "mailbox-entry" else "listing-entry"
        val token = menu.firstTokenByRole(role) ?: return 0
        return menu.slots(token).size
    }

    private fun shortId(id: UUID): String = id.toString().substring(0, config.market.commandIdPrefixMinLength)

    private fun resolveActionPlaceholders(action: String, player: Player, session: MenuSession, clickedId: UUID?): String {
        val replacements = mapOf(
            "player" to player.name,
            "uuid" to player.uniqueId.toString(),
            "page" to session.page.toString(),
            "search" to (session.searchTerm ?: ""),
            "id" to (clickedId?.toString() ?: ""),
        )
        var resolved = action
        replacements.forEach { (key, value) ->
            resolved = resolved.replace("<$key>", value, ignoreCase = true)
        }
        return resolved
    }

    private fun browseContextMenu(session: MenuSession): MenuDefinition {
        val menuId = when (session.menuId) {
            MENU_GLOBAL, MENU_MINE -> session.menuId
            MENU_FILTER, MENU_SORT, MENU_SELL_CONFIRM -> session.returnMenuId ?: MENU_GLOBAL
            MENU_MINE_EDIT -> MENU_MINE
            else -> MENU_GLOBAL
        }
        return menuRegistry.require(menuId)
    }

    private fun resolveSourceMenuId(session: MenuSession): String {
        return when (session.menuId) {
            MENU_GLOBAL, MENU_MINE, MENU_MAILBOX -> session.menuId
            MENU_FILTER, MENU_SORT, MENU_SELL_CONFIRM -> session.returnMenuId ?: MENU_GLOBAL
            MENU_MINE_EDIT -> MENU_MINE
            else -> MENU_GLOBAL
        }
    }

    companion object {
        private const val MENU_LOADING = "loading"
        private const val MENU_GLOBAL = "market-global"
        private const val MENU_MINE = "market-mine"
        private const val MENU_MAILBOX = "market-mailbox"
        private const val MENU_FILTER = "market-filter"
        private const val MENU_SORT = "market-sort"
        private const val MENU_SELL_CONFIRM = "market-sell-confirm"
        private const val MENU_MINE_EDIT = "market-mine-edit"
        private const val DEFAULT_SORT_TOKEN = "0"
        private const val SORT_PRICE_LOW_TOKEN = "1"
        private const val SORT_PRICE_HIGH_TOKEN = "2"
        private const val SORT_EXPIRING_TOKEN = "3"
    }
}

data class MenuSession(
    val menuId: String,
    val returnMenuId: String? = null,
    val page: Int = 1,
    val searchTerm: String? = null,
    val selectedFilterToken: String? = null,
    val selectedSortToken: String? = null,
    val selectedListing: ListingSummary? = null,
    val saleDraft: SaleDraft? = null,
    val requestId: UUID? = null,
    val holderId: UUID? = null,
    val slotMapping: Map<Int, UUID> = emptyMap(),
    val listingCache: Map<UUID, ListingSummary> = emptyMap(),
    val totalCount: Int = 0,
)

enum class PromptType {
    SEARCH,
    SELL_PRICE,
    SELL_CONFIRM_PRICE,
    EDIT_PRICE,
}

data class PromptState(
    val type: PromptType,
    val resumeMenuId: String,
    val selectedListingId: UUID? = null,
)

private sealed interface MenuPayload {
    data class Listings(val page: ListingPage) : MenuPayload
    data class Mailbox(val page: MailboxPage) : MenuPayload
    data object Static : MenuPayload
}

class MarketInventoryHolder(
    val id: UUID,
    val menuId: String,
) : InventoryHolder {
    lateinit var backingInventory: Inventory
    override fun getInventory(): Inventory = backingInventory
}
