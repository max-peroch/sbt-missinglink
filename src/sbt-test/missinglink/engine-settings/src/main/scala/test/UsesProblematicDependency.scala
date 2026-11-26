package test

object UsesProblematicDependency {
  def callsTheMethodThatIsFine(): AnyRef = ProblematicDependency.fine()
}
