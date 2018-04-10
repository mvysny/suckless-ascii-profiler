package com.github.mvysny.sucklessprofiler

import java.time.Duration

/**
 * A glob matching class names. Examples:
 * * `java.*` will match `java.lang.String` and `java.io.BufferedReader` but not `javax.net.SocketFactory`
 * @property globs a list of globs; any item from the list matches. If empty, the glob will match nothing.
 */
class Glob(val globs: List<String>) {
    private val regex = globs.map { it.replace(".", "\\.").replace("*", ".*") }.joinToString("|").toRegex()
    override fun toString() = "Glob($globs)"
    /**
     * Checks if given [clazz] matches.
     */
    fun matches(clazz: Class<*>) = matches(clazz.name)

    /**
     * Checks if given fully-qualified [clazz]name matches.
     */
    fun matches(clazz: String) = regex.matches(clazz)

    /**
     * Checks if a stack trace element function invocation matches this glob (whether the function is invoked on a class matching this glob).
     * Beware - superclasses are not matched!
     */
    fun matches(element: StackTraceElement) = matches(element.className)
    companion object {
        val MATCH_NOTHING = Glob(listOf())
    }
}

fun String.toGlob(): Glob = Glob(listOf(this))

/**
 * A tree which pretty-prints itself.
 */
class PrettyPrintTree(val name: String, val children: List<PrettyPrintTree>) {

    fun print(sb: StringBuilder) {
        print(sb, "", true)
    }

    private fun print(sb: StringBuilder, prefix: String, isTail: Boolean) {
        sb.append(prefix + (if (isTail) "\\-" else "+-") + name + '\n')
        for (i in 0..children.size - 2) {
            children[i].print(sb, prefix + if (isTail) "  " else "| ", false)
        }
        children.lastOrNull()?.print(sb, prefix + if (isTail) "  " else "| ", true)
    }
}

fun Long.toDuration() = Duration.ofMillis(this)

fun Iterable<Duration>.sum(): Duration = sumByLong { it.toMillis() } .toDuration()

/**
 * Returns the sum of all values produced by [selector] function applied to each element in the collection.
 */
inline fun <T> Iterable<T>.sumByLong(selector: (T) -> Long): Long {
    var sum: Long = 0
    for (element in this) {
        sum += selector(element)
    }
    return sum
}
