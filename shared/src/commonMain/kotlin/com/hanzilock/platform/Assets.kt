package com.hanzilock.platform

import java.io.InputStream

/** The bundled content (repo folder content/: word sets, sentence corpus, CC-CEDICT). */
interface Assets {
    /** Opens [path], e.g. "sets/index.json". */
    fun open(path: String): InputStream

    /** File names directly inside [dir] (empty if there's no such folder). */
    fun list(dir: String): List<String>
}
