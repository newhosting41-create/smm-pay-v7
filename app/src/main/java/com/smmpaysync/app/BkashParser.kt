package com.smmpaysync.app

data class ParsedSms(
    val amount: Double,
    val sender: String,
    val fee: Double,
    val balance: Double,
    val trxId: String,
    val smsTime: String
)

/**
 * Same pattern the server uses. Anything that does not match exactly
 * (OTP, promo, send-money, cash-out, personal SMS) is ignored.
 */
object BkashParser {

    private val re = Regex(
        """^You have received Tk\s*([\d,]+(?:\.\d{2})?)\s+from\s+(01\d{9})\.?""" +
            """(?:\s+Ref\s+[^\r\n]*?\.)?""" +
            """\s+(?:Fee|Commission)\s+Tk\s*([\d,]+(?:\.\d{2})?)\.""" +
            """\s+Balance\s+Tk\s*([\d,]+(?:\.\d{2})?)\.""" +
            """\s+TrxID\s+([A-Z0-9]{10})\s+at\s+(\d{2}/\d{2}/\d{4}\s+\d{2}:\d{2})\s*${'$'}"""
    )

    private val spaces = Regex("\\s+")

    fun parse(body: String): ParsedSms? {
        val clean = body.trim().replace(spaces, " ")
        val m = re.find(clean) ?: return null
        val g = m.groupValues
        return try {
            ParsedSms(
                amount = g[1].replace(",", "").toDouble(),
                sender = g[2],
                fee = g[3].replace(",", "").toDouble(),
                balance = g[4].replace(",", "").toDouble(),
                trxId = g[5].uppercase(),
                smsTime = g[6]
            )
        } catch (e: NumberFormatException) {
            null
        }
    }
}
