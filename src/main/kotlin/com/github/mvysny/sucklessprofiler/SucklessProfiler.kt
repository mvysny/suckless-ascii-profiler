package com.github.mvysny.sucklessprofiler

import java.net.URL
import java.util.*

class SucklessProfiler {
    private lateinit var profilingThread: SamplingThread
    @Volatile private var started = false
    private var startedAt = System.currentTimeMillis()
    /**
     * Set to true to obtain a nice colored output.
     */
    var coloredDump: Boolean = false

    /**
     * Don't profile classes from any of the package that matches these globs. Basically, anything from these classes will
     * be counted towards the own time of the callee method.
     */
    var dontProfilePackages: List<String> = listOf("java.*", "javax.*", "sun.*", "sunw.*", "com.sun.*", "jdk.*")

    var sampleEachMs: Int = 20

    /**
     * Starts to profile this thread. There is no support for profiling multiple threads.
     */
    fun start() {
        started = true
        profilingThread = SamplingThread(Thread.currentThread(), sampleEachMs)
        profilingThread.start()
    }

    fun profile(block: () -> Unit) {
        start()
        try {
            block()
        } finally {
            stop()
        }
    }

    /**
     * Stops the profiler and dumps the data obtained.
     */
    fun stop() {
        started = false
        profilingThread.interrupt()
        profilingThread.join()
        println("====================================================================")
        println("Result of profiling of $profilingThread: ${System.currentTimeMillis() - startedAt}ms")
        println("====================================================================")
        profilingThread.tree.cutStacktraces(dontProfilePackages).dump(coloredDump)
        println("====================================================================")
    }
}

private class SamplingThread(val threadToProfile: Thread, val sampleDelayMs: Int) : Thread() {
    val nameOfThreadToProfile = threadToProfile.name
    val tree = StacktraceSamples()

    init {
        isDaemon = true
        name = "Profiler of $nameOfThreadToProfile"
    }

    override fun run() {
        try {
            var lastStacktraceSampleAt = System.currentTimeMillis()
            while (true) {
                val now = System.currentTimeMillis()
                tree.add(threadToProfile.stackTrace, (now - lastStacktraceSampleAt).toInt())
                lastStacktraceSampleAt = now
                Thread.sleep(sampleDelayMs.toLong())
            }
        } catch (t: InterruptedException) {
            // expected after the end of the profiling
        } catch (t: Throwable) {
            println("Sampling thread $this died!!!!")
            t.printStackTrace()
        }
    }

    override fun toString() = "$nameOfThreadToProfile, sampleDelayMs=$sampleDelayMs"
}

private class StacktraceSamples {
    /**
     * A stacktrace sample at some arbitrary time.
     * @property stacktrace the stacktrace. Note that it is reversed: 0th item is the most nested method call; last item is probably something like `Thread.run()`.
     * @property durationMs the actual delay between previous sample and this one.
     */
    private class Sample(val stacktrace: Array<StackTraceElement>, val durationMs: Int) {
        /**
         * Cuts the stacktrace so that all nested calls matching [classNameRegex] are removed.
         * @param classNameRegex e.g. java.*|javax.*; basically collapses all calls to these uninteresting methods.
         */
        fun cut(classNameRegex: Regex): Sample {
            // we'll cut the stacktrace so that it only contains stacktraces from root (last item in the array) to the last collapsible call.
            // note that the stacktrace is reversed, so we scan from the first item (most nested trace)
            var firstNonMatching = stacktrace.indexOfFirst { !it.className.matches(classNameRegex) }
            if (firstNonMatching < 0) return Sample(arrayOf(), durationMs)
            if (firstNonMatching > 0) {
                firstNonMatching-- // include the function itself, so it is clear which java. or javax. function it was.
            }
            return Sample(stacktrace.sliceArray(firstNonMatching..stacktrace.size - 1), durationMs)
        }
    }

    private val samples = LinkedList<Sample>()
    fun add(stacktrace: Array<StackTraceElement>, durationMs: Int) {
        if (stacktrace.isEmpty()) return
        samples.add(Sample(stacktrace, durationMs))
    }

    fun cutStacktraces(cutpoints: List<String>): StacktraceSamples {
        if (cutpoints.isEmpty()) return this
        val regex = cutpoints.map { it.replace(".", "\\.").replace("*", ".*") }.joinToString("|").toRegex()
        val cutSamples = samples.map { it.cut(regex) }.filterNot { it.stacktrace.isEmpty() }
        val samples = StacktraceSamples()
        samples.samples.addAll(cutSamples)
        return samples
    }

    private class Dumper(private val roots: LinkedHashMap<StackTraceElement, Node>) {
        private class Node(val element: StackTraceElement) {
            val children = LinkedHashMap<StackTraceElement, Node>()
            var ownTime: Long = 0
            // duration of all children, computed gradually
            var totalTime: Long = 0

            override fun toString() = "Node(element=$element, ownTime=$ownTime, totalTime=$totalTime)"
        }

        companion object {
            fun parse(tree: StacktraceSamples): Dumper {
                val roots = LinkedHashMap<StackTraceElement, Node>()
                // first, compute the 'roots' tree.
                for (sample in tree.samples) {

                    var parentNode: Node? = null
                    for (element in sample.stacktrace.reversedArray()) {
                        val node: Node
                        if (parentNode == null) {
                            node = roots.getOrPut(element) { Node(element) }
                            roots.put(element, node)
                        } else {
                            node = parentNode.children.getOrPut(element) { Node(element) }
                        }
                        parentNode = node
                    }
                    parentNode!!.ownTime += sample.durationMs.toLong()
                }

                // second pass - recompute total durations
                fun Node.computeTotalTime(): Long {
                    check(totalTime == 0L) { "Expected 0 but got $totalTime: ${this@computeTotalTime}" }
                    totalTime = ownTime + children.values.map { it.computeTotalTime() }.sum()
                    return totalTime
                }
                for (node in roots.values) {
                    node.computeTotalTime()
                }

                return Dumper(roots)
            }
        }

        fun dump(colored: Boolean) {
            fun toTreeNode(node: Node, padding: Int): PrettyPrintTreeNode {
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
                    appendColor("\u001B[31m")
                    append("${node.totalTime}ms")
                    appendColor("\u001B[0m")
                    if (node.children.isEmpty() || node.ownTime > 0) {
                        append(" / own ")
                        appendColor("\u001B[32m")
                        append("${node.ownTime}ms")
                        appendColor("\u001B[0m")
                    }
                    // right pane: stacktrace element so that the programmer can ctrl+click
                    val charactersUsed = length + padding - colorControlChars
                    repeat((160 - charactersUsed).coerceAtLeast(0)) { append(' ') }
                    append(" at ")
                    append(node.element)
                }
                return PrettyPrintTreeNode(text, node.children.values.map { toTreeNode(it, padding + 2) })
            }

            for (root in roots.values) {
                toTreeNode(root, 0).print()
            }
        }
    }

    fun dump(coloredDump: Boolean) {
        Dumper.parse(this).dump(coloredDump)
    }
}

private class PrettyPrintTreeNode(internal val name: String, internal val children: List<PrettyPrintTreeNode>) {

    fun print() {
        print("", true)
    }

    private fun print(prefix: String, isTail: Boolean) {
        println(prefix + (if (isTail) "\\-" else "+-") + name)
        for (i in 0..children.size - 1 - 1) {
            children[i].print(prefix + if (isTail) "  " else "| ", false)
        }
        if (children.isNotEmpty()) {
            children[children.size - 1].print(prefix + if (isTail) "  " else "| ", true)
        }
    }
}

fun main(args: Array<String>) {
    SucklessProfiler().apply {
        dontProfilePackages = listOf()
        coloredDump = true
    }.profile {
        println(URL("https://aedict-online.eu").readText())
    }
}
