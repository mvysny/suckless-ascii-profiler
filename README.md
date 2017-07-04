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

Add the following dependency todo.
Then, in your code, just call the following:

```java
final SucklessProfiler p = new SucklessProfiler();
p.start();
callMethodYouNeedToProfile();
p.stop();  // this will dump the profiling info into the console
```

or in Kotlin:

```kotlin
fun main(args: Array<String>) {
    SucklessProfiler().apply {
        coloredDump = true
    }.profile {
        Thread.sleep(500)
        println(URL("https://aedict-online.eu").readText())
        Thread.sleep(500)
    }
}
```
