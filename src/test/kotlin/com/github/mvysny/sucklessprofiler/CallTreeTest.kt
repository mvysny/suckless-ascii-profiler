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
                second.toNodes(), Duration.ofMillis(1), 1)

    internal fun toNodes(): List<CallTree.Node> = nodes.map { it.toNode() }
}
fun tree(block: TreeBuilder.()->Unit): CallTree {
    val treeBuilder = TreeBuilder()
    treeBuilder.block()
    return CallTree(Duration.ofMillis(100), treeBuilder.toNodes())
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
        expect("""\-Main.invoke(): total >4% / own >1%       at com.test.Main.invoke(Unknown Source)
  +-Thread.invoke(): total >1% / own >1%   at java.lang.Thread.invoke(Unknown Source)
  +-TextStreamsKt.invoke(): total >1% / own >1% at kotlin.io.TextStreamsKt.invoke(Unknown Source)
  \-Thread.invoke(): total >1% / own >1%   at java.lang.Thread.invoke(Unknown Source)
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
        expect("""\-Fun2.invoke(): total >1% / own >1%       at com.test.Fun2.invoke(Unknown Source)
""") { callTree.withStacktraceTopPruned().prettyPrint() }
    }

    group("collapse") {
        test("collapse soft") {
            val callTree = tree {
                "com.test.Main" {
                    "java.lang.String" {
                        "java.lang.String"()
                    }
                }
            }
            expect("""\-Main.invoke(): total >3% / own >1%       at com.test.Main.invoke(Unknown Source)
  \-String.invoke(): total >2% / own >2%   at java.lang.String.invoke(Unknown Source)
""") { callTree.withCollapsed(soft = "java.lang.*".toGlob()).prettyPrint() }
        }

        test("soft collapse does not collapse unmatched calls") {
            val callTree = tree {
                "com.test.Main" {
                    "java.lang.Method" {
                        "com.test.ReflectiveCall"()
                    }
                }
            }
            expect("""\-Main.invoke(): total >3% / own >1%       at com.test.Main.invoke(Unknown Source)
  \-Method.invoke(): total >2% / own >1%   at java.lang.Method.invoke(Unknown Source)
    \-ReflectiveCall.invoke(): total >1% / own >1% at com.test.ReflectiveCall.invoke(Unknown Source)
""") { callTree.withCollapsed(soft = "java.lang.*".toGlob()).prettyPrint() }
        }

        test("collapse hard") {
            val callTree = tree {
                "com.test.Main" {
                    "java.lang.String" {
                        "java.lang.String"()
                    }
                }
            }
            expect("""\-Main.invoke(): total >3% / own >1%       at com.test.Main.invoke(Unknown Source)
  \-String.invoke(): total >2% / own >2%   at java.lang.String.invoke(Unknown Source)
""") { callTree.withCollapsed(hard = "java.lang.*".toGlob()).prettyPrint() }
        }

        test("hard collapse collapses unmatched calls") {
            val callTree = tree {
                "com.test.Main" {
                    "java.lang.Method" {
                        "com.test.ReflectiveCall"()
                    }
                }
            }
            expect("""\-Main.invoke(): total >3% / own >1%       at com.test.Main.invoke(Unknown Source)
  \-Method.invoke(): total >2% / own >2%   at java.lang.Method.invoke(Unknown Source)
""") { callTree.withCollapsed(hard = "java.lang.*".toGlob()).prettyPrint() }
        }
    }
})
