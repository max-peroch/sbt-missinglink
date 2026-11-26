package ch.epfl.scala.sbtmissinglink

import sbt._
import sbt.Keys._
import sbt.librarymanagement.{ModuleDescriptorConfiguration, ModuleFilter}
import sbt.plugins.JvmPlugin

import sbtcompat.PluginCompat

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
      settingKey[Boolean](
        "Also scan all dependencies: start from every class on the classpath and fail on every conflict, even one nothing calls. " +
          "Implies class precision unless missinglinkPrecision is set."
      )

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

    /** How much the checker works out: `Method` says which conflicts the project can reach, `Class` reports every conflict as missinglink did. */
    sealed abstract class MissinglinkPrecision(val bridgeName: String)
    object MissinglinkPrecision {
      case object Class extends MissinglinkPrecision("Class")
      case object Method extends MissinglinkPrecision("Method")
    }

    val missinglinkPrecision: SettingKey[MissinglinkPrecision] =
      settingKey[MissinglinkPrecision](
        "Method (the default) works out which conflicts the project can reach; Class reports every conflict, as missinglink-core does " +
          "(the default when missinglinkScanDependencies is true)."
      )

    val missinglinkKeepRulesFile: SettingKey[Option[File]] =
      settingKey[Option[File]](
        "Optional file of rules saying what your frameworks call by reflection (one rule per line, e.g. 'keep-members com.acme.Wired'). " +
          "Added to the built-in rules for Guice, Spring, Jackson, Gson, servlet containers, Akka and Pekko."
      )

    val missinglinkCacheDirectory: SettingKey[Option[File]] =
      settingKey[Option[File]]("Where the checker keeps what it learns about jars between runs; none means nothing is written.")
  }

  import autoImport._

  override def requires: Plugins = JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  // Make it easy to throttle the concurrency of running missing-link on multiple projects, it consumes a lot of memory
  val missinglinkConflictsTag = Tags.Tag("missinglinkConflicts")

  // What sbt's `evicted` works out, without its printing: the report names the replaced versions itself.
  @transient
  private val missinglinkEvictions = taskKey[List[Eviction]]("What dependency resolution replaced.")

  val configSettings: Seq[Setting[?]] = Def.settings(
    missinglinkEvictions := {
      val module = dependencyResolution.value.moduleDescriptor(moduleSettings.value.asInstanceOf[ModuleDescriptorConfiguration])
      evictions(EvictionWarning(module, (evicted / evictionWarningOptions).value, update.value))
    },
    missinglinkCheck := Def
      .task {
        val log = streams.value.log
        val evictionData = missinglinkEvictions.value

        implicit val converter: FileConverter = fileConverter.value
        val cp = fullClasspath.value
        val classDir = (Compile / classDirectory).value
        val precision = missinglinkPrecision.value
        val scanDependencies = missinglinkScanDependencies.value
        validatePackageSettings(
          missinglinkIgnoreSourcePackages.value,
          missinglinkTargetSourcePackages.value,
          missinglinkIgnoreDestinationPackages.value,
          missinglinkTargetDestinationPackages.value
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
          RequestSettings(
            scanDependencies = scanDependencies,
            precision = precision,
            keepRulesFile = missinglinkKeepRulesFile.value,
            cacheDirectory = missinglinkCacheDirectory.value
          ),
          missinglinkIgnoreSourcePackages.value.map(p => (p.name, p.ignoreSubpackages)),
          missinglinkTargetSourcePackages.value.map(p => (p.name, p.targetSubpackages)),
          missinglinkIgnoreDestinationPackages.value.map(p => (p.name, p.ignoreSubpackages)),
          missinglinkTargetDestinationPackages.value.map(p => (p.name, p.targetSubpackages))
        )
        val result = EngineBridge.check(request)
        result.warnings.foreach(w => log.warn(w))
        logFilterEffects(result, log)
        reportConflicts(
          result,
          ReportSettings(precision, scanDependencies, verbose = missinglinkVerbose.value, failOnConflicts = missinglinkFailOnConflicts.value),
          modules,
          evictionData,
          log
        )
      }
      .tag(missinglinkConflictsTag)
      .value,
  )

  /** A package can be ignored or targeted on one side, not both (sbt-missinglink's rule). */
  private def validatePackageSettings(
      ignoreSource: Seq[IgnoredPackage],
      targetSource: Seq[TargetedPackage],
      ignoreDestination: Seq[IgnoredPackage],
      targetDestination: Seq[TargetedPackage]
  ): Unit = {
    assert(
      ignoreSource.isEmpty || targetSource.isEmpty,
      "ignoreSourcePackages and targetSourcePackages cannot be defined in the same project."
    )
    assert(
      ignoreDestination.isEmpty || targetDestination.isEmpty,
      "ignoreDestinationPackages and targetDestinationPackages cannot be defined in the same project."
    )
  }

  /** What the report depends on besides the conflicts. */
  private final case class ReportSettings(precision: MissinglinkPrecision, scanDependencies: Boolean, verbose: Boolean, failOnConflicts: Boolean)

  /**
   * Prints what the check found and fails the build if it must. At class precision the report is sbt-missinglink's own: every conflict, and only the
   * three kinds it knows. At method precision the call graph says which conflicts the project can reach, and the others are left out.
   */
  private def reportConflicts(result: EngineResult, settings: ReportSettings, modules: Map[String, ModuleID], evictionData: List[Eviction], log: Logger): Unit = {
    val layout = if (settings.precision == MissinglinkPrecision.Class) ConflictSummary.Layout.Classic else ConflictSummary.Layout.Labelled
    val considered = if (layout == ConflictSummary.Layout.Classic) result.conflicts.filter(_.reportedByMissinglink) else result.conflicts
    val shown = considered.filter(fails(_, settings.scanDependencies))
    if (shown.nonEmpty) reportFailing(shown, result, settings, layout, modules, evictionData, log)
    else log.info("No conflicts found")
  }

  private def reportFailing(
      shown: List[EngineConflict],
      result: EngineResult,
      settings: ReportSettings,
      layout: ConflictSummary.Layout,
      modules: Map[String, ModuleID],
      evictionData: List[Eviction],
      log: Logger
  ): Unit = {
    val filteredTotal = shown.length
    val removedByFilters = result.filtered.map(_.removed).sum

    val diffMessage =
      if (removedByFilters != 0)
        s" (${filteredTotal + removedByFilters} conflicts were found before applying filters)"
      else
        ""

    val conflictNoun = if (filteredTotal == 1) "conflict" else "conflicts"
    val total = s"$filteredTotal $conflictNoun found!$diffMessage"
    log.error(total)

    ConflictSummary.lines(shown, modules, settings.verbose, layout, evictionData).foreach(line => log.error(line))
    // The verbose breakdown can run long: the total and what kinds it is are said again where the output ends.
    if (settings.verbose) {
      log.error(total)
      ConflictSummary.kindLines(shown).foreach(line => log.error(line))
    }

    if (settings.failOnConflicts) throw new MessageOnlyException("Conflicts found")
  }

  /** Does `conflict` fail the build? The checker says which conflicts the project can reach (`failing`); scanning the dependencies counts every one. */
  private def fails(conflict: EngineConflict, scanDependencies: Boolean): Boolean = scanDependencies || conflict.failing

  override def globalSettings: Seq[Def.Setting[?]] = Seq(
    missinglinkFailOnConflicts := true,
    missinglinkScanDependencies := false,
    missinglinkIgnoreSourcePackages := Nil,
    missinglinkTargetSourcePackages := Nil,
    missinglinkIgnoreDestinationPackages := Nil,
    missinglinkTargetDestinationPackages := Nil,
    missinglinkExcludedDependencies := Nil,
    missinglinkVerbose := false,
    missinglinkKeepRulesFile := None,
  )

  override def projectSettings: Seq[Setting[?]] = {
    // Scanning the dependencies implies class precision, so a build that sets only that keeps sbt-missinglink's behaviour.
    Seq(
      missinglinkPrecision := (if (missinglinkScanDependencies.value) MissinglinkPrecision.Class else MissinglinkPrecision.Method),
      missinglinkCacheDirectory := Some(target.value / "reachable-linkage")
    ) ++
      inConfig(Compile)(configSettings) ++
      inConfig(Runtime)(configSettings) ++
      inConfig(Test)(configSettings)
  }

  /** What dependency resolution replaced, from sbt's own `evicted`: every version that lost to another, and the modules that asked for it. */
  private def evictions(warning: EvictionWarning): List[Eviction] =
    warning.allEvictions.toList.flatMap { pair =>
      pair.winner.flatMap { winner =>
        val selected = winner.module.revision
        val lost = pair.evicteds
          .map(e => EvictedVersion(e.module.revision, e.callers.map(c => s"${c.caller.organization}:${c.caller.name}").distinct.sorted))
          .filterNot(_.version == selected)
          .sortBy(_.version)
        if (lost.isEmpty) None else Some(Eviction(pair.organization, pair.name, selected, lost))
      }
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

  /** What the user chose, as the request says it. */
  private final case class RequestSettings(
      scanDependencies: Boolean,
      precision: MissinglinkPrecision,
      keepRulesFile: Option[File],
      cacheDirectory: Option[File]
  )

  private def stringList(values: Seq[String]): JList[String] = values.asJava

  private def pairs(values: Seq[(String, String)]): JList[JList[String]] =
    values.map { case (a, b) => Arrays.asList(a, b) }.asJava

  private def engineRequest(
      classDirectory: File,
      entries: List[SbtEntry],
      excluded: Set[String],
      settings: RequestSettings,
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
    request.put("scope", if (settings.scanDependencies) "Full" else "App")
    request.put("precision", settings.precision.bridgeName)
    request.put("excludedEntries", stringList(excluded.toList))
    request.put("ignoreSourcePackages", packages(ignoreSource))
    request.put("targetSourcePackages", packages(targetSource))
    request.put("ignoreDestinationPackages", packages(ignoreDestination))
    request.put("targetDestinationPackages", packages(targetDestination))
    settings.keepRulesFile.foreach(f => request.put("keepRulesFile", f.getAbsolutePath))
    settings.cacheDirectory.foreach(f => request.put("cacheDirectory", f.getAbsolutePath))
    request
  }

  /** What each package filter that was set removed, as the plugin has always said it. */
  private def logFilterEffects(result: EngineResult, log: Logger): Unit =
    result.filtered.filter(_.removed != 0).foreach { effect =>
      val (name, setting) = effect.kind match {
        case "IGNORE_SOURCE_PACKAGES"      => ("source", "missinglinkIgnoreSourcePackages")
        case "TARGET_SOURCE_PACKAGES"      => ("source", "missinglinkTargetSourcePackages")
        case "IGNORE_DESTINATION_PACKAGES" => ("destination", "missinglinkIgnoreDestinationPackages")
        case _                             => ("destination", "missinglinkTargetDestinationPackages")
      }
      log.info(
        s"""
          |${effect.removed} conflicts found in ignored ${name} packages.
          |Run plugin again without the '$setting' setting to see all conflicts that were found.
           """.stripMargin
      )
    }

  private[sbtmissinglink] sealed trait PackageFilter {
    def apply(name: String): Boolean
  }
  private[sbtmissinglink] trait PackageFilters[T <: PackageFilter] {
    def apply(name: String)(filters: Seq[T]): Boolean
  }
}
