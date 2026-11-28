package org.freeagent.app

/**
 * HER OWN FACTS (v1.0.14) — the canonical sources for
 * Lille's own product, recorded once and carried in her
 * state sheet and her task prompts.
 *
 * Born of the 1.0.13 head-to-head battery (verify-a /
 * verify-b): asked to check her own app's latest version
 * and the Fluid names registry, she invented hosts
 * (lille.dev/download, fluidnames.com) and never fetched
 * a real source. The addresses existed in the app all
 * along — UpdateCheck's version.json URL, FluidGate's
 * registry sources — but they were plumbing, not facts
 * in her context. This object is the fact record; the
 * core mirror is free_agent/selffacts.py and the two
 * must not drift.
 *
 * Pure constants + string composition; pinned by
 * Lille114Test.
 */
object SelfFacts {

    const val REPO = "Townsvilledh/free-agent"
    const val REPO_URL = "https://github.com/Townsvilledh/free-agent"
    const val APK_VERSION_URL =
        "https://raw.githubusercontent.com/Townsvilledh/free-agent/apk/version.json"
    const val APK_DOWNLOAD_URL =
        "https://raw.githubusercontent.com/Townsvilledh/free-agent/apk/app-debug.apk"
    const val LANDING_URL =
        "https://townsvilledh.github.io/free-agent/"
    const val NAMES_REGISTRY_URL =
        "https://fluidwallet.dpdns.org/names.json"

    /** The one line the state sheet carries: her own
     *  sources as recorded facts, with the standing
     *  instruction — use these, never invent an address
     *  for your own product. */
    fun sheetLine(): String =
        "Your own canonical sources — recorded facts; use " +
            "these and never invent an address for your own " +
            "product: your latest released version is at " +
            APK_VERSION_URL + "; the app itself at " +
            APK_DOWNLOAD_URL + "; the Fluid names registry " +
            "at " + NAMES_REGISTRY_URL + "; your code at " +
            REPO_URL + "."

    /** The compact form for task system prompts. */
    fun promptLine(): String =
        "Facts about your own product (recorded, canonical): " +
            "your latest version is published at " +
            APK_VERSION_URL + "; the Fluid names registry " +
            "is " + NAMES_REGISTRY_URL + "; your code lives " +
            "at " + REPO_URL + ". Use these sources for " +
            "questions about your own product — never invent " +
            "an address for it."
}
