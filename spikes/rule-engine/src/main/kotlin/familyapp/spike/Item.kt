package familyapp.spike

/** An incoming piece of information, e.g. a forwarded mail or a transcribed letter. */
data class Item(
    val id: String,
    val sender: String,
    val subject: String,
    val body: String,
    /** When the item was written; relative dates ("am Montag") are resolved against it. */
    val writtenOn: java.time.LocalDate? = null,
) {
    val senderDomain: String get() = sender.substringAfter('@', missingDelimiterValue = "").lowercase()
    val fullText: String get() = "$subject\n$body"
}

enum class ItemType { APPOINTMENT, TASK, DEADLINE, DOCUMENT, INFO }

/** What a rule proposes: who it belongs to, who takes care of it, which kind of thing it is. */
data class Assignment(
    val person: String? = null,
    val owner: String? = null,
    val type: ItemType? = null,
    val area: String? = null,
)
