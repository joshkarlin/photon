package app.photon.signal

/**
 * Pure conversation-title resolution from a contact's phone/profile, shared so
 * the policy is testable and consistent.
 */
object ConversationNaming {

    /** Shown until a GroupV2 state fetch resolves the real title. */
    const val PLACEHOLDER_GROUP_NAME = "Signal group"

    /**
     * A group's display title: the stored name, with the placeholder only as
     * a fallback. Never the raw base64 group id (old rows could store it).
     */
    fun groupTitle(storedName: String?, jid: String): String =
        storedName?.takeIf { it.isNotBlank() && it != jid } ?: PLACEHOLDER_GROUP_NAME

    /**
     * Resolve a DM's display name from its contact row. Order: local address
     * book (via [resolveByPhone]) → Signal profile name → bare phone number.
     *
     * Returns `null` when nothing resolves — most importantly for **groups**,
     * which have no phone or profile in the contacts table. Callers MUST treat a
     * null result as "leave the existing name alone", never as "clear the name":
     * nulling a group here wiped the group title and surfaced as a raw base64
     * JID in the UI.
     */
    fun resolveFromContact(
        phone: String?,
        profileName: String?,
        resolveByPhone: (String) -> String?,
    ): String? =
        phone?.let(resolveByPhone)
            ?: profileName?.takeIf { it.isNotBlank() }
            ?: phone
}
