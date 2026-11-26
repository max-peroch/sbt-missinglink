package ch.epfl.scala.sbtmissinglink

/** A version of a module that dependency resolution replaced, and the modules that asked for it. */
private[sbtmissinglink] final case class EvictedVersion(version: String, requestedBy: Seq[String])

/** A module whose requested versions were replaced by `selected`: what sbt's `evicted` reports, kept as data. */
private[sbtmissinglink] final case class Eviction(organization: String, name: String, selected: String, evicted: Seq[EvictedVersion])
