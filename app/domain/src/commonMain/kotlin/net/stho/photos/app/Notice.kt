package net.stho.photos.app

/**
 * What a toast says (§1, §6).
 *
 * §1 forbids opaque errors: a failure names its status and its cause. The kind is not
 * decoration — it decides both how long the toast lives and what colour it carries:
 *
 * - [Error] needs a person (401/403, a malformed URL). It stays until dismissed, carries an
 *   action to Settings, and is drawn in the error colour.
 * - [Info] clears itself (offline, a timeout, DNS). It auto-dismisses and is drawn neutral,
 *   because there is nothing to do about it.
 *
 * A toast is transient either way, which is why the Settings sync row keeps the durable
 * record: a missed toast must cost nothing.
 */
public data class Notice(val kind: Kind, val title: String, val detail: String) {
    public enum class Kind { Error, Info }

    public companion object {
        /** e.g. "Sync failed: 403 Forbidden" / "Log out and check the password." */
        public fun error(title: String, detail: String): Notice = Notice(Kind.Error, title, detail)

        public fun info(title: String, detail: String): Notice = Notice(Kind.Info, title, detail)
    }
}
