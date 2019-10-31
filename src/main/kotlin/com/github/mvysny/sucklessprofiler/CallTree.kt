package com.github.mvysny.sucklessprofiler

import java.time.Duration

/**
 * Represents a call tree - a call history. Every node contains a pointer to a class+method, time spent etc. Immutable.
 */
class CallTree(val totalTime: Duration, val roots: List<Node>) {
    init {
        require(totalTime >= Duration.ZERO) { "Got negative totalTime $totalTime" }
    }

    /**
     * A node in the stack tree. Contains a pointer to the class+method where the program spent time, the time that was spent etc.
     * Immutable.
     * @property element the pointer to the class+method the program called.
     * @property children child nodes - functions that the [element] called.
     * @property ownTime how much time was spent executing code in this very function. Equals to this [totalTime] minus total times of all chidren. Milliseconds.
     * @property occurrences in how many stack trace samples this node is present.
     */
    data class Node(val element: StackTraceElement, val children: List<Node>, val ownTime: Duration, val occurrences: Int) {

        /**
         * The total time spent in this function including all children, in milliseconds.
         */
        val totalTime: Duration by lazy { ownTime + children.map { it.totalTime }.sum() }

        override fun toString() = "Node($element, $ownTime/$totalTime, occurrences=$occurrences)"
        override fun equals(other: Any?): Boolean = other is Node && element == other.element
        override fun hashCode(): Int = element.hashCode()

        /**
         * Prunes a path with no fork from this node down into its children.
         */
        fun withStacktraceTopPruned(): Node = when {
            children.size == 1 -> children.first().withStacktraceTopPruned()
            else -> this
        }

        /**
         * Creates a new node which is collapsed - it has no children and [ownTime] equal to [totalTime]
         */
        fun collapsed() = copy(children = listOf(), ownTime = totalTime)

        /**
         * Checks whether this node and the whole tree matches given [glob].
         */
        fun treeMatches(glob: Glob): Boolean = glob.matches(element) && children.all { it.treeMatches(glob) }

        fun withChildrenSortedWith(comparator: Comparator<Node>): Node =
                copy(children = children.map { it.withChildrenSortedWith(comparator) } .sortedWith(comparator))
    }

    /**
     * Prunes a path with no fork from this node down into its children.
     */
    fun withStacktraceTopPruned() = CallTree(totalTime, roots.map { it.withStacktraceTopPruned() } )

    /**
     * Returns a new stack tree with nodes matching given pair of globs collapsed.
     * @param soft the 'soft' collapser - the node including the subtree must match in order to be collapsed.
     * @param hard the 'hard' collapser - it is enough that this node matches this glob.
     */
    @JvmOverloads
    fun withCollapsed(soft: Glob = Glob.MATCH_NOTHING, hard: Glob = Glob.MATCH_NOTHING): CallTree {
        fun collapseIfMatches(node: Node): Node = if (hard.matches(node.element) || node.treeMatches(soft)) {
            node.collapsed()
        } else {
            node.copy(children = node.children.map { collapseIfMatches(it) })
        }
        return CallTree(totalTime, roots.map { collapseIfMatches(it) })
    }

    /**
     * Returns a pretty tree. The tree is optionally [colored].
     * @param timeFormat the format to print the time in.
     * @param leftPaneSizeChars how many characters to pad the right stacktrace pointer.
     */
    @JvmOverloads
    fun prettyPrint(colored: Boolean = false,
                    leftPaneSizeChars: Int = 40,
                    timeFormat: TimeFormatEnum = TimeFormatEnum.Percentage): String =
        buildString { prettyPrintTo(this, colored, leftPaneSizeChars, timeFormat) }

    /**
     * Dumps a pretty tree into the string builder. The tree is optionally [colored].
     * @param timeFormat the format to print the time in.
     * @param leftPaneSizeChars how many characters to pad the right stacktrace pointer.
     */
    fun prettyPrintTo(sb: StringBuilder, colored: Boolean, leftPaneSizeChars: Int, timeFormat: TimeFormatEnum) {

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
                if (node.occurrences <= 1) append('>')
                append(timeFormat.format(node.totalTime, totalTime))
                appendColor("\u001B[0m")
                if (node.children.isEmpty() || node.ownTime > Duration.ZERO) {
                    append(" / own ")
                    appendColor("\u001B[32m")
                    if (node.occurrences <= 1) append('>')
                    append(timeFormat.format(node.ownTime, totalTime))
                    appendColor("\u001B[0m")
                }
                // right pane: stacktrace element so that the programmer can ctrl+click
                val charactersUsed = length + padding - colorControlChars
                repeat((leftPaneSizeChars - charactersUsed).coerceAtLeast(0)) { append(' ') }
                append(" at ")
                append(node.element)
            }
            return PrettyPrintTree(text, node.children.map { toTreeNode(it, padding + 2) })
        }

        for (root in roots) {
            toTreeNode(root, 0).print(sb)
        }
    }

    /**
     * Calculates summary durations of various libraries, represented as package matcher [Glob]s.
     *
     * Note: if a package is matched for one group, the algorithm does not dig deeper into child stack traces! Therefore, if your class
     * matches some group and performs a DB or IO, the DB/IO time is not considered.
     * @param groups calculate totals of how much time the program spent in particular libraries. Maps a human-readable group name (e.g. "DB")
     * to a list of package names to match (e.g. `listOf("com.zaxxer.hikari.*", "org.mariadb.jdbc.*")`.
     * @return totals, keyed by [groups] keys, mapping to duration.
     */
    fun calculateGroupTotals(groups: Map<String, List<String>>): Map<String, Duration> {
        val totals: MutableMap<String, Long> = groups.keys.associateWith { 0L }.toMutableMap()
        val globs: Map<String, Glob> = groups.mapValues { Glob(it.value) }
        fun walkNodes(nodes: Collection<Node>) {
            for (node in nodes) {
                val matchingGroupName: String? = globs.entries.firstOrNull { it.value.matches(node.element) } ?.key
                if (matchingGroupName != null) {
                    // match! Append the node time towards the total for given group/key.
                    totals.compute(matchingGroupName) { _, v: Long? -> v!! + node.totalTime.toMillis() }
                } else {
                    // no match, search its children.
                    walkNodes(node.children)
                }
            }
        }
        walkNodes(roots)
        return totals.mapValues { (_, v: Long) -> Duration.ofMillis(v) }
    }

    /**
     * Returns a copy of this call tree, with all nodes sorted using given [comparator].
     */
    fun sortedWith(comparator: Comparator<Node>): CallTree {
        val sorted: List<Node> = this.roots.map { it.withChildrenSortedWith(comparator) } .sortedWith(comparator)
        return CallTree(totalTime, sorted)
    }

    /**
     * Returns a copy of this call tree, with all nodes sorted longest [Node.totalTime] first.
     */
    fun sortedLongestFirst(): CallTree = sortedWith(compareBy { -it.totalTime.toMillis() })
}

/**
 * Pretty-prints all non-zero groups, e.g. `Total: 100ms [DB: 25ms (25%), IO/Net: 10ms (10%)]`.
 * @param totalTime the overall duration of the profiled code.
 */
fun Map<String, Duration>.prettyPrint(totalTime: Duration): String {
    val groupsDuration: List<Map.Entry<String, Duration>> = entries.filter { it.value != Duration.ZERO }
    val groupDump: List<String> = groupsDuration.map { (group, duration) ->
        "$group: ${TimeFormatEnum.Millis.format(duration, totalTime)} (${TimeFormatEnum.Percentage.format(duration, totalTime)})"
    }
    return "Total: ${totalTime.toMillis()}ms [${groupDump.joinToString(", ")}]\n"
}
