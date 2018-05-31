package com.github.mvysny.sucklessprofiler

import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectThrows

class SucklessProfilerTest : DynaTest({
    test("exception propagates out of profile() method") {
        expectThrows(RuntimeException::class, "simulated") {
            SucklessProfiler().profile {
                throw RuntimeException("simulated")
            }
        }
    }
})