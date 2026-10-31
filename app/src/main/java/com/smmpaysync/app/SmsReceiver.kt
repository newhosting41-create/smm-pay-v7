package com.smmpaysync.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.util.UUID

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (msgs == null || msgs.isEmpty()) return

        val from = msgs[0].originatingAddress ?: return
        if (!from.equals("bKash", ignoreCase = true)) return   // only the official header

        val body = msgs.joinToString("") { it.messageBody ?: "" }
        val p = BkashParser.parse(body) ?: return              // OTP / promo / other => ignored

        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                // 1) save on the phone first (so nothing is ever lost)
                Db.get(app).insert(
                    Ev(
                        localId = UUID.randomUUID().toString(),
                        trxId = p.trxId,
                        amount = p.amount,
                        sender = p.sender,
                        smsTime = p.smsTime,
                        raw = body,
                        status = "PENDING",
                        error = null,
                        createdAt = System.currentTimeMillis()
                    )
                )
                // 2) send to the server right now - no need to open the app
                val r = Sender.flushPending(app, true)
                // 3) if it did not go (no internet etc.) the background retry takes over
                if (r == Sender.RETRY) SyncWorker.enqueue(app)
            } catch (e: Exception) {
                try {
                    SyncWorker.enqueue(app)
                } catch (x: Exception) {
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
