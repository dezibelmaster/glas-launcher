package de.glas.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle

/**
 * Shows Android's own "Allow deleting?" question on the TV for a file the phone wants deleted.
 * Android 10 and later only let an app delete files it did not create after the user agrees there.
 */
class DeleteConfirmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        @Suppress("DEPRECATION")
        val sender = intent.getParcelableExtra<IntentSender>(EXTRA_SENDER) ?: return finish()
        try {
            @Suppress("DEPRECATION")
            startIntentSenderForResult(sender, 1, null, 0, 0, 0)
        } catch (e: Exception) {
            finish()
        }
    }

    @Deprecated("Needed for the delete confirmation")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = intent.getStringExtra(EXTRA_URI)
        if (resultCode == RESULT_OK && uri != null) {
            Thread {
                // On Android 10 the answer only grants access; the delete itself still has to happen
                if (Build.VERSION.SDK_INT == 29) Media.delete(applicationContext, uri)
                PhoneApi.deleted(applicationContext, uri)
            }.start()
            toast(tr("Gelöscht"))
        } else {
            toast(tr("Nicht gelöscht"))
        }
        finish()
    }

    companion object {
        private const val EXTRA_SENDER = "sender"
        private const val EXTRA_URI = "uri"

        fun start(ctx: Context, sender: IntentSender, uri: String) {
            ctx.startActivity(Intent(ctx, DeleteConfirmActivity::class.java)
                .putExtra(EXTRA_SENDER, sender)
                .putExtra(EXTRA_URI, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
