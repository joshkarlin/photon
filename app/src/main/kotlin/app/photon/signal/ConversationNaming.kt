package app.photon.signal

/**
 * Pure conversation-title resolution from a contact's phone/profile, shared so
 * the policy is testable and consistent.
 */
object ConversationNaming {

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
