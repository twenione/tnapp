package com.trailnav.app

import android.text.InputFilter
import android.text.Spanned

internal object RouteNameRules {
    const val MAX_LENGTH = 60

    fun trimAndLimit(value: String): String = limitCodePoints(value.trim(), MAX_LENGTH)

    fun uniqueName(base: String, existing: Collection<String>): String {
        val normalizedBase = trimAndLimit(base)
        fun isTaken(candidate: String): Boolean = existing.any {
            trimAndLimit(it).equals(candidate, ignoreCase = true)
        }

        if (!isTaken(normalizedBase)) return normalizedBase
        var suffixNumber = 2
        while (true) {
            val suffix = " ($suffixNumber)"
            val prefix = limitCodePoints(
                normalizedBase,
                MAX_LENGTH - codePointCount(suffix),
            )
            val candidate = prefix + suffix
            if (!isTaken(candidate)) return candidate
            suffixNumber++
        }
    }

    fun limitCodePoints(value: String, maxLength: Int): String {
        require(maxLength >= 0)
        val codePointCount = codePointCount(value)
        if (codePointCount <= maxLength) return value
        val end = offsetByCodePoints(value, maxLength)
        return value.substring(0, end)
    }

    fun codePointCount(value: CharSequence): Int {
        var index = 0
        var count = 0
        while (index < value.length) {
            index += Character.charCount(Character.codePointAt(value, index))
            count++
        }
        return count
    }

    fun offsetByCodePoints(value: CharSequence, count: Int): Int {
        require(count >= 0)
        var index = 0
        var advanced = 0
        while (index < value.length && advanced < count) {
            index += Character.charCount(Character.codePointAt(value, index))
            advanced++
        }
        require(advanced == count)
        return index
    }
}

internal class RouteNameLengthFilter(private val maxCodePoints: Int) : InputFilter {
    override fun filter(
        source: CharSequence,
        start: Int,
        end: Int,
        dest: Spanned,
        dstart: Int,
        dend: Int,
    ): CharSequence? {
        val prefixCount = RouteNameRules.codePointCount(dest.subSequence(0, dstart))
        val suffix = dest.subSequence(dend, dest.length)
        val suffixCount = RouteNameRules.codePointCount(suffix)
        val available = maxCodePoints - prefixCount - suffixCount
        if (available <= 0) return if (start == end) null else ""
        val incoming = source.subSequence(start, end)
        val incomingCount = RouteNameRules.codePointCount(incoming)
        if (incomingCount <= available) return null
        val endIndex = RouteNameRules.offsetByCodePoints(incoming, available)
        return incoming.subSequence(0, endIndex)
    }
}
