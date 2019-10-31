package com.github.mvysny.sucklessprofiler

import java.net.URL

private fun profilingDemo() {
    SucklessProfiler().apply {
        leftPaneSizeChars = 70
        coloredDump = true
        timeFormat = TimeFormatEnum.Millis
    }.profile {
        Thread.sleep(500)
        println(URL("https://aedict-online.eu").readText())
        Thread.sleep(500)
    }
}

fun main() {
    profilingDemo()
}
