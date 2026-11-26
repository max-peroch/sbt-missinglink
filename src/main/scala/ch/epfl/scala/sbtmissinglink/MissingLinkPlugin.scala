package ch.epfl.scala.sbtmissinglink

import sbt._
import sbt.Keys._
import sbt.librarymanagement.ModuleFilter
import sbt.plugins.JvmPlugin

import sbtcompat.PluginCompat

import java.net.URLClassLoader
import java.util.{Arrays, Collections, HashMap => JHashMap, List => JList, Map => JMap}

import scala.collection.JavaConverters._
import scala.collection.immutable.Map

import xsbti.FileConverter

object MissingLinkPlugin extends AutoPlugin {

  object autoImport {

    final case class IgnoredPackage(name: String, ignoreSubpackages: Boolean = true)
        extends PackageFilter {
      override def apply(packageName: String): Boolean =
        packageName != name && (!ignoreSubpackages || !packageName.startsWith(s"$name."))
    }
    private[sbtmissinglink] implicit object IgnoredPackages extends PackageFilters[IgnoredPackage] {
      def apply(name: String)(filters: Seq[IgnoredPackage]): Boolean = filters.forall(_.apply(name))
    }

    final case class TargetedPackage(name: String, targetSubpackages: Boolean = true)
        extends PackageFilter {
      override def apply(packageName: String): Boolean =
        packageName == name || (targetSubpackages && packageName.startsWith(s"$name."))
    }

    private[sbtmissinglink] implicit object TargetedPackages
        extends PackageFilters[TargetedPackage] {
      def apply(name: String)(filters: Seq[TargetedPackage]): Boolean =
        filters.exists(_.apply(name))
    }

    @transient
    val missinglinkCheck: TaskKey[Unit] =
      taskKey[Unit]("Run the missinglink checks")

    val missinglinkFailOnConflicts: SettingKey[Boolean] =
      settingKey[Boolean]("Fail the build if any conflicts are found")

    val missinglinkScanDependencies: SettingKey[Boolean] =
      settingKey[Boolean]("Also scan all dependencies")

    val missinglinkIgnoreSourcePackages: SettingKey[Seq[IgnoredPackage]] =
      settingKey[Seq[IgnoredPackage]](
        "Optional list of packages to ignore conflicts where the source of the conflict " +
          "is in one of the specified packages."
      )

    val missinglinkTargetSourcePackages: SettingKey[Seq[TargetedPackage]] =
      settingKey[Seq[TargetedPackage]](
        "Optional list of source packages to specifically target conflicts in. " +
          "Cannot be used with missinglinkIgnoreSourcePackages."
      )

    val missinglinkIgnoreDestinationPackages: SettingKey[Seq[IgnoredPackage]] =
      settingKey[Seq[IgnoredPackage]](
        "Optional list of packages to ignore conflicts where the destination/called-side " +
          "of the conflict is in one of the specified packages."
      )

    val missinglinkTargetDestinationPackages: SettingKey[Seq[TargetedPackage]] =
      settingKey[Seq[TargetedPackage]](
        "Optional list of source packages to specifically target conflicts in. " +
          "Cannot be used with missinglinkIgnoreDestinationPackages."
      )

    val missinglinkExcludedDependencies =
      settingKey[Seq[ModuleFilter]]("Dependencies that are excluded from analysis")

    val missinglinkVerbose: SettingKey[Boolean] =
      settingKey[Boolean](
        "Print the full per-class, per-call-site breakdown of every conflict. " +
          "When false (the default), only the concise summary is printed."
      )
  }

  import autoImport._

  override def requires: Plugins = JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  // Make it easy to throttle the concurrency of running missing-link on multiple projects, it consumes a lot of memory
  val missinglinkConflictsTag = Tags.Tag("missinglinkConflicts")


  val configSettings: Seq[Setting[?]] = Def.settings(
    missinglinkCheck := Def
      .task {
        val log = streams.value.log

        implicit val converter: FileConverter = fileConverter.value
        val cp = fullClasspath.value
        val classDir = (Compile / classDirectory).value
        val engineJars = resolveEngine(log)
        val failOnConflicts = missinglinkFailOnConflicts.value
        val scanDependencies = missinglinkScanDependencies.value
        val verbose = missinglinkVerbose.value
        assert(
          missinglinkIgnoreSourcePackages.value.isEmpty || missinglinkTargetSourcePackages.value.isEmpty,
          "ignoreSourcePackages and targetSourcePackages cannot be defined in the same project."
        )

        assert(
          missinglinkIgnoreDestinationPackages.value.isEmpty || missinglinkTargetDestinationPackages.value.isEmpty,
          "ignoreDestinationPackages and targetDestinationPackages cannot be defined in the same project."
        )

        val includedModules =
          missinglinkExcludedDependencies.value.foldLeft[ModuleFilter](_ => true)((k, v) => k - v)

        val entries = classpathEntries(cp, classDir)
        val modules: Map[String, ModuleID] = entries.collect { case SbtEntry(name, _, Some(m)) => name -> m }.toMap
        val excluded = entries.collect { case e if e.module.exists(m => !includedModules(m)) => e.name }.toSet

        if (!hasClasses(classDir)) {
          log.warn(
            "No classes found in project build directory" +
              " - did you run 'sbt compile' first?"
          )
        }

        val request = engineRequest(
          classDir,
          entries,
          excluded,
          scanDependencies,
          missinglinkIgnoreSourcePackages.value.map(p => (p.name, p.ignoreSubpackages)),
          missinglinkTargetSourcePackages.value.map(p => (p.name, p.targetSubpackages)),
          missinglinkIgnoreDestinationPackages.value.map(p => (p.name, p.ignoreSubpackages)),
          missinglinkTargetDestinationPackages.value.map(p => (p.name, p.targetSubpackages))
        )
        val result = EngineBridge.check(engineJars, request)
        result.warnings.foreach(w => log.warn(w))
        logFilterEffects(result, log)

        // Scanning the dependencies keeps sbt-missinglink's own view: the three kinds of conflict it reports. Otherwise
        // the build is about what the project can reach.
        val conflicts: Seq[EngineConflict] =
          if (scanDependencies) result.conflicts.filter(_.reportedByMissinglink)
          else result.conflicts.filter(_.failing)

        if (conflicts.nonEmpty) {
          val filteredTotal = conflicts.length
          val removedByFilters = result.filtered.map(_._2).sum

          val diffMessage =
            if (removedByFilters != 0)
              s" (${filteredTotal + removedByFilters} conflicts were found before applying filters)"
            else
              ""

          val conflictNoun = if (filteredTotal == 1) "conflict" else "conflicts"
          log.info(s"$filteredTotal $conflictNoun found!$diffMessage")

          outputConflicts(conflicts, modules, verbose, log)

          if (failOnConflicts) {
            val verb = if (filteredTotal == 1) "was" else "were"
            throw new MessageOnlyException(s"There $verb $filteredTotal $conflictNoun")
          }
        } else {
          log.info("No conflicts found")
        }

        val notReachable = result.conflicts.size - conflicts.size
        if (!scanDependencies && notReachable > 0) {
          log.info(
            s"$notReachable more ${if (notReachable == 1) "conflict is" else "conflicts are"} not reachable from your code " +
              "(never called, guarded by a catch, or probed for first) and do not fail the build."
          )
        }
      }
      .tag(missinglinkConflictsTag)
      .value,
  )

  override def globalSettings: Seq[Def.Setting[?]] = Seq(
    missinglinkFailOnConflicts := true,
    missinglinkScanDependencies := false,
    missinglinkIgnoreSourcePackages := Nil,
    missinglinkTargetSourcePackages := Nil,
    missinglinkIgnoreDestinationPackages := Nil,
    missinglinkTargetDestinationPackages := Nil,
    missinglinkExcludedDependencies := Nil,
    missinglinkVerbose := false,
  )


  override def projectSettings: Seq[Setting[?]] = {
    inConfig(Compile)(configSettings) ++
      inConfig(Runtime)(configSettings) ++
      inConfig(Test)(configSettings)
  }

  private val engineVersion = "0.1.0-SNAPSHOT"

  /**
   * The checker (reachable-linkage-core) and everything it needs, fetched on its own with Coursier, outside the project's resolution: sbt forces the
   * project's Scala library onto every dependency it resolves (2.12 on an sbt 1 build), and the checker needs its own, Scala 2.13 and Scala 3.
   */
  private def resolveEngine(log: Logger): Seq[File] = {
    val engine = coursierapi.Dependency.of("io.github.reachable-linkage", "reachable-linkage-core_3", engineVersion)
    val jars =
      try {
        coursierapi.Fetch
          .create()
          .addDependencies(engine)
          .withRepositories(coursierapi.Repository.ivy2Local(), coursierapi.Repository.central())
          .fetch()
          .asScala
          .toList
      } catch {
        case e: coursierapi.error.CoursierError =>
          throw new MessageOnlyException(s"Could not resolve the reachable-linkage engine $engine: ${e.getMessage}")
      }
    log.debug("reachable-linkage engine: " + jars.map(_.getName).mkString(", "))
    jars
  }

  /** A classpath entry: the name conflicts are attributed to, where it is, and the module sbt knows it as. */
  private final case class SbtEntry(name: String, path: File, module: Option[ModuleID])

  private def moduleName(m: ModuleID): String = s"${m.organization}:${m.name}:${m.revision}"

  /** The jars and class directories of the classpath, except the project's own class directory (it is passed on its own). */
  private def classpathEntries(cp: Classpath, classDirectory: File)(implicit
      converter: FileConverter
  ): List[SbtEntry] = {
    def isValid(entry: File): Boolean =
      (entry.isFile() && entry.getPath().endsWith(".jar")) || entry.isDirectory()

    val projectDir = classDirectory.getCanonicalFile
    cp.toList
      .map(c => c -> PluginCompat.toFile(c.data))
      .filter { case (_, f) => isValid(f) && f.getCanonicalFile != projectDir }
      .map { case (c, f) =>
        val module = c.get(PluginCompat.moduleIDStr).map(PluginCompat.parseModuleIDStrAttribute)
        SbtEntry(module.fold(f.getPath)(moduleName), f, module)
      }
  }

  private def hasClasses(classDirectory: File): Boolean =
    (classDirectory ** "*.class").get().nonEmpty

  private def stringList(values: Seq[String]): JList[String] = values.asJava

  private def pairs(values: Seq[(String, String)]): JList[JList[String]] =
    values.map { case (a, b) => Arrays.asList(a, b) }.asJava

  /**
   * Scanning the dependencies is sbt-missinglink's own mode: every class of every checked entry is a root and every
   * conflict counts, as missinglink-core saw them. Otherwise the project's classes are the roots and the call graph
   * says which conflicts they can reach.
   */
  private def engineRequest(
      classDirectory: File,
      entries: List[SbtEntry],
      excluded: Set[String],
      scanDependencies: Boolean,
      ignoreSource: Seq[(String, Boolean)],
      targetSource: Seq[(String, Boolean)],
      ignoreDestination: Seq[(String, Boolean)],
      targetDestination: Seq[(String, Boolean)]
  ): JMap[String, AnyRef] = {
    def packages(selectors: Seq[(String, Boolean)]): JList[JList[String]] =
      pairs(selectors.map { case (name, subpackages) => (name, subpackages.toString) })

    val request = new JHashMap[String, AnyRef]()
    request.put("projectClasses", Collections.singletonList(classDirectory.getAbsolutePath))
    request.put("classpath", pairs(entries.map(e => (e.name, e.path.getAbsolutePath))))
    request.put("scope", if (scanDependencies) "Full" else "App")
    request.put("precision", if (scanDependencies) "Class" else "Method")
    request.put("excludedEntries", stringList(excluded.toList))
    request.put("ignoreSourcePackages", packages(ignoreSource))
    request.put("targetSourcePackages", packages(targetSource))
    request.put("ignoreDestinationPackages", packages(ignoreDestination))
    request.put("targetDestinationPackages", packages(targetDestination))
    request
  }

  /** What each package filter that was set removed, as the plugin has always said it. */
  private def logFilterEffects(result: EngineResult, log: Logger): Unit =
    result.filtered.filter(_._2 != 0).foreach { case (kind, removed) =>
      val (name, setting) = kind match {
        case "IGNORE_SOURCE_PACKAGES"      => ("source", "missinglinkIgnoreSourcePackages")
        case "TARGET_SOURCE_PACKAGES"      => ("source", "missinglinkTargetSourcePackages")
        case "IGNORE_DESTINATION_PACKAGES" => ("destination", "missinglinkIgnoreDestinationPackages")
        case _                             => ("destination", "missinglinkTargetDestinationPackages")
      }
      log.info(
        s"""
          |$removed conflicts found in ignored ${name} packages.
          |Run plugin again without the '$setting' setting to see all conflicts that were found.
           """.stripMargin
      )
    }

  /** `foo(java.lang.String, int)` from a method name and its descriptor. */
  private def prettyMethod(name: String, descriptor: String): String =
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

  private val UnknownEntry = "<unknown>"

  private def outputConflicts(
      conflicts: Seq[EngineConflict],
      modules: Map[String, ModuleID],
      verbose: Boolean,
      log: Logger
  ): Unit = {
    def logLine(msg: String): Unit =
      log.error(msg)

    def conflictNoun(count: Int): String = if (count == 1) "conflict" else "conflicts"
    def referenceVerb(count: Int): String = if (count == 1) "references" else "reference"

    val descriptions = Map(
      "CLASS_NOT_FOUND" -> "Class being called not found",
      "METHOD_SIGNATURE_NOT_FOUND" -> "Method being called not found",
    )

    def categoryDesc(category: String): String =
      descriptions.getOrElse(category, category.replace('_', ' '))

    def optionalLineNumber(lineNumber: Int): String =
      if (lineNumber != 0) ":" + lineNumber else ""

    // Attribute every conflict to the JAR that references the missing symbol
    val byModule: Seq[((String, String), (Int, Int))] =
      conflicts
        .flatMap(c => modules.get(c.usedBy).map(_ -> c.category))
        .groupBy { case (module, _) => (module.organization, module.name) }
        .map { case (key, grouped) =>
          val missingClasses = grouped.count(_._2 == "CLASS_NOT_FOUND")
          key -> ((missingClasses, grouped.size - missingClasses))
        }
        .toSeq
        .sortBy { case ((org, name), (classes, members)) => (-classes, -members, org, name) }

    logLine(
      if (byModule.isEmpty) "Missinglink summary: conflicts found."
      else
        s"Missinglink summary: conflicts found in ${byModule.size} " +
          s"${if (byModule.size == 1) "dependency" else "dependencies"}."
    )

    if (verbose) {
      for ((category, conflictsInCategory) <- conflicts.groupBy(_.category)) {
        logLine("")
        logLine("Category: " + categoryDesc(category))

        // next group by artifact containing the conflict
        val byArtifact = conflictsInCategory.groupBy(_.usedBy)

        for ((artifactName, conflictsInArtifact) <- byArtifact) {
          logLine("  In artifact: " + artifactName)

          // next group by class containing the conflict
          val byClassName = conflictsInArtifact.groupBy(_.fromClass)

          for ((className, conflictsInClass) <- byClassName) {
            logLine("    In class: " + className)

            // collapse all call sites that share the same missing target + reason
            val byProblem = conflictsInClass.groupBy { conflict =>
              (conflict.describe, conflict.reason, conflict.existsIn)
            }

            for (((describe, reason, existsIn), groupedConflicts) <- byProblem) {
              logLine("      " + describe)
              logLine("      Problem: " + reason)
              if (existsIn != UnknownEntry)
                logLine("      Found in: " + existsIn)

              val callSites = groupedConflicts
                .map { conflict =>
                  prettyMethod(conflict.fromMethodName, conflict.fromMethodDescriptor) +
                    optionalLineNumber(conflict.fromLineNumber)
                }
                .distinct
                .sorted

              logLine("      Referenced from:")
              for (callSite <- callSites)
                logLine("        " + callSite)
              logLine("      --------")
            }
          }
        }
      }
    } else {
      val classNotFound = conflicts.filter(_.category == "CLASS_NOT_FOUND")
      val memberNotFound = conflicts.filter(c => c.category == "METHOD_SIGNATURE_NOT_FOUND" || c.category == "FIELD_NOT_FOUND")
      val other = conflicts.filterNot(c => classNotFound.contains(c) || memberNotFound.contains(c))

      def classCount(n: Int): String = s"$n missing ${if (n == 1) "class" else "classes"}"
      def memberCount(n: Int): String = s"$n missing ${if (n == 1) "member" else "members"}"

      if (classNotFound.nonEmpty)
        logLine(
          s" * ${classNotFound.size} ${conflictNoun(classNotFound.size)} " +
            s"${referenceVerb(classNotFound.size)} classes missing from the classpath"
        )
      if (memberNotFound.nonEmpty)
        logLine(
          s" * ${memberNotFound.size} ${conflictNoun(memberNotFound.size)} " +
            s"${referenceVerb(memberNotFound.size)} members missing from classes already on your classpath"
        )
      if (other.nonEmpty)
        logLine(
          s" * ${other.size} ${conflictNoun(other.size)} of other kinds " +
            other.map(c => categoryDesc(c.category)).distinct.sorted.mkString("(", ", ", ")")
        )

      if (byModule.nonEmpty) {
        logLine("Exclude the dependencies that reference them:")
        logLine("    <proj>/missinglinkExcludedDependencies ++= List(")
        byModule.zipWithIndex.foreach { case (((org, name), (classes, members)), index) =>
          val comma = if (index == byModule.size - 1) "" else ","
          logLine(
            s"""      moduleFilter(organization = "$org", name = "$name")$comma""" +
              s"  // ${classCount(classes)}, ${memberCount(members)}"
          )
        }
        logLine("    )")
      }

      val projectLocal =
        conflicts.count(c => !modules.contains(c.usedBy))
      if (projectLocal != 0) {
        val verb = if (projectLocal == 1) "originates" else "originate"
        logLine(
          s"$projectLocal ${conflictNoun(projectLocal)} $verb in your own project and cannot " +
            "be excluded this way."
        )
      }

      logLine(
        "Re-run with 'missinglinkVerbose := true' to see the full per-class breakdown " +
          "(which class references each missing symbol, and from which methods)."
      )
    }
  }

  /** One conflict as the checker reports it over the bridge. */
  private final case class EngineConflict(
      category: String,
      label: String,
      usedBy: String,
      existsIn: String,
      reason: String,
      describe: String,
      fromClass: String,
      fromMethodName: String,
      fromMethodDescriptor: String,
      fromLineNumber: Int,
      failing: Boolean,
      reportedByMissinglink: Boolean
  )

  private final case class EngineResult(
      warnings: Seq[String],
      filtered: Seq[(String, Int)],
      conflicts: Seq[EngineConflict]
  )

  /**
   * Calls the checker through its one static bridge method, which takes and returns only `java.util` maps, lists and strings. The checker is in a
   * classloader of its own (its parent is the JDK's, not sbt's), so its Scala 3 and 2.13 libraries cannot meet this plugin's.
   */
  private object EngineBridge {
    private val BridgeClass = "reachablelinkage.api.LinkageBridge"

    def check(engineJars: Seq[File], request: JMap[String, AnyRef]): EngineResult = {
      if (engineJars.isEmpty)
        throw new MessageOnlyException("The reachable-linkage engine could not be resolved (is it published, and is the resolver set up?)")
      val loader = new URLClassLoader(engineJars.map(_.toURI.toURL).toArray, ClassLoader.getSystemClassLoader.getParent)
      try {
        val response = loader.loadClass(BridgeClass).getMethod("check", classOf[JMap[AnyRef, AnyRef]]).invoke(null, request).asInstanceOf[JMap[String, AnyRef]]
        decode(response)
      } catch {
        case e: java.lang.reflect.InvocationTargetException =>
          throw new MessageOnlyException(s"The reachable-linkage engine failed: ${e.getCause}")
      } finally loader.close()
    }

    private def decode(response: JMap[String, AnyRef]): EngineResult = {
      Option(response.get("error")).foreach(message => throw new MessageOnlyException(s"reachable-linkage: $message"))

      def list(key: String): Seq[AnyRef] = response.get(key).asInstanceOf[JList[AnyRef]].asScala.toList
      def text(m: JMap[String, AnyRef], key: String): String = String.valueOf(m.get(key))
      def flag(m: JMap[String, AnyRef], key: String): Boolean = m.get(key).asInstanceOf[java.lang.Boolean].booleanValue

      EngineResult(
        warnings = list("warnings").map(_.toString),
        filtered = list("filtered").map { pair =>
          val p = pair.asInstanceOf[JList[String]]
          (p.get(0), p.get(1).toInt)
        },
        conflicts = list("conflicts").map { entry =>
          val m = entry.asInstanceOf[JMap[String, AnyRef]]
          EngineConflict(
            category = text(m, "category"),
            label = text(m, "label"),
            usedBy = text(m, "usedBy"),
            existsIn = text(m, "existsIn"),
            reason = text(m, "reason"),
            describe = text(m, "describe"),
            fromClass = text(m, "fromClass"),
            fromMethodName = text(m, "fromMethodName"),
            fromMethodDescriptor = text(m, "fromMethodDescriptor"),
            fromLineNumber = m.get("fromLineNumber").asInstanceOf[java.lang.Integer].intValue,
            failing = flag(m, "failing"),
            reportedByMissinglink = flag(m, "reportedByMissinglink")
          )
        }
      )
    }
  }

  private[sbtmissinglink] sealed trait PackageFilter {
    def apply(name: String): Boolean
  }
  private[sbtmissinglink] trait PackageFilters[T <: PackageFilter] {
    def apply(name: String)(filters: Seq[T]): Boolean
  }
}
