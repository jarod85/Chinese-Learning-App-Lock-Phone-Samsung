plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Shared by the phone (app) and Mac (desktop) builds.
// The build number is the number of commits, so every committed update installs as a newer version
// (Settings > About shows it). Without git it falls back to 1; `adb install -r` accepts equal numbers.
val commitCount: Int by extra(runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1))

// The GitHub repository ("owner/name") the app downloads new builds from, taken from the git remote.
// Empty (e.g. no git or not a GitHub remote) switches the in-app updater off.
val updateRepo: String by extra(runCatching {
    providers.exec { commandLine("git", "remote", "get-url", "origin") }.standardOutput.asText.get().trim()
}.getOrDefault("").let { url ->
    Regex("""github\.com[/:]([^/]+/[^/]+?)(\.git)?/?$""").find(url)?.groupValues?.get(1).orEmpty()
})

val versionName: String by extra("1.2.0")
