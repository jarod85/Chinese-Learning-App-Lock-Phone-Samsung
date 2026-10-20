package com.hanzilock.desktop

import java.io.File

/** Where the Mac app keeps its data and finds what it was shipped with. */
object DesktopPaths {
    private val os = System.getProperty("os.name").orEmpty().lowercase()
    val isMac = "mac" in os
    val isWindows = "windows" in os

    /**
     * Your words, progress, stories and settings: ~/Library/Application Support/Lingo Lock on the Mac
     * (the system property lingolock.dataDir moves it, e.g. for tests).
     */
    val dataDir: File by lazy {
        val home = File(System.getProperty("user.home"))
        when {
            System.getProperty("lingolock.dataDir") != null -> File(System.getProperty("lingolock.dataDir"))
            isMac -> File(home, "Library/Application Support/Lingo Lock")
            isWindows -> File(System.getenv("APPDATA") ?: home.path, "Lingo Lock")
            else -> File(home, ".lingolock")
        }.apply { mkdirs() }
    }

    /** The app's bundled resources (Contents/app/resources in the .app); null when run from Gradle without them. */
    private val resourcesDir: File? = System.getProperty("compose.application.resources.dir")?.let(::File)?.takeIf { it.isDirectory }

    /** The word sets, sentence corpus and dictionary (the repo's content/ folder). */
    val contentDir: File by lazy {
        listOfNotNull(resourcesDir?.let { File(it, "content") }, File("content"), File("../content"))
            .firstOrNull { File(it, "sets/index.json").exists() }
            ?: error("The app's word sets are missing (no content folder found).")
    }

    /** The Swift helper for speech and the practice gate (macOS only). */
    val helper: File?
        get() = resourcesDir?.let { File(it, "lingo-helper") }?.takeIf { it.isFile }
            ?.also { if (!it.canExecute()) it.setExecutable(true) }
            ?.takeIf { it.canExecute() }

    /** The installed Lingo Lock.app, if this is one (not a run from Gradle). */
    val installedApp: File? by lazy {
        val launcher = System.getProperty("jpackage.app-path") ?: return@lazy null
        generateSequence(File(launcher).absoluteFile) { it.parentFile }.firstOrNull { it.name.endsWith(".app") }
    }
}
