package com.janus.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.janus.app.core.JanusService

/**
 * MOB-04 FIX: Trampoline activity that transiently enters foreground
 * to read clipboard content in full compliance with Android 10+ background privacy rules.
 */
class ClipboardSyncActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                if (text.isNotEmpty()) {
                    JanusService.instance?.sendClipboard(text)
                    Toast.makeText(this, "📋 Clipboard synced to Mac", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read clipboard: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            finish()
        }
    }
}
