package com.chatscroll.app.data

import java.text.Collator
import java.util.Locale

/** Stable ordering shared by folder listings and library search results. */
object ConversationOrdering {
    const val DEFAULT = "time_desc"
    val orders = setOf("time_desc", "time_asc", "name_asc", "name_desc", "size_desc", "size_asc")

    fun normalized(value: String?): String = value?.takeIf { it in orders } ?: DEFAULT

    fun inFolder(folderId: String?, selected: String): Boolean = when (selected) {
        "all" -> true
        "unfiled" -> folderId == null
        else -> folderId == selected
    }

    fun comparator(order: String, locale: Locale = Locale.getDefault()): Comparator<ConversationSummary> {
        val mode = normalized(order)
        val names = naturalNames(locale)
        return Comparator { first, second ->
            when {
                first.pinned != second.pinned -> if (first.pinned) -1 else 1
                first.pinned && (first.pinnedAt ?: 0L) != (second.pinnedAt ?: 0L) -> compareValues(second.pinnedAt ?: 0L, first.pinnedAt ?: 0L)
                else -> {
                    val primary = when (mode) {
                        "time_asc" -> first.importedAt.compareTo(second.importedAt)
                        "name_asc" -> names.compare(first.title, second.title)
                        "name_desc" -> names.compare(second.title, first.title)
                        "size_asc", "size_desc" -> compareSizes(first.sourceSizeBytes, second.sourceSizeBytes, mode == "size_desc")
                        else -> second.importedAt.compareTo(first.importedAt)
                    }
                    if (primary != 0) primary else {
                        val name = names.compare(first.title, second.title)
                        if (name != 0) name else {
                            val time = second.importedAt.compareTo(first.importedAt)
                            if (time != 0) time else first.id.compareTo(second.id)
                        }
                    }
                }
            }
        }
    }

    /** Unknown original sizes remain last in both directions. */
    private fun compareSizes(first: Long?, second: Long?, descending: Boolean): Int = when {
        first == null && second == null -> 0
        first == null -> 1
        second == null -> -1
        descending -> second.compareTo(first)
        else -> first.compareTo(second)
    }

    fun naturalNames(locale: Locale = Locale.getDefault()): Comparator<String> {
        val collation = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        return Comparator { first, second ->
            var a = 0
            var b = 0
            var zerosTie = 0
            while (a < first.length && b < second.length) {
                val digitA = first[a] in '0'..'9'
                val digitB = second[b] in '0'..'9'
                if (digitA && digitB) {
                    var endA = a
                    var endB = b
                    while (endA < first.length && first[endA] in '0'..'9') endA++
                    while (endB < second.length && second[endB] in '0'..'9') endB++
                    var valueA = a
                    var valueB = b
                    while (valueA < endA - 1 && first[valueA] == '0') valueA++
                    while (valueB < endB - 1 && second[valueB] == '0') valueB++
                    val length = (endA - valueA).compareTo(endB - valueB)
                    if (length != 0) return@Comparator length
                    val digits = first.regionCompare(valueA, endA, second, valueB)
                    if (digits != 0) return@Comparator digits
                    if (zerosTie == 0) zerosTie = (endA - a).compareTo(endB - b)
                    a = endA
                    b = endB
                } else if (digitA != digitB) {
                    return@Comparator if (digitA) -1 else 1
                } else {
                    var endA = a
                    var endB = b
                    while (endA < first.length && first[endA] !in '0'..'9') endA++
                    while (endB < second.length && second[endB] !in '0'..'9') endB++
                    val text = collation.compare(first.substring(a, endA), second.substring(b, endB))
                    if (text != 0) return@Comparator text
                    a = endA
                    b = endB
                }
            }
            val remaining = (first.length - a).compareTo(second.length - b)
            if (remaining != 0) remaining else zerosTie
        }
    }

    private fun String.regionCompare(start: Int, end: Int, other: String, otherStart: Int): Int {
        for (index in start until end) {
            val difference = this[index].compareTo(other[otherStart + index - start])
            if (difference != 0) return difference
        }
        return 0
    }
}
