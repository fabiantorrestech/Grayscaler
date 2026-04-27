package io.github.cloudburst.grayscaler

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class WebShortcutEntry(
    val id: String,
    val label: String,
    val url: String,
    val browserPackage: String?,
    val isManual: Boolean
) : Parcelable
