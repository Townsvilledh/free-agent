package org.freeagent.app

/**
 * Lille's X identity kit (v0.10.1) — handle preference list,
 * bio, date of birth, mailbox ladder for HER OWN X account.
 *
 * The kit is her personal identity, so it does NOT live in
 * this public source tree (XLANE.md, "Hers alone"): the
 * values are injected at build time into BuildConfig from
 * the CI environment, which the official workflow populates
 * from the repo's Actions secrets (LILLE_X_KIT_HANDLES,
 * LILLE_X_KIT_BIO, LILLE_X_KIT_DOB, LILLE_X_KIT_MAILBOXES —
 * the same mechanism as the stable signing key). In plain
 * source, local dev builds, and forks, the fields are EMPTY:
 * the build is hollow, [isPresent] is false, and the
 * owner-gated X-account task reports itself unavailable
 * ("owner configuration missing") instead of running with
 * baked-in defaults. Tests build kits from fixture values
 * through [fromRaw] — never the real kit.
 *
 * This type is pure (no Android beyond the generated
 * BuildConfig constants) and pinned by JVM tests.
 */
data class XIdentityKit(
    val handles: List<String>,
    val bio: String,
    val dob: String,
    val mailboxes: List<String>
) {
    /** The kit is usable only when every part was injected.
     *  A partial kit is treated as missing: her signup must
     *  never improvise half an identity. */
    fun isPresent(): Boolean =
        handles.isNotEmpty() && bio.isNotBlank() &&
            dob.isNotBlank() && mailboxes.isNotEmpty()

    companion object {
        /** Parse the raw injected strings (comma-separated
         *  lists; surrounding whitespace is not significant). */
        fun fromRaw(
            handlesCsv: String, bio: String, dob: String, mailboxesCsv: String
        ): XIdentityKit = XIdentityKit(
            handles = splitCsv(handlesCsv),
            bio = bio.trim(),
            dob = dob.trim(),
            mailboxes = splitCsv(mailboxesCsv)
        )

        /** The kit this build was given — empty unless the CI
         *  environment injected the secrets. */
        fun fromBuildConfig(): XIdentityKit = fromRaw(
            BuildConfig.X_KIT_HANDLES,
            BuildConfig.X_KIT_BIO,
            BuildConfig.X_KIT_DOB,
            BuildConfig.X_KIT_MAILBOXES
        )

        private fun splitCsv(raw: String): List<String> =
            raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
