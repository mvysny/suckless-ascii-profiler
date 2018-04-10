package com.github.mvysny.sucklessprofiler

import com.github.mvysny.dynatest.DynaTest
import java.time.Duration
import kotlin.test.expect

class TreeBuilder {
    private val nodes = mutableListOf<Pair<String, TreeBuilder>>()
    operator fun String.invoke(block: TreeBuilder.()->Unit = {}) {
        val treeBuilder = TreeBuilder()
        treeBuilder.block()
        nodes.add(this to treeBuilder)
    }
    private fun Pair<String, TreeBuilder>.toNode() =
            CallTree.Node(java.lang.StackTraceElement(first, "invoke", null, -1),
                second.toNodes(), Duration.ZERO, 1)

    internal fun toNodes(): List<CallTree.Node> = nodes.map { it.toNode() }
}
fun tree(block: TreeBuilder.()->Unit): CallTree {
    val treeBuilder = TreeBuilder()
    treeBuilder.block()
    return CallTree(Duration.ofMillis(1), treeBuilder.toNodes())
}

class CallTreeTest : DynaTest({

    test("pretty print") {
        val callTree = tree {
            "com.test.Main" {
                "java.lang.Thread"()
                "kotlin.io.TextStreamsKt"()
                "java.lang.Thread"()
            }
        }
        expect("""\-Main.invoke(): total >0%                 at com.test.Main.invoke(Unknown Source)
  +-Thread.invoke(): total >0% / own >0%   at java.lang.Thread.invoke(Unknown Source)
  +-TextStreamsKt.invoke(): total >0% / own >0% at kotlin.io.TextStreamsKt.invoke(Unknown Source)
  \-Thread.invoke(): total >0% / own >0%   at java.lang.Thread.invoke(Unknown Source)
""") { callTree.prettyPrint() }
    }

    test("prune") {
        val callTree = tree {
            "com.test.Main" {
                "com.test.Fun" {
                    "com.test.Fun2"()
                }
            }
        }
        expect("""\-Fun2.invoke(): total >0% / own >0%       at com.test.Fun2.invoke(Unknown Source)
""") { callTree.withStacktraceTopPruned().prettyPrint() }
    }
})
