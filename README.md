# Suckless ASCII Profiler

An embedded JVM profiler which you start and stop at will from your code, and it will dump the profiling info into 
the console:

![Profiler Console](docs/images/profiler_console.png)

* Uses thread stacktrace sampling, so the overhead is really small. The accuracy
is not that great, but it definitely can identify the bottleneck in your code.
* IDE turns the console log into clickable links so you can navigate into your code easily

## Demo

We're going to profile the [sample main method](tree/master/src/main/kotlin/com/github/mvysny/sucklessprofiler/SucklessProfiler.kt#L252) which looks like this:
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

Add the following repository and dependency to your gradle script:

```groovy
repositories {
    maven { url "https://dl.bintray.com/mvysny/github/" }
}
dependencies {
    compile "com.github.mvysny.sucklessprofiler:suckless-profiler:0.2"
}
```

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
}
```

or in your Vaadin Servlet:

```kotlin
@WebServlet(urlPatterns = arrayOf("/*"), name = "MyUIServlet", asyncSupported = true)
@VaadinServletConfiguration(ui = MyUI::class, productionMode = false)
class MyUIServlet : VaadinServlet() {
    override fun service(request: HttpServletRequest?, response: HttpServletResponse?) {
        SucklessProfiler().apply {
            coloredDump = true
            pruneStacktraceBottom = true
        }.profile {
            super.service(request, response)
        }
    }
}
```
