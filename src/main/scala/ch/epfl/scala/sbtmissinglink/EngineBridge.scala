package ch.epfl.scala.sbtmissinglink

import java.util.{List => JList, Map => JMap}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import sbt.MessageOnlyException

/**
 * Runs the checker (reachable-linkage-core), a dependency of the plugin: `_2.12` on sbt 1 and `_3` on sbt 2. The two sides meet at one static method, `LinkageBridge.check`,
 * which takes and returns `java.util` maps, lists and strings (see the core's `LinkageBridge` for the keys).
 */
private[sbtmissinglink] object EngineBridge {

  /** Runs the engine on `request` and reads what it says. */
  def check(request: JMap[String, AnyRef]): EngineResult =
    try decode(reachablelinkage.api.LinkageBridge.check(request))
    catch {
      case NonFatal(e) if !e.isInstanceOf[MessageOnlyException] =>
        throw new MessageOnlyException(s"The reachable-linkage engine failed: $e")
    }

  private def decode(response: JMap[String, AnyRef]): EngineResult = {
    Option(response.get("error")).foreach(message => throw new MessageOnlyException(s"reachable-linkage: $message"))

    EngineResult(
      warnings = list(response, "warnings").map(_.toString),
      filtered = list(response, "filtered").map {
        case pair: JList[_] if pair.size == 2 => FilterEffect(pair.get(0).toString, pair.get(1).toString.toInt)
        case other                            => malformed("filtered", other)
      },
      conflicts = list(response, "conflicts").map {
        case m: JMap[_, _] => conflict(m.asInstanceOf[JMap[String, AnyRef]])
        case other         => malformed("conflicts", other)
      }
    )
  }

  private def conflict(m: JMap[String, AnyRef]): EngineConflict =
    EngineConflict(
      category = Category.parse(text(m, "category")),
      label = text(m, "label"),
      usedBy = text(m, "usedBy"),
      existsIn = text(m, "existsIn"),
      reason = text(m, "reason"),
      describe = text(m, "describe"),
      fromClass = text(m, "fromClass"),
      fromMethodName = text(m, "fromMethodName"),
      fromMethodDescriptor = text(m, "fromMethodDescriptor"),
      fromLineNumber = number(m, "fromLineNumber"),
      failing = flag(m, "failing"),
      reportedByMissinglink = flag(m, "reportedByMissinglink"),
      targetClass = text(m, "targetClass"),
      route = list(m, "route").map(_.toString)
    )

  // The engine's response is a contract between two versions of our own code; a field that is missing or of the wrong type is said, not hidden.
  private def malformed(field: String, found: Any): Nothing =
    throw new MessageOnlyException(s"The reachable-linkage engine returned an unexpected '$field': $found (is the plugin's engine version the one it expects?)")

  private def list(response: JMap[String, AnyRef], key: String): List[AnyRef] = response.get(key) match {
    case l: JList[_] => l.asScala.toList.asInstanceOf[List[AnyRef]]
    case other       => malformed(key, other)
  }

  private def text(m: JMap[String, AnyRef], key: String): String = m.get(key) match {
    case s: String => s
    case other     => malformed(key, other)
  }

  private def number(m: JMap[String, AnyRef], key: String): Int = m.get(key) match {
    case n: java.lang.Number => n.intValue
    case other               => malformed(key, other)
  }

  private def flag(m: JMap[String, AnyRef], key: String): Boolean = m.get(key) match {
    case b: java.lang.Boolean => b.booleanValue
    case other                => malformed(key, other)
  }
}
