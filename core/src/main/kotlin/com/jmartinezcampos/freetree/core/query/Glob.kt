package com.jmartinezcampos.freetree.core.query

/**
 * Matches [value] against a glob where `*` matches any sequence (including `/`) and `?` matches
 * exactly one character.
 */
internal fun globMatches(pattern: String, value: String): Boolean {
    var previous = BooleanArray(value.length + 1)
    previous[0] = true
    for (patternChar in pattern) {
        val current = BooleanArray(value.length + 1)
        if (patternChar == '*') {
            current[0] = previous[0]
            for (index in 1..value.length) {
                current[index] = previous[index] || current[index - 1]
            }
        } else {
            for (index in 1..value.length) {
                current[index] = previous[index - 1] &&
                    (patternChar == '?' || patternChar == value[index - 1])
            }
        }
        previous = current
    }
    return previous[value.length]
}
