package com.github.mvysny.sucklessprofiler

import java.time.Duration
import java.util.LinkedHashMap

/**
 * Represents a stack tree - a call history. Every node contains a pointer to a class+method, time spent etc.
 */
class StackTree(val roots: List<Node>) {

    /**
     * A node in the stack tree. Contains a pointer to the class+method where the program spent time, the time that was spent etc.
     * @property element the pointer to the class+method the program called.
     * @property children child nodes - functions that the [element] called.
     * @property ownTime how much time was spent executing code in this very function. Equals to this [totalTime] minus total times of all chidren. Milliseconds.
     * @property totalTime the total time spent in this function including all children, in milliseconds.
     * @property occurences in how many stack trace samples this node is present.
     */
    class Node(val element: StackTraceElement) {
        val children = LinkedHashMap<StackTraceElement, Node>()
        var ownTime: Long = 0
        var totalTime: Long = 0
        var occurences: Int = 0

        override fun toString() = "Node(element=$element, ownTime=$ownTime, totalTime=$totalTime, occurences=$occurences)"

        /**
         * Prunes a path with no fork from this node down into its children.
         */
        fun withStacktraceTopPruned(): Node = when {
            children.size == 1 -> children.values.first().withStacktraceTopPruned()
            else -> this
        }

        /**
         * Creates a new node which is collapsed - it has no children and [ownTime] equal to [totalTime]
         */
        fun collapsed() = clone(deep = false).apply { collapse() }

        /**
         * Collapses this node: removes all children and makes [ownTime] equal to [totalTime].
         */
        fun collapse() {
            children.clear()
            ownTime = totalTime
        }

        /**
         * Clones this node.
         * @param deep if true (default), also children are cloned recursively.
         */
        fun clone(deep: Boolean = true): Node = Node(element).also {
            it.ownTime = ownTime
            it.totalTime = totalTime
            it.occurences = occurences
            if (deep) {
                it.children.putAll(children.mapValues { (_, v) -> v.clone(true) })
            } else {
                it.children.putAll(children)
            }
        }
    }

    fun clone(): StackTree = StackTree(roots.map { it.clone() })

    /**
     * Prunes a path with no fork from this node down into its children.
     */
    fun withStacktraceTopPruned() = StackTree(roots.map { it.withStacktraceTopPruned() } )

    /**
     * Returns a new stack tree with nodes matching given [glob] collapsed.
     */
    fun withCollapsed(glob: Glob): StackTree {
        val result = clone()
        fun collapseIfMatches(node: Node) {
            if (glob.matches(node.element)) {
                node.collapse()
            } else {
                for (child in node.children.values) {
                    collapseIfMatches(child)
                }
            }
        }
        for (root in result.roots) {
            collapseIfMatches(root)
        }
        return result
    }

    /**
     * Dumps a pretty tree into the string builder. The tree is optionally [colored].
     * @param timeFormat the format to print the time in.
     * @param leftPaneSizeChars how many characters to pad the right stacktrace pointer.
     */
    fun dump(sb: StringBuilder, colored: Boolean, totalTime: Duration, leftPaneSizeChars: Int, timeFormat: TimeFormatEnum) {

        /**
         * Converts our stack node into the [PrettyPrintTree] which will then pretty-print itself.
         */
        fun toTreeNode(node: Node, padding: Int): PrettyPrintTree {
            val text = buildString {
                var colorControlChars = 0
                fun StringBuilder.appendColor(color: String) {
                    if (colored) {
                        append(color); colorControlChars += color.length
                    }
                }

                // left pane: the call tree with call times
                with(node.element) {
                    appendColor("\u001B[35m")
                    append(className.replaceBeforeLast('.', "").removePrefix("."))
                    appendColor("\u001B[0m")
                    append('.')
                    appendColor("\u001B[34m")
                    append(methodName)
                    append("()")
                    appendColor("\u001B[0m")
                    append(": ")
                }
                append("total ")
                appendColor("\u001B[33m")
                if (node.occurences <= 1) append('>')
                append(timeFormat.format(Duration.ofMillis(node.totalTime), totalTime))
                appendColor("\u001B[0m")
                if (node.children.isEmpty() || node.ownTime > 0) {
                    append(" / own ")
                    appendColor("\u001B[32m")
                    if (node.occurences <= 1) append('>')
                    append(timeFormat.format(Duration.ofMillis(node.ownTime), totalTime))
                    appendColor("\u001B[0m")
                }
                // right pane: stacktrace element so that the programmer can ctrl+click
                val charactersUsed = length + padding - colorControlChars
                repeat((leftPaneSizeChars - charactersUsed).coerceAtLeast(0)) { append(' ') }
                append(" at ")
                append(node.element)
            }
            return PrettyPrintTree(text, node.children.values.map { toTreeNode(it, padding + 2) })
        }

        for (root in roots) {
            toTreeNode(root, 0).print(sb)
        }
    }

    /**
     * Calculates summary durations of various libraries.
     *
     * Note: if a package is matched for one group, the algorithm does not dig deeper into child stack traces! Therefore, if your class
     * matches some group and performs a DB or IO, the DB/IO time is not considered.
     * @param groups calculate totals of how much time the program spent in particular libraries. Maps a human-readable group name (e.g. "DB")
     * to a list of package names to match (e.g. `listOf("com.zaxxer.hikari.*", "org.mariadb.jdbc.*")`.
     * @return totals, keyed by [groups] keys, mapping to duration.
     */
    fun calculateGroupTotals(groups: Map<String, List<String>>): Map<String, Duration> {
        val totals: MutableMap<String, Long> = groups.keys.associate { it to 0L } .toMutableMap()
        val globs = groups.mapValues { Glob(it.value) }
        fun walkNodes(nodes: Collection<Node>) {
            for (node in nodes) {
                val matchingGroupName: String? = globs.entries.firstOrNull { it.value.matches(node.element) } ?.key
                if (matchingGroupName != null) {
                    // match! Append the node time towards the total for given group/key.
                    totals.compute(matchingGroupName, { _, v -> (v ?: 0L) + node.totalTime })
                } else {
                    // no match, search its children.
                    walkNodes(node.children.values)
                }
            }
        }
        walkNodes(roots)
        return totals.mapValues { (_, v) -> Duration.ofMillis(v) }
    }
}

/**
 * Pretty-prints all non-zero groups, e.g. `Total: 100ms [DB: 25ms (25%), IO/Net: 10ms (10%)]`.
 * @param totalTime the overall duration of the profiled code.
 */
fun Map<String, Duration>.prettyPrint(totalTime: Duration): String {
    val groupsDuration = entries.filter { it.value != Duration.ZERO }
    val groupDump = groupsDuration.map { (group, duration) ->
        "$group: ${TimeFormatEnum.Millis.format(duration, totalTime)} (${TimeFormatEnum.Percentage.format(duration, totalTime)})"
    }
    return "Total: ${totalTime.toMillis()}ms [${groupDump.joinToString(", ")}]\n"
}
