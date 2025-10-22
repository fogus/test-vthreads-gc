# Test harness for core.async vthreads gc

## Compile

To compile:

```
clj -T:build co :vthreads '"target"'
```

You can examine the emitted classes to see how it was compiled. If you see state_machine classes, it was IOC compiled:

```
$ ls target/classes | grep main
main$_main$fn__8321$G__8316__8322.class
main$_main$fn__8321$state_machine__5674__auto____8324$fn__8326.class
main$_main$fn__8321$state_machine__5674__auto____8324.class
...
```

If not, it was compiled to a vthread-style go block.

## Run from compiled 

```
clj -M:test:nosrc use-alts|flood*
```

Default is running with go blocks only.

## Monitoring

To capture a GC histogram:

    jcmd PID GC.class_histogram | head

To start a recording:

    jcmd PID JFR.start name=leak settings=profile duration=5m filename=/tmp/leak.jfr include=OldObjectSample
