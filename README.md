[![Build Status](https://travis-ci.org/mvysny/suckless-ascii-profiler.svg?branch=master)](https://travis-ci.org/mvysny/suckless-ascii-profiler)
[![Join the chat at https://gitter.im/vaadin/vaadin-on-kotlin](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/vaadin/vaadin-on-kotlin?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![GitHub tag](https://img.shields.io/github/tag/mvysny/suckless-ascii-profiler.svg)](https://github.com/mvysny/suckless-ascii-profiler/tags)

# Suckless ASCII Profiler

An embedded JVM profiler which you start and stop at will from your code, and it will dump the profiling info into 
the console:

![Profiler Console](docs/images/profiler_console.png)

* Identifies longest-running methods in your code. Just follow the largest percentage trail.
* Uses thread stacktrace sampling, so the overhead is negligible. The accuracy
is not that great, but it definitely can identify the bottleneck in your code.
* IDE turns the console log into clickable links so you can navigate into your code easily

What it's not:

* Remote profiler - cannot connect to a remote JVM
* Debugger
* It doesn't gather JVM/system statistics like CPU usage, memory usage and/or disk usage.
* You need an initial hunch on where your code is actually slow, so that you can place the `profile` method around the code.

Please see [the introductionary video](https://www.youtube.com/watch?v=LhPLXStYePw) for more details.

## Demo

We're going to profile the [example main method](src/main/kotlin/com/github/mvysny/sucklessprofiler/SucklessProfiler.kt#L301) which looks like this:
```kotlin
Thread.sleep(500)
println(URL("https://aedict-online.eu").readText())
Thread.sleep(500)
```

Simply type this into your console:

```bash
git clone https://github.com/mvysny/suckless-ascii-profiler
cd suckless-ascii-profiler
./gradlew run
```

And you should see the output similar to the screenshot above.

## Usage

Add the following repository and dependency to your Gradle script:

```groovy
repositories {
    maven { url "https://dl.bintray.com/mvysny/github/" }
}
dependencies {
    compile "com.github.mvysny.sucklessprofiler:suckless-profiler:x.y"
}
```

Maven:
```xml
    <repositories>
        <repository>
            <id>mvysny-github</id>
            <url>https://dl.bintray.com/mvysny/github/</url>
        </repository>
    </repositories>

    <dependencies>
        <dependency>
            <groupId>com.github.mvysny.sucklessprofiler</groupId>
            <artifactId>suckless-profiler</artifactId>
            <version>0.4</version>
        </dependency>
    </dependencies>
```

> Note: please see the latest tag name above for the newest version


Then, in your code, just call the following:

```java
final SucklessProfiler p = new SucklessProfiler();
p.start();
callMethodYouNeedToProfile();
p.stop();  // this will dump the profiling info into the console
```

or in Kotlin:

```kotlin
SucklessProfiler().apply {
    coloredDump = true
}.profile {
    Thread.sleep(500)
    println(URL("https://aedict-online.eu").readText())
    Thread.sleep(500)
}   // this will call the stop() method and will dump the profiling info into the console 
```

or in your (Vaadin) Servlet:

```kotlin
@WebServlet(urlPatterns = arrayOf("/*"), name = "MyUIServlet", asyncSupported = true)
@VaadinServletConfiguration(ui = MyUI::class, productionMode = false)
class MyUIServlet : VaadinServlet() {
    override fun service(request: HttpServletRequest?, response: HttpServletResponse?) {
        SucklessProfiler().apply {
            coloredDump = true
            pruneStacktraceBottom = true
            dumpOnlyProfilingsLongerThan = Duration.ofSeconds(2)
        }.profile {
            super.service(request, response)
        }
    }
}
```

or in your JUnit tests:

```java
public abstract class AbstractTest {
    private static SucklessProfiler profiler;

    @BeforeClass
    public static void startupServer() {
        profiler = new SucklessProfiler();
        profiler.setColoredDump(true);
        profiler.setPruneStacktraceTop(true);
    }

    @AfterClass
    public static void dumpProfiler() {
        profiler.stop(true);
    }
}
```

# How To Use / General Tips

## Getting General Overview Where Your App Spends Most Time

After the dump is printed on `profiler.stop(true)`, the last line looks like following:

```
Total: 100ms [DB: 25ms (25%), IO/Net: 10ms (10%)]
```

The profiler will sum up durations of certain call stacks, which will give you a nice
overall statistics. By default the following groups are supported:

* DB - sums time spent in the JDBC driver. The following call stacks are considered: `"com.zaxxer.hikari.*", "org.mariadb.jdbc.*", "org.h2.*", "com.mysql.jdbc.*", "org.postgresql.*"`
* IO/Net - sums time spent in I/O or network. The following call stacks are considered: `"java.io.*", "java.net.*", "javax.net.*"`

You can define your own groups, simply by adding stuff into the `profiler.groupTotals` map as follows:

```kotlin
profiler.groupTotals["Password Hashing"] = listOf("my.project.BCryptPasswordEncoder")
```

### Overlapping Groups

A slice of time is always counted towards one group at most.

When the groups overlap (they match the same class + method), first one in the order of
`profiler.groupTotals` keys wins (`profiler.groupTotals` is a `LinkedHashMap` therefore the keys are ordered).

When the groups target different parts of the call stack (e.g. you add groups both for JDBC and for your DAOs),
then the group for the shallowest stack frame wins (in this case your DAOs since they're calling JDBC and they're closer up the call stack).

# About Stacktrace Sampling

A thread stacktrace is captured every 20ms (configurable) into a list of stacktrace samples. That list
is afterwards converted to a tree, and the time spent is assigned. The Stacktrace Sampling method of
profiling is non-intrusive: for example
you don't have to start a Java agent, as opposed to the tracing method (where every method is intercepted and call
statistics are stored - very accurate but very slow and often unnecessary). Stacktrace Sampling will thus profile a real code execution.

Stacktrace sampling is also unfortunately quite inaccurate. Sampling stacktrace every 20ms means that we have no idea what's going
on during those 20ms inbetween samplings. Thus, if a method is sampled only once, we cannot check whether
the method took less than 1ms to run, or whether it took 39ms. To remedy this, you can increase the sampling rate to 10ms
(or even 2ms) to obtain more accurate results while still maintaining quite minor performance hit. However, this is often not required.

Usually what you hunt for is the place where your code spends 200ms or more. And that is something we can
detect with high accuracy. If a method is present in multiple samplings, then there is a high
probability that that method was running quite long. Of course there is also the possibility that
the method was called repeatedly, ran shortly and was captured in multiple stack samplings,
but the probability of this is very low.

In order to obtain the stacktrace, the JVM must reach a safepoint; a thread may run for
just a bit until it hits the safepoint, and therefore the terminal method
may not be accurate. See [JVM safepoints](https://medium.com/software-under-the-hood/under-the-hood-java-peak-safepoints-dd45af07d766)
for more info. Especially high-CPU-usage functions may often be missed since it will take some time
until they reach a safepoint.

To conclude:

* Ignore methods present in just one sample (they are marked with the less-than sign, such as >1%)
* Focus on long-running methods, and dissect them until you find a culprit
* Remember that there is a probability that the profiler is lying to you :-)
* Use Intellij's [Async Profiler](https://blog.jetbrains.com/idea/2018/09/intellij-idea-2018-3-eap-git-submodules-jvm-profiler-macos-and-linux-and-more/)
  to obtain accurate profiling data.

### Overhead

The overhead is very low as compared to tracing, yet there still is a very minor overhead:

* The profiler will start 3 threads per JVM for its own purpose, to collect samples.
* During the sampling, the stacktrace samples are stored in-memory
* Taking a thread stack trace will pause the sampled JVM thread briefly

Thus, the profiler will use a bit of CPU and memory. However, as long as your app is not eating 100%
CPU and is not taking nearly all of your JVM's memory, this overhead will not slow down your app.
Your app will be slowed down by the JVM being paused while the stack trace is taken, however this
pause is generally just a couple of nanoseconds and therefore negligible.

# License

Copyright 2019 Martin Vysny

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
