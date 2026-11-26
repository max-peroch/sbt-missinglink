package test

/** Calls only the method of [[test.ProblematicDependency]] that is not broken, so with Guava >= 18 nothing this project can run fails. */
object UsesProblematicDependency {
  def callsTheMethodThatIsFine(): AnyRef = {
    ProblematicDependency.fine()
  }
}
