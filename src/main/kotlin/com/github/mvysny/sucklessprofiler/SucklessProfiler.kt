package com.github.mvysny.sucklessprofiler

import java.net.URL
import java.text.DecimalFormat
import java.time.Duration
import java.util.*
import java.util.concurrent.*

enum class TimeFormatEnum {
    /**
     * Formats the duration as percentage of total time, using one decimal point. Example: `25.4%`.
     */
    Percentage {
        override fun format(duration: Duration, totalDuration: Duration) =
            DecimalFormat("#.#%").format(duration.toMillis().toFloat() / totalDuration.toMillis())
    },
    /**
     * Formats the duration as milliseconds, for example `250ms`.
     */
    Millis {
        override fun format(duration: Duration, totalDuration: Duration) = "${duration.toMillis()}ms"
    };

    /**
     * Formats and returns the [duration], optionally as a part of [totalDuration].
     */
    abstract fun format(duration: Duration, totalDuration: Duration): String
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
     * Whether [stop] and [profile] will dump profiling info. Defaults to true.
     */
    var dump: Boolean = true

    /**
     * Set to true to obtain a nice colored output. Defaults to `false`.
     */
    var coloredDump: Boolean = false

    /**
     * If the call ends in one of these classes, just collapse the call node (all sub-calls will simply be counted towards the own time of this method).
     * Useful for `java.*` since we typically don't want to profile Java built-in classes.
     *
     * The difference between this and [collapsePackagesHard] is that soft-collapsing `java.*` won't collapse `java.lang.Method`
     * since the stack trace continues with your app's logic which is not `java.*`.
     *
     * All [collapsePackagesHard] packages are automatically included in this list.
     */
    var collapsePackagesSoft: MutableList<String> = mutableListOf("java.*", "javax.*", "sun.*", "sunw.*", "com.sun.*", "jdk.*", "kotlin.*")

    /**
     * Don't profile classes from any of the package that matches these globs. Basically, any class matching these globs will
     * be counted towards the own time of the callee method. For example: `com.zaxxer.hikari.*` hard-truncates the HikariCP and the JDBC
     * driver stacktrace which we typically don't want to profile and it would just pollute the profiled call tree.
     *
     * Use with care. This glob hard-collapses any matching node which may be unwanted. For example `java.*` would collapse `java.lang.Method`
     * even though it's a reflective call to your app's logic which you want to profile.
     */
    var collapsePackagesHard: MutableList<String> = mutableListOf()

    /**
     * At the end, calculate totals of how much time the program spent in particular libraries.
     *
     * The "IO/Net" is grouped together: for example the InputStream from a Socket (Net) is wrapped in a BufferedReader which would then count
     * towards IO rather than towards the "Net".
     */
    var groupTotals: MutableMap<String, List<String>> = mapOf(
        "DB" to listOf("com.zaxxer.hikari.*", "org.mariadb.jdbc.*", "org.h2.*", "com.mysql.jdbc.*", "org.postgresql.*"),
        "IO/Net" to listOf("java.io.*", "java.net.*", "javax.net.*")
    ).toMutableMap()

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
     * Removes the common stack frames starting at [Thread.run] that all stacktrace samplings share, leaving only the parts that do differ.
     *
     * Useful for profiling servlets: removes the unnecessary stacktrace all the way from [Thread.run] through http server's
     * parsing code, the filter chain and jumps straight into the servlet code.
     */
    var pruneStacktraceTop: Boolean = false

    /**
     * Often dumping all web requests will produce a lot of clutter; we often want to not to dump any info for short requests which
     * show no performance bottlenecks. Just set this value to `Duration.ofSeconds(2)` to ignore short requests.
     */
    var dumpOnlyProfilingsLongerThan: Duration = Duration.ZERO

    /**
     * How to format the call duration for every frame, either as millis (`250ms`) or percentage (`10.4%`). Defaults
     * to percentage.
     */
    var timeFormat: TimeFormatEnum = TimeFormatEnum.Percentage

    /**
     * If true, sort stack frames, longest first.
     */
    var sortLongestFirst: Boolean = true

    /**
     * Defaults to 0. If not zero, it will collapse all methods whose run time is below 5% of the total profile time.
     * Set this to 5% to get a nice overview where the app spends most of its time.
     */
    var collapseBelowPercent: Int = 0

    /**
     * Starts to profile this thread. There is no support for profiling multiple threads.
     */
    fun start() {
        started = true
        sampler = StacktraceSampler(Thread.currentThread())
        samplerFuture = executor.scheduleAtFixedRate(sampler, 0, sampleEachMs.toLong(), TimeUnit.MILLISECONDS)
    }

    /**
     * Profiles given block. A shorthand for calling [start], then your code, then [stop]. Dumps the data obtained, and returns the
     * stack tree which you can examine further.
     */
    inline fun profile(block: () -> Unit): CallTree {
        start()
        return try {
            block()
            stop()
        } finally {
            stop(dumpProfilingInfo = false)
        }
    }

    /**
     * Stops the profiler and by default dumps the data obtained. This method *must* be called to stop the profiling thread,
     * otherwise it will continue to watch this thread endlessly and needlessly, wasting both CPU cycles and memory (since the stacktrace samples are
     * stored in-memory).
     * @param dumpProfilingInfo defaults to [dump]. If false, nothing is dumped - collected profiling info is just thrown away.
     * @return the stack tree, unpruned and uncollapsed
     */
    @JvmOverloads
    fun stop(dumpProfilingInfo: Boolean = dump): CallTree {
        check(::samplerFuture.isInitialized) { "Profiler has not been started with start()" }
        val totalTime: Duration = Duration.ofMillis(System.currentTimeMillis() - startedAt)
        started = false
        samplerFuture.cancel(false)
        val tree: StacktraceSamples = sampler.copy()
        var callTree: CallTree = tree.toCallTree(totalTime)
        if (sortLongestFirst) {
            callTree = callTree.sortedLongestFirst()
        }

        val dump: Boolean = dumpProfilingInfo && this.dump && totalTime >= dumpOnlyProfilingsLongerThan
        if (dump) {
            // only now it is safe to access Sampler since Future.get() forms the happens-before relation
            dump(callTree)
        }

        return callTree
    }

    fun dump(callTree: CallTree) {
        // don't print directly to stdout - there may be multiple profilings ongoing, and we don't want those println to interleave.
        val sb = StringBuilder()
        sb.append("====================================================================\n")
        sb.append("Result of profiling of $sampler: ${callTree.totalTime.toMillis()}ms, ${callTree.sampleCount} samples\n")
        sb.append("====================================================================\n")
        var collapsedPrunedCallTree: CallTree = callTree
        if (pruneStacktraceTop) {
            collapsedPrunedCallTree = collapsedPrunedCallTree.withStacktraceTopPruned()
        }
        collapsedPrunedCallTree = collapsedPrunedCallTree.withCollapsed(soft = Glob(collapsePackagesSoft + collapsePackagesHard), hard = Glob(collapsePackagesHard))
        if (collapseBelowPercent > 0) {
            val minDuration: Duration = Duration.ofMillis(callTree.totalTime.toMillis() * collapseBelowPercent / 100)
            collapsedPrunedCallTree = collapsedPrunedCallTree.withCollapsed { it.totalTime < minDuration }
        }
        collapsedPrunedCallTree.prettyPrintTo(sb, coloredDump, leftPaneSizeChars, timeFormat)
        sb.append("====================================================================\n")
        println(sb)
        dumpGroupTotals(callTree)
    }

    fun dumpGroupTotals(callTree: CallTree) {
        val sb = StringBuilder()
        val groups: Map<String, Duration> = callTree.calculateGroupTotals(groupTotals)
        sb.append(groups.prettyPrint(callTree.totalTime))
        sb.append("====================================================================\n")
        println(sb)
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
                val now: Long = System.currentTimeMillis()
            val trace: Array<StackTraceElement> = threadToProfile.stackTrace
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
    class Sample(val stacktrace: Array<StackTraceElement>, val durationMs: Int)

    val sampleCount: Int get() = samples.size

    fun toCallTree(totalTime: Duration): CallTree {
        /**
         * @property element the pointer to the class+method the program called.
         * @property children child nodes - functions that the [element] called.
         * @property ownTime how much time was spent executing code in this very function. Equals to this [totalTime] minus total times of all chidren. Milliseconds.
         * @property occurrences in how many stack trace samples this node is present.
         */
        data class SNode(val element: StackTraceElement, var occurrences: Int = 0, var ownTime: Long = 0L,
                         val children: MutableMap<StackTraceElement, SNode> = LinkedHashMap()) {
            fun toNode(): CallTree.Node = CallTree.Node(element,
                    children.values.map { it.toNode() },
                    Duration.ofMillis(ownTime),
                    occurrences)
        }

        val roots = LinkedHashMap<StackTraceElement, SNode>()

        for (sample: Sample in samples) {
            var parentNode: SNode? = null
            for (element: StackTraceElement in sample.stacktrace.reversedArray()) {
                val node: SNode
                if (parentNode == null) {
                    node = roots.getOrPut(element) { SNode(element) }
                    roots[element] = node
                } else {
                    node = parentNode.children.getOrPut(element) { SNode(element) }
                }
                node.occurrences++
                parentNode = node
            }
            val leafNode = parentNode!!
            leafNode.ownTime += sample.durationMs.toLong()
        }

        return CallTree(totalTime, roots.values.map { it.toNode() }, sampleCount)
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

fun main() {
    profilingDemo()
}
