package io.github.cloudburst.grayscaler

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class PauseOverlayShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(Intent(this, PauseOverlayService::class.java))
        finish()
    }
}
