package com.jmartinezcampos.freetree

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform