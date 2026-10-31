package com.smmpaysync.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Sender {
    const val DONE = 0    // nothing left to do (or everything delivered)
    const val RETRY = 1   // network/server problem, try again later
    const val STOP = 2    // wrong token/secret, user must fix settings

    private val lock = Any()

    /**
     * Sends every PENDING event to the server right now.
     * fast = true is used from the SMS receiver (short timeouts, gives up quickly
     * so Android never complains that the receiver is too slow).
     */
    fun flushPending(ctx: Context, fast: Boolean): Int {
        return synchronized(lock) { doFlush(ctx.applicationContext, fast) }
    }

    private fun doFlush(ctx: Context, fast: Boolean): Int {
        val prefs = Prefs(ctx)
        if (!prefs.isConfigured) return DONE
        val db = Db.get(ctx)
        val connectMs = if (fast) 6000 else 15000
        val readMs = if (fast) 6000 else 20000
        val started = System.currentTimeMillis()

        for (e in db.pending()) {
            if (fast && System.currentTimeMillis() - started > 7000) return RETRY

            val body = JSONObject()
                .put("sender", "bKash")
                .put("raw_sms", e.raw)
                .put("local_event_id", e.localId)
                .toString()

            val res = try {
                Http.post(prefs.url, prefs.token, prefs.secret, body, connectMs, readMs)
            } catch (ex: Exception) {
                prefs.lastResult = "Network error"
                return RETRY
            }
            val code = res.first
            val text = res.second

            when {
                code == 200 -> {
                    val st = try {
                        JSONObject(text).optString("status")
                    } catch (x: Exception) {
                        ""
                    }
                    val newStatus = when (st) {
                        "duplicate" -> "DUPLICATE"
                        "flagged" -> "FLAGGED"
                        else -> "SYNCED"
                    }
                    db.mark(e.localId, newStatus, null)
                    prefs.lastSync = System.currentTimeMillis()
                    prefs.lastResult = "OK"
                }
                code == 401 || code == 403 -> {
                    db.mark(e.localId, "FAILED", "Invalid token/secret (HTTP $code)")
                    prefs.lastResult = "Invalid token/secret"
                    return STOP
                }
                code == 422 -> {
                    val msg = try {
                        JSONObject(text).optString("message")
                    } catch (x: Exception) {
                        ""
                    }
                    db.mark(e.localId, "REJECTED", if (msg.isEmpty()) "Rejected by server" else msg)
                    prefs.lastResult = "OK"
                }
                else -> {
                    prefs.lastResult = "Server error HTTP $code"
                    return RETRY
                }
            }
        }
        return DONE
    }

    /** Ask the server which of our SYNCED payments a client has already used on the website. */
    fun refreshUsed(ctx: Context) {
        try {
            val app = ctx.applicationContext
            val prefs = Prefs(app)
            if (!prefs.isConfigured) return
            val db = Db.get(app)
            val ids = db.syncedTrxIds(50)
            if (ids.isEmpty()) return
            val body = JSONObject()
                .put("action", "status")
                .put("trx_ids", JSONArray(ids))
                .toString()
            val res = Http.post(prefs.url, prefs.token, prefs.secret, body)
            if (res.first != 200) return
            val map = JSONObject(res.second).optJSONObject("statuses") ?: return
            for (id in ids) {
                when (map.optString(id, "")) {
                    "used" -> db.setStatusByTrx(id, "USED")
                    "flagged" -> db.setStatusByTrx(id, "FLAGGED")
                }
            }
        } catch (e: Exception) {
            // not critical; tried again on the next sync
        }
    }
}
