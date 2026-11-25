
package com.theoutcasts.livescanner

object TagDetector {

    private val tagPattern =
        Regex("(?<![A-Z0-9])[A-Z0-9]{6}(?![A-Z0-9])")

    fun findTags(screenText: String): List<String> {

        val normalisedText = screenText
            .uppercase()
            .replace('O', '0')
            .replace('I', '1')

        return tagPattern
            .findAll(normalisedText)
            .map { it.value }
            .distinct()
            .toList()
    }
}
