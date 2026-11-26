# sbt-missinglink

An sbt plugin that checks the dependencies of a project for links that cannot work at run time (a method, field or class that is gone, a changed access flag, a class that became `final`, ...) and fails the build on the ones your code can reach. Based on [missinglink](https://github.com/spotify/missinglink); the checker is reachable-linkage.

It supports sbt 1 and sbt 2.

## Usage

Add the following line in `project/plugins.sbt`:

```scala
addSbtPlugin("ch.epfl.scala" % "sbt-missinglink" % "<sbt-missinglink-version>")
```

The checker, reachable-linkage, comes with the plugin; nothing else needs to be on the build classpath.

Then, run the following task for the project you want to test:

```
> theProject/missinglinkCheck
```

It starts from the classes of your project (its `Compile` configuration, in `src/main/`), follows the call graph through your dependencies, and fails the build if a broken link can be reached. Broken links that nothing your code calls reaches
are reported apart and do not fail it.

### Testing another configuration

You can test another configuration, such as `Test` or `Runtime`, with:


```
> theProject/Runtime/missinglinkCheck
```

### Precision

`missinglinkPrecision` is `Method` (the default) or `Class`:

- `Method` labels each conflict by whether your code can reach it: reachable, reflective (only a framework or reflection rule reaches it), latent (never called), guarded (inside a `catch` for that error) or probed (the code checks for the class first).
  The build fails on reachable and reflective ones.
- `Class` has no call graph: every conflict counts, and only the three kinds sbt-missinglink always reported (missing class, method and field) are shown, in its report layout.

### Scanning the dependencies

```
missinglinkScanDependencies := true
```

Starts from every class on the classpath instead of your project's, and fails on every conflict, even one nothing calls. It implies `Class` precision unless `missinglinkPrecision` is set.

### Do not fail on conflicts

By default, the plugin fails the build if any conflicts are found.
It can be disabled by the `missinglinkFailOnConflicts` setting:

```
missinglinkFailOnConflicts := false
```

### Your own frameworks

Frameworks call code by reflection, so nothing in the classpath names it. Guice, Spring and Spring Boot, Jackson, Gson, servlet containers, Akka, Pekko and jboss-logging are built in. For your own, list rules in a file:

```
missinglinkKeepRulesFile := Some(baseDirectory.value / "linkage-rules.txt")
```

```
keep-members com.acme.Wired        # a method or field the container uses
```

The directives are described in the reachable-linkage README.

### Ignore conflicts in certain packages

Conflicts can be ignored based on the package name of the class that has the conflict.
There are separate configuration options for ignoring conflicts on the "source" side of the conflict and the "destination" side of the conflict.
Packages on the source side can be ignored with `missinglinkIgnoreSourcePackages` and packages on the destination side can be ignored with `missinglinkIgnoreDestinationPackages`:

```
missinglinkIgnoreDestinationPackages += IgnoredPackage("com.google.common")
missinglinkIgnoreSourcePackages += IgnoredPackage("com.example")
```

By default, all subpackages of the specified package are also ignored, but this can be disabled by the `ignoreSubpackages` field: `IgnoredPackage("test", ignoreSubpackages = false)`.
`missinglinkTargetSourcePackages` and `missinglinkTargetDestinationPackages` do the opposite: they keep only the conflicts in the packages named, with `TargetedPackage`.

### Understanding the conflict report

By default `missinglinkCheck` reports the total conflict count, then a concise summary. At `Method` precision it says how many conflicts your code can reach and how many it cannot, which dependencies hold the reachable ones, and a ready-to-paste snippet
that excludes only the dependencies whose conflicts are all unreachable. A reachable conflict in your own project is shown with its method and line.

When a conflict involves a dependency whose version resolution replaced, the report says so:

```
Versions that dependency resolution replaced, which may be what broke these (see `show <proj>/evicted`):
  - com.google.guava:guava: 14.0 (wanted by has-problematic-dependency:has-problematic-dependency_2.12) replaced by 18.0
```

Conflicts are either a missing class (`CLASS_NOT_FOUND`), a missing member on a class that *is* present (`METHOD_SIGNATURE_NOT_FOUND`, `FIELD_NOT_FOUND`), or one of the other kinds of link the checker follows. Each conflict is attributed to the JAR whose
bytecode makes the reference.

Set `missinglinkVerbose := true` for the full per-class, per-call-site breakdown.

### Excluding some dependencies from the analysis

You can exclude certain dependencies using `moduleFilter`:

```
missinglinkExcludedDependencies += moduleFilter(organization = "com.google.guava")
missinglinkExcludedDependencies += moduleFilter(organization = "ch.qos.logback", name = "logback-core")
```

### Where the checker keeps its cache

The checker summarises each jar once and reuses the summary on later runs, under `target/reachable-linkage` by default. `missinglinkCacheDirectory := None` writes nothing.

### Limiting the concurrency

sbt runs the missing-link analysis on the modules you have concurrently.
Analysis of each module can take up a considerable amount of memory,
so you might want to limit the degree of concurrency.
To run missing-link at most on 4 projects at a time, add this setting to your project `root`.

```scala
concurrentRestrictions += Tags.limit(missinglinkConflictsTag, 4)
```

## More information

You can find more information about the problem statement, caveats and
limitations, etc. in the upstream project
[missinglink](https://github.com/spotify/missinglink).

## Acknowledgments

<a title="Scala Center" href="https://scala.epfl.ch/"><img alt="Scala Center" src="https://scala.epfl.ch/resources/img/scala-center-logo-black.png" height="60" /></a>

This project is funded by the <a title="Scala Center" href="https://scala.epfl.ch/">Scala Center</a>.
