package ym.globalchain.menu

data class MenuDefinition(
    val id: String,
    val title: String,
    val rows: Int,
    val slotsByToken: Map<String, List<Int>>,
    val tokenBySlot: Map<Int, String>,
    val buttons: Map<String, MenuButtonDefinition>,
) {
    val size: Int = rows * 9

    fun slots(token: String): List<Int> = slotsByToken[token].orEmpty()

    fun firstTokenByRole(role: String): String? = buttons.values.firstOrNull { it.dynamicRole == role }?.token

    fun firstButtonByAction(action: String): MenuButtonDefinition? {
        return buttons.values.firstOrNull { button ->
            button.click.any { it.equals(action, ignoreCase = true) || it.equals("internal: $action", ignoreCase = true) || it.equals("internal:$action", ignoreCase = true) }
        }
    }
}

data class MenuButtonDefinition(
    val token: String,
    val materials: List<String>,
    val amount: Int,
    val glow: Boolean,
    val name: String?,
    val lore: List<String>,
    val click: List<String>,
    val dynamicRole: String?,
    val filterPrefixes: List<String>,
    val defaultFilter: Boolean,
)
