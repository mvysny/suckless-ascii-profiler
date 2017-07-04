package com.github.mvysny.sucklessprofiler

import java.util.*

class SucklessProfiler {
    private lateinit var profilingThread: SamplingThread
    @Volatile private var started = false
    private var startedAt = System.currentTimeMillis()

    fun start(sampleEachMs: Int = 40) {
        started = true
        profilingThread = SamplingThread(Thread.currentThread(), sampleEachMs)
        profilingThread.start()
    }

    fun stop(coloredDump: Boolean = false) {
        started = false
        profilingThread.interrupt()
        profilingThread.join()
        println("====================================================================")
        println("Result of profiling of $profilingThread: ${System.currentTimeMillis() - startedAt}ms")
        println("====================================================================")
        profilingThread.tree.dump(coloredDump)
        println("====================================================================")
    }
}

private class SamplingThread(val threadToProfile: Thread, val sampleDelayMs: Int) : Thread() {
    val nameOfThreadToProfile = threadToProfile.name
    val tree = StacktraceTree()
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

private class StacktraceTree {
    private class Sample(val stacktrace: Array<StackTraceElement>, val durationMs: Int)
    private val samples = LinkedList<Sample>()
    fun add(stacktrace: Array<StackTraceElement>, durationMs: Int) {
        if (stacktrace.isEmpty()) return
        samples.add(Sample(stacktrace, durationMs))
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
            fun parse(tree: StacktraceTree): Dumper {
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
                        if (colored) { append(color); colorControlChars += color.length }
                    }

                    // left pane: the call tree with call times
                    with (node.element) {
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
