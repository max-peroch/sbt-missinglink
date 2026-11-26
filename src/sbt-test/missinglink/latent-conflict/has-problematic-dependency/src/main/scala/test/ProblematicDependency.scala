package test

import com.google.common.base.Enums

/** `reliesOnRemovedMethod` calls a method in the Guava Enums class which was removed in guava 18; `fine` calls nothing broken. The project calls only `fine`. */
object ProblematicDependency {

  def fine(): AnyRef = "fine"

  def reliesOnRemovedMethod(): AnyRef = {
    Enums.valueOfFunction(classOf[Foo])
  }
}
