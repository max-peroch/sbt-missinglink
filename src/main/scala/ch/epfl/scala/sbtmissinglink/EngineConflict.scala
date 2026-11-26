package ch.epfl.scala.sbtmissinglink

/** The kinds of conflict the plugin tells apart. Any other kind the engine reports is `Other`, shown by its name. */
private[sbtmissinglink] sealed abstract class Category(val name: String)

private[sbtmissinglink] object Category {
  case object ClassNotFound extends Category("CLASS_NOT_FOUND")
  case object MethodNotFound extends Category("METHOD_SIGNATURE_NOT_FOUND")
  case object FieldNotFound extends Category("FIELD_NOT_FOUND")
  final case class Other(raw: String) extends Category(raw)

  def parse(name: String): Category = name match {
    case ClassNotFound.name  => ClassNotFound
    case MethodNotFound.name => MethodNotFound
    case FieldNotFound.name  => FieldNotFound
    case other               => Other(other)
  }
}

/** One conflict as the checker reports it over the bridge. `label` is how likely it is to be hit (REACHABLE, LATENT, ...), shown as the engine names it. */
private[sbtmissinglink] final case class EngineConflict(
    category: Category,
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
) {
  def isMissingClass: Boolean = category == Category.ClassNotFound

  def isMissingMember: Boolean = category == Category.MethodNotFound || category == Category.FieldNotFound
}

/** What a package filter removed: its kind (an engine name such as IGNORE_SOURCE_PACKAGES) and how many conflicts. */
private[sbtmissinglink] final case class FilterEffect(kind: String, removed: Int)

/** What the checker said: warnings, what each package filter removed, and every conflict that is left. */
private[sbtmissinglink] final case class EngineResult(
    warnings: List[String],
    filtered: List[FilterEffect],
    conflicts: List[EngineConflict]
)
