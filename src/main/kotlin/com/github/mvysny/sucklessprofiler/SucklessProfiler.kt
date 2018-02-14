package com.github.mvysny.sucklessprofiler

import java.net.URL
import java.text.DecimalFormat
import java.time.Duration
import java.util.*
import java.util.concurrent.*

enum class TimeFormatEnum {
    Percentage {
        override fun format(stackDuration: Duration, totalDuration: Duration) =
            DecimalFormat("#.#%").format(stackDuration.toMillis().toFloat() / totalDuration.toMillis())
    },
    Millis {
        override fun format(stackDuration: Duration, totalDuration: Duration) = "${stackDuration.toMillis()}ms"
    };
    abstract fun format(stackDuration: Duration, totalDuration: Duration): String
}

/**
 * The profiler. Create a new instance, configure it, then call [start]+[stop] or [profile].
 *
 * Every profiler instance can be used only once. Calling [start] multiple times leads to undefined results.
 */
class SucklessProfiler {
    private lateinit var samplerFuture: ScheduledFuture<*>
    private lateinit var sampler: StacktraceSampler
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

    /**
     * Sample the thread stacktrace each 20 milliseconds by default. Since the profiler introduces very low overhead,
     * even values as low as 2 milliseconds may be reasonable.
     */
    var sampleEachMs: Int = 20

    /**
     * The size of the left pane when pretty-printing the profiler dump to console, in characters. Decrease if you know your stacktraces won't be deep.
     */
    var leftPaneSizeChars: Int = 100

    /**
     * Useful for profiling servlets: removes the unnecessary stacktrace all the way from [Thread.run] through http server's
     * parsing code, the filter chain and jumps straight into the servlet code.
     */
    var pruneStacktraceBottom: Boolean = false

    /**
     * Often dumping all web requests will produce a lot of clutter; we often want to not to dump any info for short requests which
     * show no performance bottlenecks. Just set this value to `Duration.ofSeconds(2)` to ignore short requests.
     */
    var dumpOnlyProfilingsLongerThan: Duration = Duration.ZERO

    var timeFormat: TimeFormatEnum = TimeFormatEnum.Percentage

    /**
     * Starts to profile this thread. There is no support for profiling multiple threads.
     */
    fun start() {
        started = true
        sampler = StacktraceSampler(Thread.currentThread())
        samplerFuture = executor.scheduleAtFixedRate(sampler, 0, sampleEachMs.toLong(), TimeUnit.MILLISECONDS)
    }

    /**
     * Profiles given block. A shorthand for calling [start], then your code, then [stop]. Dumps the data obtained.
     */
    inline fun profile(block: () -> Unit) {
        start()
        try {
            block()
        } finally {
            stop()
        }
    }

    /**
     * Stops the profiler and by default dumps the data obtained. This method *must* be called to stop the profiling thread
     * to watch this thread endlessly and needlessly, wasting both CPU cycles and memory (since the stacktrace samples are
     * stored in-memory).
     * @param dumpProfilingInfo defaults to true. If false, nothing is dumped - collected profiling info is just thrown away.
     */
    fun stop(dumpProfilingInfo: Boolean = true) {
        val totalTime = Duration.ofMillis(System.currentTimeMillis() - startedAt)
        started = false
        samplerFuture.cancel(false)
        val tree = sampler.copy()

        val dump = dumpProfilingInfo && totalTime >= dumpOnlyProfilingsLongerThan
        if (dump) {
            // only now it is safe to access Sampler since Future.get() forms the happens-before relation
            // don't print directly to stdout - there may be multiple profilings ongoing, and we don't want those println to interleave.
            val sb = StringBuilder()
            sb.append("====================================================================\n")
            sb.append("Result of profiling of $sampler: ${totalTime.toMillis()}ms, ${tree.sampleCount} samples\n")
            sb.append("====================================================================\n")
            tree.cutStacktraces(dontProfilePackages).dump(sb, this, totalTime)
            sb.append("====================================================================\n")
            println(sb)
        }
    }
}

// oh crap: ScheduledExecutorService acts as a fixed-sized pool using corePoolSize threads and an unbounded queue, adjustments to maximumPoolSize have no useful effect?!?
// better have more core pool threads then.
private val executor = Executors.newScheduledThreadPool(3, { runnable ->
    val t = Thread(runnable)
    t.name = "Suckless Profiler Thread"
    t.isDaemon = true
    t
})

/**
 * Periodically invoked by the [executor], collects one stacktrace sample per invocation.
 */
private class StacktraceSampler(val threadToProfile: Thread) : Runnable {
    val nameOfThreadToProfile: String = threadToProfile.name
    private val samples = ConcurrentLinkedQueue<StacktraceSamples.Sample>()
    // guarded-by: happens-before relationship guaranteed by ScheduledThreadPoolExecutor
    private var lastStacktraceSampleAt = System.currentTimeMillis()
    /**
     * Thread-safe and safe to call at any time, since [samples] is thread-safe.
     */
    fun copy() = StacktraceSamples(samples.toList())

    override fun run() {
        try {
            // having the run() guarded by synchronized(lock) this will generally perform very well, since the executor will reuse one thread to run all samplers.
            // however, if the contention is really high (lots of samplers running in parallel), this may be a performance bottleneck?
            // yet, removing this synchronized block would leave `lastStacktraceSampleAt` unguarded... yet perhaps there is a happens-before
            // relationship guaranteed by the executor between multiple invocations of the same runnable?

            // AHA! Yes: https://docs.oracle.com/javase/7/docs/api/java/util/concurrent/ScheduledThreadPoolExecutor.html
            // Successive executions of a task scheduled via scheduleAtFixedRate or scheduleWithFixedDelay do not overlap.
            // While different executions may be performed by different threads, the effects of prior executions happen-before those of subsequent ones.

            // peachy. How can I now, upon cancelation, await for the ongoing call to finish?
            // I don't! Since `samples` is thread-safe, I can just create a copy of whatever the sampler collected at any time, and work on that.
//            synchronized(lock) {
                val now = System.currentTimeMillis()
            val trace = threadToProfile.stackTrace
            if (trace.isNotEmpty()) {
                samples.add(StacktraceSamples.Sample(trace, (now - lastStacktraceSampleAt).toInt()))
            }
                lastStacktraceSampleAt = now
//            }
        } catch (t: Throwable) {
            println("Sampling of $this failed!")
            t.printStackTrace()
        }
    }

    override fun toString() = nameOfThreadToProfile
}

/**
 * An immutable copy of samples, produced after the actual sampling finishes. Therefore, this class doesn't need to be optimized much.
 */
private class StacktraceSamples(val samples: List<Sample>) {
    /**
     * A stacktrace sample at some arbitrary time.
     * @property stacktrace the stacktrace. Note that it is reversed: 0th item is the most nested method call; last item is probably something like `Thread.run()`.
     * @property durationMs the actual delay between previous sample and this one.
     */
    class Sample(val stacktrace: Array<StackTraceElement>, val durationMs: Int) {
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
            return Sample(stacktrace.sliceArray(firstNonMatching until stacktrace.size), durationMs)
        }
    }

    val sampleCount: Int get() = samples.size

    fun cutStacktraces(cutpoints: List<String>): StacktraceSamples {
        if (cutpoints.isEmpty()) return this
        val regex = cutpoints.map { it.replace(".", "\\.").replace("*", ".*") }.joinToString("|").toRegex()
        val cutSamples = samples.map { it.cut(regex) }.filterNot { it.stacktrace.isEmpty() }
        return StacktraceSamples(cutSamples)
    }

    private class Dumper(private val roots: List<Node>) {
        private class Node(val element: StackTraceElement) {
            val children = LinkedHashMap<StackTraceElement, Node>()
            var ownTime: Long = 0
            // duration of all children, computed gradually
            var totalTime: Long = 0
            var occurences: Int = 0

            override fun toString() = "Node(element=$element, ownTime=$ownTime, totalTime=$totalTime, occurences=$occurences)"

            fun pruneStacktraceTop(): Node = when {
                children.size == 1 -> children.values.first().pruneStacktraceTop()
                else -> this
            }
        }

        companion object {
            fun parse(tree: StacktraceSamples, pruneStacktraceTop: Boolean): Dumper {
                val roots = LinkedHashMap<StackTraceElement, Node>()

                // first, compute the 'roots' tree.
                for (sample in tree.samples) {
                    var parentNode: Node? = null
                    for (element in sample.stacktrace.reversedArray()) {
                        val node: Node
                        if (parentNode == null) {
                            node = roots.getOrPut(element) { Node(element) }
                            roots[element] = node
                        } else {
                            node = parentNode.children.getOrPut(element) { Node(element) }
                        }
                        node.occurences++
                        parentNode = node
                    }
                    val leafNode = parentNode!!
                    leafNode.ownTime += sample.durationMs.toLong()
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

                var root = roots.values.toList()
                if (pruneStacktraceTop) {
                    root = root.map { it.pruneStacktraceTop() }
                }

                return Dumper(root)
            }
        }

        fun dump(sb: StringBuilder, colored: Boolean, totalTime: Duration, leftPaneSizeChars: Int, timeFormat: TimeFormatEnum) {

            /**
             * Converts our stack node into the [PrettyPrintTreeNode] which will then pretty-print itself.
             */
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
                return PrettyPrintTreeNode(text, node.children.values.map { toTreeNode(it, padding + 2) })
            }

            for (root in roots) {
                toTreeNode(root, 0).print(sb)
            }
        }
    }

    fun dump(sb: StringBuilder, config: SucklessProfiler, totalTime: Duration) {
        Dumper.parse(this, config.pruneStacktraceBottom).dump(sb, config.coloredDump, totalTime, config.leftPaneSizeChars, config.timeFormat)
    }
}

/**
 * A tree which pretty-prints itself.
 */
private class PrettyPrintTreeNode(internal val name: String, internal val children: List<PrettyPrintTreeNode>) {

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

fun main(args: Array<String>) {
    profilingDemo()
}
