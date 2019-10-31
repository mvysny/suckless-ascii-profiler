package com.github.mvysny.sucklessprofiler

import com.github.mvysny.dynatest.DynaTest
import java.net.URL
import javax.print.attribute.URISyntax
import kotlin.test.expect

class GlobTest : DynaTest({
    test("single class match") {
        expect(true) { "java.lang.*".toGlob().matches("java.lang.String") }
        expect(true) { "java.lang.*".toGlob().matches(String::class.java) }
        expect(false) { "java.lang.*".toGlob().matches(List::class.java) }
    }

    test("multiple class match") {
        val glob = Glob(listOf("java.lang.*", "java.net.*"))
        expect(true) { glob.matches(URL::class.java) }
        expect(true) { glob.matches(String::class.java) }
        expect(false) { glob.matches(URISyntax::class.java) }
    }

    test("real-world matching") {
        val glob = Glob(listOf("java.*", "javax.*", "sun.*", "sunw.*", "com.sun.*", "jdk.*", "com.zaxxer.hikari.*", "org.springframework.*"))
        expect(true) { glob.matches("org.springframework.transaction.interceptor.TransactionAspectSupport")}
        expect(true) { glob.matches("com.zaxxer.hikari.pool.HikariProxyConnection") }
    }

    test("class matching") {
        expect(true) { "java.lang.String".toGlob().matches("java.lang.String") }
        expect(false) { "java.lang.String".toGlob().matches("java.lang.StringBuilder") }
    }

    test("match-nothing") {
        expect(false) { Glob.MATCH_NOTHING.matches("java.lang.String") }
        expect(false) { Glob.MATCH_NOTHING.matches("com.zaxxer.hikari.pool.HikariProxyConnection") }
    }
})
