package ch.epfl.scala.sbtmissinglink

import sbt.ModuleID

/**
 * The text of the report, as lines: pure, so it can be tested. Two layouts.
 *
 * The classic one is sbt-missinglink's own (the conflicts, then the dependencies to exclude), used when every conflict counts, as at class precision.
 *
 * The labelled one is for method precision, where the call graph says which conflicts the project can hit: the ones it can reach (they fail the
 * build) are listed apart from the ones it cannot, and only dependencies whose conflicts are all unreachable are suggested for exclusion, because
 * excluding one the project can hit would hide a break.
 */
private[sbtmissinglink] object ConflictSummary {

  /** Which of the two layouts to print. */
  sealed abstract class Layout
  object Layout {
    case object Classic extends Layout
    case object Labelled extends Layout
  }

  private val UnknownEntry = "<unknown>"

  /** A dependency's conflicts, by what they are. */
  private final case class Counts(classes: Int, members: Int, others: Int) {
    def total: Int = classes + members + others
  }

  private def noun(count: Int): String = if (count == 1) "conflict" else "conflicts"

  private def referenceVerb(count: Int): String = if (count == 1) "references" else "reference"

  private def classCount(n: Int): String = s"$n missing ${if (n == 1) "class" else "classes"}"
  private def memberCount(n: Int): String = s"$n missing ${if (n == 1) "member" else "members"}"

  private def moduleKey(m: ModuleID): (String, String) = (m.organization, m.name)

  /** Each dependency that holds conflicts, with what they are, the most missing classes first. */
  private def byModule(conflicts: List[EngineConflict], modules: Map[String, ModuleID]): List[((String, String), Counts)] =
    conflicts
      .flatMap(c => modules.get(c.usedBy).map(m => moduleKey(m) -> c))
      .groupBy(_._1)
      .map { case (key, grouped) =>
        val cs = grouped.map(_._2)
        key -> Counts(cs.count(_.isMissingClass), cs.count(_.isMissingMember), cs.count(c => !c.isMissingClass && !c.isMissingMember))
      }
      .toList
      .sortBy { case ((org, name), counts) => (-counts.classes, -counts.members, org, name) }

  /** The `missinglinkExcludedDependencies` snippet, each line saying why with `comment`. */
  private def exclusionSnippet(modules: List[((String, String), Counts)], comment: ((String, String), Counts) => String): List[String] =
    List("    <proj>/missinglinkExcludedDependencies ++= List(") ++
      modules.zipWithIndex.map { case (((org, name), counts), index) =>
        val comma = if (index == modules.size - 1) "" else ","
        s"""      moduleFilter(organization = "$org", name = "$name")$comma  // ${comment((org, name), counts)}"""
      } ++
      List("    )")

  private def classicComment(counts: Counts): String = s"${classCount(counts.classes)}, ${memberCount(counts.members)}"

  private def categoryDesc(category: Category): String = category match {
    case Category.ClassNotFound  => "Class being called not found"
    case Category.MethodNotFound => "Method being called not found"
    case other                   => other.name.replace('_', ' ')
  }

  /** `foo(java.lang.String, int)` from a method name and its descriptor. */
  private[sbtmissinglink] def prettyMethod(name: String, descriptor: String): String =
    s"$name(${parameterTypes(descriptor).mkString(", ")})"

  private def parameterTypes(descriptor: String): List[String] = {
    val end = descriptor.indexOf(')')

    def primitive(c: Char): String = c match {
      case 'B'   => "byte"
      case 'C'   => "char"
      case 'D'   => "double"
      case 'F'   => "float"
      case 'I'   => "int"
      case 'J'   => "long"
      case 'S'   => "short"
      case 'Z'   => "boolean"
      case other => other.toString
    }

    @scala.annotation.tailrec
    def from(i: Int, acc: List[String]): List[String] =
      if (i >= end) acc.reverse
      else {
        val dimensions = descriptor.drop(i).takeWhile(_ == '[').length
        val start = i + dimensions
        val (base, next) = descriptor.charAt(start) match {
          case 'L' =>
            val semicolon = descriptor.indexOf(';', start)
            (descriptor.substring(start + 1, semicolon).replace('/', '.'), semicolon + 1)
          case c => (primitive(c), start + 1)
        }
        from(next, (base + ("[]" * dimensions)) :: acc)
      }

    if (end < 0) Nil else from(1, Nil)
  }

  private def callSite(c: EngineConflict): String =
    prettyMethod(c.fromMethodName, c.fromMethodDescriptor) + (if (c.fromLineNumber != 0) ":" + c.fromLineNumber else "")

  private val verbosePointer =
    "Re-run with 'missinglinkVerbose := true' to see the full per-class breakdown " +
      "(which class references each missing symbol, and from which methods)."

  /** The full per-class breakdown of `conflicts`: who references what, from which methods. */
  private def verboseLines(conflicts: List[EngineConflict], layout: Layout): List[String] =
    conflicts.groupBy(_.category).toList.sortBy(_._1.name).flatMap { case (category, inCategory) =>
      List("", "Category: " + categoryDesc(category)) ++
        inCategory.groupBy(_.usedBy).toList.sortBy(_._1).flatMap { case (artifact, inArtifact) =>
          ("  In artifact: " + artifact) +: inArtifact.groupBy(_.fromClass).toList.sortBy(_._1).flatMap { case (className, inClass) =>
            ("    In class: " + className) +: inClass.groupBy(c => (c.describe, c.reason, c.existsIn, c.label)).toList.sortBy(_._1).flatMap {
              case ((describe, reason, existsIn, label), sameProblem) =>
                List("      " + describe, "      Problem: " + reason) ++
                  (if (layout == Layout.Labelled) List("      Reachability: " + label.toLowerCase) else Nil) ++
                  (if (existsIn != UnknownEntry) List("      Found in: " + existsIn) else Nil) ++
                  List("      Referenced from:") ++
                  sameProblem.map(callSite).distinct.sorted.map("        " + _) ++
                  List("      --------")
            }
          }
        }
    }

  /**
   * The report: `shown` are the conflicts that fail the build, `others` the ones the project cannot reach (empty at class precision, where every
   * conflict counts), `modules` the dependencies the entries came from.
   */
  def lines(
      shown: List[EngineConflict],
      others: List[EngineConflict],
      modules: Map[String, ModuleID],
      verbose: Boolean,
      layout: Layout,
      evictions: List[Eviction] = Nil
  ): List[String] = {
    val recap = layout match {
      case Layout.Labelled => labelledLines(shown, others, modules, evictions)
      case Layout.Classic  => classicLines(shown, modules, evictions)
    }
    // The full breakdown can be long: the recap follows it, so the last lines of the output say what it comes to.
    if (verbose) verboseLines(shown, layout) ++ recap.filterNot(_ == verbosePointer) else recap
  }

  private val RequestersShown = 3

  /** Dependency resolution may be what broke them: a version a dependency was built against was replaced by another. Only modules the conflicts involve. */
  private def evictionNote(conflicts: List[EngineConflict], modules: Map[String, ModuleID], evictions: List[Eviction]): List[String] = {
    val involved = conflicts.flatMap(c => List(c.usedBy, c.existsIn).flatMap(modules.get)).map(moduleKey).toSet
    val relevant = evictions.filter(e => involved.contains((e.organization, e.name))).sortBy(e => (e.organization, e.name))
    if (relevant.isEmpty) Nil
    else
      "Versions that dependency resolution replaced, which may be what broke these (see `show <proj>/evicted`):" +:
        relevant.map { e =>
          val wanted = e.evicted.map { v =>
            val who = v.requestedBy.take(RequestersShown).mkString(", ") + (if (v.requestedBy.size > RequestersShown) ", ..." else "")
            s"${v.version} (wanted by $who)"
          }
          s"  - ${e.organization}:${e.name}: ${wanted.mkString("; ")} replaced by ${e.selected}"
        }
  }

  // ---- sbt-missinglink's own layout ----

  private def classicLines(conflicts: List[EngineConflict], modules: Map[String, ModuleID], evictions: List[Eviction]): List[String] = {
    val perModule = byModule(conflicts, modules)
    val header =
      if (perModule.isEmpty) "Missinglink summary: conflicts found."
      else
        s"Missinglink summary: conflicts found in ${perModule.size} " +
          s"${if (perModule.size == 1) "dependency" else "dependencies"}."

    header +: classicBrief(conflicts, perModule, modules, evictions)
  }

  private def classicBrief(conflicts: List[EngineConflict], perModule: List[((String, String), Counts)], modules: Map[String, ModuleID], evictions: List[Eviction]): List[String] = {
    val classNotFound = conflicts.filter(_.isMissingClass)
    val memberNotFound = conflicts.filter(_.isMissingMember)
    val other = conflicts.filterNot(c => c.isMissingClass || c.isMissingMember)

    val kinds = List(
      if (classNotFound.nonEmpty)
        Some(s" * ${classNotFound.size} ${noun(classNotFound.size)} ${referenceVerb(classNotFound.size)} classes missing from the classpath")
      else None,
      if (memberNotFound.nonEmpty)
        Some(s" * ${memberNotFound.size} ${noun(memberNotFound.size)} ${referenceVerb(memberNotFound.size)} members missing from classes already on your classpath")
      else None,
      if (other.nonEmpty)
        Some(s" * ${other.size} ${noun(other.size)} of other kinds " + other.map(c => categoryDesc(c.category)).distinct.sorted.mkString("(", ", ", ")"))
      else None
    ).flatten

    val exclusions =
      if (perModule.isEmpty) Nil
      else "Exclude the dependencies that reference them:" +: exclusionSnippet(perModule, (_, counts) => classicComment(counts))

    kinds ++ exclusions ++ projectLocalNote(conflicts, modules) ++ evictionNote(conflicts, modules, evictions) :+ verbosePointer
  }

  private def projectLocalNote(conflicts: List[EngineConflict], modules: Map[String, ModuleID]): List[String] = {
    val local = conflicts.count(c => !modules.contains(c.usedBy))
    if (local == 0) Nil
    else {
      val verb = if (local == 1) "originates" else "originate"
      List(s"$local ${noun(local)} $verb in your own project and cannot be excluded this way.")
    }
  }

  // ---- the layout for method precision ----

  private def labelledLines(
      shown: List[EngineConflict],
      others: List[EngineConflict],
      modules: Map[String, ModuleID],
      evictions: List[Eviction]
  ): List[String] = {
    val header =
      s"Missinglink summary: ${shown.size} ${noun(shown.size)} your code can reach" +
        (if (others.nonEmpty) s", ${others.size} more that nothing it calls reaches." else ".")

    val detail = if (shown.nonEmpty) reachableSection(shown, modules) else Nil
    List(header) ++ detail ++ unreachableSection(shown, others, modules) ++ evictionNote(shown ++ others, modules, evictions) ++ List(verbosePointer)
  }

  /** What the project can hit: what is broken, in which dependencies, and what to do about it. */
  private def reachableSection(shown: List[EngineConflict], modules: Map[String, ModuleID]): List[String] = {
    val classes = shown.count(_.isMissingClass)
    val members = shown.count(_.isMissingMember)
    val other = shown.size - classes - members
    val perModule = byModule(shown, modules)

    List(
      if (classes > 0) Some(s" * ${classes} ${noun(classes)} ${referenceVerb(classes)} classes missing from the classpath") else None,
      if (members > 0) Some(s" * ${members} ${noun(members)} ${referenceVerb(members)} members missing from classes already on your classpath") else None,
      if (other > 0) Some(s" * ${other} ${noun(other)} of other kinds " + shown.filterNot(c => c.isMissingClass || c.isMissingMember).map(c => categoryDesc(c.category)).distinct.sorted.mkString("(", ", ", ")")) else None
    ).flatten ++
      (if (perModule.isEmpty) Nil
       else
         "These can fail at run time: align the versions of the dependencies below (see `show <proj>/evicted`), or fix the code that calls into them." +:
           perModule.map { case ((org, name), counts) => s"  - $org:$name  (${classCount(counts.classes)}, ${memberCount(counts.members)}${if (counts.others > 0) s", ${counts.others} other" else ""})" }) ++
      projectLocalSites(shown, modules)
  }

  /** Conflicts in the project's own code that it can reach, with where they are: they cannot be fixed by excluding a dependency. */
  private def projectLocalSites(shown: List[EngineConflict], modules: Map[String, ModuleID]): List[String] = {
    val local = shown.filterNot(c => modules.contains(c.usedBy))
    if (local.isEmpty) Nil
    else {
      val sites = local.map(callSite).distinct.sorted
      val verb = if (local.size == 1) "originates" else "originate"
      val more = if (sites.size > 5) List(s"  ... and ${sites.size - 5} more places") else Nil
      List(s"${local.size} ${noun(local.size)} $verb in your own project, which no exclusion can fix:") ++ sites.take(5).map("  - " + _) ++ more
    }
  }

  /** What the project cannot reach: dependencies whose conflicts are all unreachable are safe to exclude. */
  private def unreachableSection(shown: List[EngineConflict], others: List[EngineConflict], modules: Map[String, ModuleID]): List[String] = {
    val withReachable = shown.flatMap(c => modules.get(c.usedBy).map(moduleKey)).toSet
    val safe = byModule(others, modules).filterNot { case (key, _) => withReachable.contains(key) }
    val labelsOf: Map[(String, String), Map[String, Int]] =
      others
        .flatMap(c => modules.get(c.usedBy).map(m => moduleKey(m) -> c.label))
        .groupBy(_._1)
        .map { case (key, grouped) => key -> grouped.map(_._2).groupBy(identity).map { case (label, ls) => label -> ls.size } }

    def comment(key: (String, String)): String =
      labelsOf.getOrElse(key, Map.empty).toList.sortBy { case (label, n) => (-n, label) }.map { case (label, n) => s"$n ${label.toLowerCase}" }.mkString(", ")

    if (safe.isEmpty) Nil
    else
      "Nothing your code calls reaches these (never called, caught, or checked for first), so excluding the dependencies is safe:" +:
        exclusionSnippet(safe, (key, _) => comment(key))
  }
}
