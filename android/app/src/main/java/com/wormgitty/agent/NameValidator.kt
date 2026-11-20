package com.wormgitty.agent

object NameValidator {
    const val MIN = 2
    const val MAX = 40

    fun isValid(name: String): Boolean = name.trim().length in MIN..MAX
}
