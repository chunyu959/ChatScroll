package com.chatscroll.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.chatscroll.app.ui.AppState
import com.chatscroll.app.ui.ImportPayload
import com.chatscroll.app.ui.Root
import com.chatscroll.app.ui.theme.ChatScrollTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pushImportIntent(intent)
        setContent {
            ChatScrollTheme {
                Root()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pushImportIntent(intent)
    }

    private fun pushImportIntent(intent: Intent?) {
        intent ?: return
        if (intent.action != Intent.ACTION_SEND) return

        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val payload = stream?.let { ImportPayload.FileUri(it) }
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.let { ImportPayload.RawText(it) }
        if (payload != null) {
            AppState.pendingImport.value = payload
        }
    }
}
