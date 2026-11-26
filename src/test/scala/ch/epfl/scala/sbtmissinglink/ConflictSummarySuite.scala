package ch.epfl.scala.sbtmissinglink

import sbt.librarymanagement.ModuleID

/** The report text, for sample conflicts: what is listed, what is suggested for exclusion, and what is left out. */
class ConflictSummarySuite extends munit.FunSuite {

  import ConflictSummary.Layout

  private val netty = ModuleID("io.netty", "netty-common", "4.1")
  private val jcl = ModuleID("org.slf4j", "jcl-over-slf4j", "2.0")
  private val modules = Map("io.netty:netty-common:4.1" -> netty, "org.slf4j:jcl-over-slf4j:2.0" -> jcl)

  private def conflict(
      usedBy: String,
      category: String = "CLASS_NOT_FOUND",
      label: String = "REACHABLE",
      method: String = "run",
      line: Int = 12
  ): EngineConflict =
    EngineConflict(
      category = Category.parse(category),
      label = label,
      usedBy = usedBy,
      existsIn = "<unknown>",
      reason = "Class not found",
      describe = s"a.B#$method -> x.Gone",
      fromClass = "a.B",
      fromMethodName = method,
      fromMethodDescriptor = "(Ljava/lang/String;I[J)V",
      fromLineNumber = line,
      failing = label == "REACHABLE" || label == "REFLECTIVE",
      reportedByMissinglink = true
    )

  private val reachable = conflict("io.netty:netty-common:4.1")
  private val unreachable = conflict("org.slf4j:jcl-over-slf4j:2.0", label = "LATENT")

  test("the classic layout is sbt-missinglink's: counts, the dependencies to exclude, the note, the pointer") {
    val lines = ConflictSummary.lines(List(reachable, conflict("io.netty:netty-common:4.1", "METHOD_SIGNATURE_NOT_FOUND")), Nil, modules, verbose = false, layout = Layout.Classic)
    assertEquals(lines.head, "Missinglink summary: conflicts found in 1 dependency.")
    assert(lines.contains(" * 1 conflict references classes missing from the classpath"), lines.toString)
    assert(lines.contains(" * 1 conflict references members missing from classes already on your classpath"), lines.toString)
    assert(lines.contains("""      moduleFilter(organization = "io.netty", name = "netty-common")  // 1 missing class, 1 missing member"""), lines.toString)
    assert(lines.last.startsWith("Re-run with 'missinglinkVerbose := true'"), lines.toString)
  }

  test("the classic layout says what it cannot exclude: a conflict in the project's own code") {
    val own = conflict("project")
    val lines = ConflictSummary.lines(List(own), Nil, modules, verbose = false, layout = Layout.Classic)
    assert(lines.contains("1 conflict originates in your own project and cannot be excluded this way."), lines.toString)
  }

  test("the labelled layout counts what the project can reach apart from what it cannot") {
    val lines = ConflictSummary.lines(List(reachable), List(unreachable, unreachable), modules, verbose = false, layout = Layout.Labelled)
    assertEquals(lines.head, "Missinglink summary: 1 conflict your code can reach, 2 more that nothing it calls reaches.")
  }

  test("only a dependency whose conflicts are all unreachable is suggested for exclusion") {
    val lines = ConflictSummary.lines(List(reachable), List(unreachable, conflict("io.netty:netty-common:4.1", label = "PROBED")), modules, verbose = false, layout = Layout.Labelled)
    val exclusions = lines.filter(_.contains("moduleFilter"))
    assertEquals(exclusions.size, 1, lines.toString)
    assert(exclusions.head.contains("jcl-over-slf4j"), "the one with a conflict the project can reach is not suggested: " + exclusions)
    assert(exclusions.head.endsWith("// 1 latent"), exclusions.head)
    assert(lines.exists(_.contains("excluding the dependencies is safe")), lines.toString)
  }

  test("a dependency the project can reach is listed to be fixed, with its counts, not to be excluded") {
    val lines = ConflictSummary.lines(List(reachable), Nil, modules, verbose = false, layout = Layout.Labelled)
    assert(lines.exists(_.startsWith("These can fail at run time")), lines.toString)
    assert(lines.contains("  - io.netty:netty-common  (1 missing class, 0 missing members)"), lines.toString)
    assert(!lines.exists(_.contains("moduleFilter")), "nothing reachable is suggested for exclusion: " + lines)
  }

  test("the verb agrees with the count, in both layouts") {
    val member = conflict("io.netty:netty-common:4.1", "METHOD_SIGNATURE_NOT_FOUND")
    val one = ConflictSummary.lines(List(member), Nil, modules, verbose = false, layout = Layout.Labelled)
    assert(one.contains(" * 1 conflict references members missing from classes already on your classpath"), one.toString)
    val two = ConflictSummary.lines(List(member, member.copy(fromClass = "b.B")), Nil, modules, verbose = false, layout = Layout.Labelled)
    assert(two.contains(" * 2 conflicts reference members missing from classes already on your classpath"), two.toString)
  }

  test("a conflict in the project's own code is shown with where it is") {
    val lines = ConflictSummary.lines(List(conflict("project", method = "go", line = 40)), Nil, modules, verbose = false, layout = Layout.Labelled)
    assert(lines.contains("1 conflict originates in your own project, which no exclusion can fix:"), lines.toString)
    assert(lines.contains("  - go(java.lang.String, int, long[]):40"), lines.toString)
  }

  test("the verbose layout says how reachable each conflict is, in the labelled layout only") {
    val labelled = ConflictSummary.lines(List(reachable), Nil, modules, verbose = true, layout = Layout.Labelled)
    assert(labelled.contains("      Reachability: reachable"), labelled.toString)
    val classic = ConflictSummary.lines(List(reachable), Nil, modules, verbose = true, layout = Layout.Classic)
    assert(!classic.exists(_.contains("Reachability")), classic.toString)
  }

  private val nettyEviction = Eviction("io.netty", "netty-common", "4.1", List(EvictedVersion("4.0", List("a:one", "b:two", "c:three", "d:four"))))

  test("with the verbose output, a version that resolution replaced is named when a conflict involves that module, in both layouts") {
    for (layout <- List(Layout.Classic, Layout.Labelled)) {
      val lines = ConflictSummary.lines(List(reachable), Nil, modules, verbose = true, layout = layout, evictions = List(nettyEviction))
      assert(lines.exists(_.startsWith("Versions that dependency resolution replaced")), s"$layout: $lines")
      assert(lines.contains("  - io.netty:netty-common: 4.0 (wanted by a:one, b:two, c:three, ...) replaced by 4.1"), s"$layout: $lines")
    }
  }

  test("the brief output leaves the replaced versions to the verbose one, in both layouts") {
    for (layout <- List(Layout.Classic, Layout.Labelled)) {
      val lines = ConflictSummary.lines(List(reachable), Nil, modules, verbose = false, layout = layout, evictions = List(nettyEviction))
      assert(!lines.exists(_.contains("replaced")), s"$layout: $lines")
    }
  }

  test("a replaced version of a module no conflict involves is not mentioned") {
    val unrelated = Eviction("org.other", "lib", "2", List(EvictedVersion("1", List("x:y"))))
    val lines = ConflictSummary.lines(List(reachable), List(unrelated).map(_ => unreachable), modules, verbose = true, layout = Layout.Labelled, evictions = List(unrelated))
    assert(!lines.exists(_.contains("replaced")), lines.toString)
  }

  test("a module that holds only unreachable conflicts still gets the note") {
    val lines = ConflictSummary.lines(List(reachable), List(unreachable), modules, verbose = true, layout = Layout.Labelled, evictions = List(Eviction("org.slf4j", "jcl-over-slf4j", "2.0", List(EvictedVersion("1.7", List("a:one"))))))
    assert(lines.contains("  - org.slf4j:jcl-over-slf4j: 1.7 (wanted by a:one) replaced by 2.0"), lines.toString)
  }

  test("the verbose breakdown lists artifacts and classes in sorted order") {
    val artifacts = List("f:art", "e:art", "d:art", "c:art", "b:art", "a:art")
    val conflicts = artifacts.map(a => conflict(a).copy(fromClass = s"x.${a.take(1)}"))
    val lines = ConflictSummary.lines(conflicts, Nil, modules, verbose = true, layout = Layout.Classic)
    assertEquals(lines.filter(_.startsWith("  In artifact: ")).map(_.stripPrefix("  In artifact: ")), artifacts.sorted)
    assertEquals(lines.filter(_.startsWith("    In class: ")).map(_.stripPrefix("    In class: ")), artifacts.sorted.map(a => s"x.${a.take(1)}"))
  }

  test("a category the plugin does not know is kept by name and described from it") {
    assertEquals(Category.parse("CLASS_NOT_FOUND"), Category.ClassNotFound)
    assertEquals(Category.parse("FIELD_NOT_FOUND"), Category.FieldNotFound)
    val other = conflict("io.netty:netty-common:4.1", category = "ABSTRACT_METHOD_NOT_IMPLEMENTED")
    assertEquals(other.category, Category.Other("ABSTRACT_METHOD_NOT_IMPLEMENTED"))
    assert(!other.isMissingClass && !other.isMissingMember)
    val lines = ConflictSummary.lines(List(other), Nil, modules, verbose = false, layout = Layout.Classic)
    assert(lines.exists(_.contains("ABSTRACT METHOD NOT IMPLEMENTED")), lines.toString)
  }

  test("a method descriptor is shown as its parameter types") {
    assertEquals(ConflictSummary.prettyMethod("m", "([[Ljava/util/List;ZD)V"), "m(java.util.List[][], boolean, double)")
    assertEquals(ConflictSummary.prettyMethod("m", "()V"), "m()")
  }
}
