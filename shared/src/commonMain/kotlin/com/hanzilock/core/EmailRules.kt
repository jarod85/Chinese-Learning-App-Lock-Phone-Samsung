package com.hanzilock.core

/**
 * "Priority email" rules: one per line, matched case-insensitively against the notification's
 * text (sender name, subject, preview and - for Gmail/Outlook - the account address).
 * "*" matches every email notification from the chosen email apps.
 */
object EmailRules {
    val KNOWN_EMAIL_APPS = setOf(
        "com.google.android.gm",
        "com.samsung.android.email.provider",
        "com.microsoft.office.outlook",
        "com.yahoo.mobile.client.android.mail",
        "ch.protonmail.android",
        "com.readdle.spark",
        "com.fsck.k9",
        "net.thunderbird.android",
        "com.fastmail.app",
        "me.bluemail.mail",
        "com.syntomo.email",
    )

    fun parse(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun matches(notificationText: String, rules: List<String>): Boolean {
        val haystack = notificationText.lowercase()
        return rules.any { rule ->
            val r = rule.trim().lowercase()
            r == "*" || (r.isNotEmpty() && haystack.contains(r))
        }
    }
}
