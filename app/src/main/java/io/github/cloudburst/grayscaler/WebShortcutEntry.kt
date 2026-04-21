package io.github.cloudburst.grayscaler

data class WebShortcutEntry(
    val id: String,
    val label: String,
    val url: String,
    val browserPackage: String?,
    val isManual: Boolean
)
