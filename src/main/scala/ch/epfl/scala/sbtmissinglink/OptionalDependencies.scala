package ch.epfl.scala.sbtmissinglink

import java.io.{File, StringReader}
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

import scala.util.control.NonFatal

import org.w3c.dom.{Element, Node}
import org.xml.sax.InputSource

/**
 * A dependency a library declares `provided` or `optional` in its POM: the library expects an application that needs it to add it itself, so a class of
 * it that is missing is not a break in the library.
 */
private[sbtmissinglink] final case class OptionalDependency(groupId: String, artifactId: String) {

  /** Whether `className` (dotted) is one this dependency would hold: under its group id, or in a package named for every word of its artifact id. */
  def holds(className: String): Boolean = {
    val packages = className.split('.').toList.dropRight(1)
    val words = artifactId.split('-').toList.filter(_.nonEmpty)
    val underGroup = !groupId.startsWith("$") && className.startsWith(groupId + ".")
    val named = words.nonEmpty && words.forall(packages.contains)
    underGroup || named
  }
}

private[sbtmissinglink] object OptionalDependencies {

  /** The `provided` and `optional` dependencies a POM declares; none if it cannot be read. */
  def parse(pom: String): List[OptionalDependency] =
    try {
      val factory = DocumentBuilderFactory.newInstance()
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      val root = factory.newDocumentBuilder().parse(new InputSource(new StringReader(pom))).getDocumentElement
      children(root, "dependencies").flatMap(children(_, "dependency")).flatMap { dependency =>
        val provided = text(dependency, "scope").contains("provided")
        val optional = text(dependency, "optional").contains("true")
        for {
          groupId <- text(dependency, "groupId")
          artifactId <- text(dependency, "artifactId")
          if provided || optional
        } yield OptionalDependency(groupId, artifactId)
      }
    } catch { case NonFatal(_) => Nil }

  private def children(parent: Element, name: String): List[Element] =
    Iterator.range(0, parent.getChildNodes.getLength).map(parent.getChildNodes.item).collect {
      case element: Element if element.getTagName == name => element
    }.toList

  private def text(parent: Element, name: String): Option[String] =
    children(parent, name).headOption.map(_.getTextContent.trim)

  /** The POM that sits beside a jar in the dependency cache. */
  private def pomBeside(jar: File): Option[File] = {
    val name = jar.getName
    if (!name.endsWith(".jar")) None
    else Some(new File(jar.getParentFile, name.stripSuffix(".jar") + ".pom")).filter(_.isFile)
  }

  private def declaredBy(jar: File): List[OptionalDependency] =
    pomBeside(jar).fold(List.empty[OptionalDependency]) { pom =>
      try parse(new String(Files.readAllBytes(pom.toPath), "UTF-8"))
      catch { case NonFatal(_) => Nil }
    }

  /**
   * The result without the missing classes that a library declares optional: a conflict where a dependency `usedBy` (by entry name, with its jar in
   * `jars`) refers to a class of one of its own `provided` or `optional` dependencies, which is not on the classpath. One that is on the classpath has
   * a class missing for a real reason (another version), so it stays. A class that refers to an optional dependency is part of that optional feature,
   * so what else it refers to that is missing (the optional dependency's own dependencies, which the POM does not list) is left out with it. Also how
   * many.
   */
  def without(result: EngineResult, jars: Map[String, File]): (EngineResult, Int) = {
    val onClasspath = jars.keys.map(_.split(':').toList).collect { case org :: name :: _ => (org, name) }.toSet
    def present(d: OptionalDependency): Boolean = onClasspath.exists { case (org, name) => org == d.groupId && (name == d.artifactId || name.startsWith(d.artifactId + "_")) }
    val declared = jars.map { case (name, jar) => name -> declaredBy(jar).filterNot(present) }
    def optional(c: EngineConflict): Boolean =
      c.isMissingClass && declared.getOrElse(c.usedBy, Nil).exists(_.holds(c.targetClass))
    val optionalClasses = result.conflicts.filter(optional).map(c => (c.usedBy, c.fromClass)).toSet
    def covered(c: EngineConflict): Boolean = c.isMissingClass && optionalClasses.contains((c.usedBy, c.fromClass))
    val (left, kept) = result.conflicts.partition(covered)
    (result.copy(conflicts = kept), left.size)
  }
}
