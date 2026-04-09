package ym.globalchain.service

import net.kyori.adventure.text.Component

data class ServiceFeedback(
    val key: String,
    val placeholders: Map<String, String> = emptyMap(),
    val componentPlaceholders: Map<String, Component> = emptyMap(),
)
