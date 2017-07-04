# Suckless ASCII Profiler

An embedded JVM profiler which you start and stop at will from your code, and it will dump the profiling info into 
the console: @todo screenshot. Uses thread stacktrace sampling, so the overhead is really small. The accuracy
is not that great, but it definitely can identify the bottleneck in your code.

## Usage

Add the following dependency todo.
Then, in your code, just call the following:

```java
final SucklessProfiler p = new SucklessProfiler();
p.start();
callMethodYouNeedToProfile();
p.stop();  // this will dump the profiling info into the console
```
