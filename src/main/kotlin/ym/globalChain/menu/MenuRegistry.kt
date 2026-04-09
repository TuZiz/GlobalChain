package ym.globalchain.menu

class MenuRegistry(private val menus: Map<String, MenuDefinition>) {

    fun require(id: String): MenuDefinition {
        return menus[id] ?: error("Missing menu definition: $id")
    }

    fun get(id: String): MenuDefinition? = menus[id]

    fun all(): Collection<MenuDefinition> = menus.values
}
