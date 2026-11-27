package ch.epfl.scala.sbtmissinglink

/** What a library's POM says is optional, and which missing classes that covers. */
class OptionalDependenciesSuite extends munit.FunSuite {

  private def pom(dependencies: String): String =
    s"""<project><dependencies>$dependencies</dependencies><dependencyManagement><dependencies>
       |<dependency><groupId>org.managed</groupId><artifactId>managed</artifactId><scope>provided</scope></dependency>
       |</dependencies></dependencyManagement></project>""".stripMargin

  private def dependency(group: String, artifact: String, extra: String = "") =
    s"<dependency><groupId>$group</groupId><artifactId>$artifact</artifactId>$extra</dependency>"

  test("provided and optional dependencies are read, the others and the managed ones are not") {
    val declared = OptionalDependencies.parse(
      pom(
        dependency("com.jolbox", "bonecp", "<scope>provided</scope>") +
          dependency("org.slf4j", "slf4j-api", "<optional>true</optional>") +
          dependency("org.scala-lang", "scala-library", "<scope>compile</scope>") +
          dependency("org.scalatest", "scalatest", "<scope>test</scope>")
      )
    )
    assertEquals(declared, List(OptionalDependency("com.jolbox", "bonecp"), OptionalDependency("org.slf4j", "slf4j-api")))
  }

  test("a POM that cannot be read declares nothing") {
    assertEquals(OptionalDependencies.parse("not xml"), Nil)
    assertEquals(OptionalDependencies.parse("<!DOCTYPE p [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><project>&e;</project>"), Nil)
  }

  test("a class is held by a dependency whose group id it is under") {
    assert(OptionalDependency("com.jolbox", "bonecp").holds("com.jolbox.bonecp.BoneCPDataSource"))
    assert(OptionalDependency("org.locationtech.jts", "jts-core").holds("org.locationtech.jts.geom.Geometry"))
    assert(!OptionalDependency("com.jolbox", "bonecp").holds("com.jolboxer.X"))
  }

  test("a class is held by a dependency whose artifact id names its package, though the group id does not") {
    assert(OptionalDependency("commons-dbcp", "commons-dbcp").holds("org.apache.commons.dbcp.PoolingDataSource"))
    assert(OptionalDependency("commons-pool", "commons-pool").holds("org.apache.commons.pool.impl.GenericObjectPool"))
    assert(OptionalDependency("log4j", "log4j").holds("org.apache.log4j.Logger"))
  }

  test("a class of another package is not held, nor a near name") {
    assert(!OptionalDependency("commons-lang", "commons-lang").holds("org.apache.commons.lang3.StringUtils"))
    assert(!OptionalDependency("commons-dbcp", "commons-dbcp").holds("org.apache.commons.dbcp2.BasicDataSource"))
  }

  test("a group id that is a build variable matches nothing by group") {
    assert(!OptionalDependency("${project.groupId}", "x").holds("a.b.C"))
  }

  private def conflict(usedBy: String, fromClass: String, target: String): EngineConflict =
    EngineConflict(Category.ClassNotFound, "REACHABLE", usedBy, "<unknown>", s"Class not found: $target", s"$fromClass#run -> $target", fromClass, "run", "()V", 1, failing = true, reportedByMissinglink = true, targetClass = target)

  test("a class that refers to an optional dependency has its other missing classes left out with it, and no other class does") {
    val dir = java.nio.file.Files.createTempDirectory("optional-dependencies")
    val jar = dir.resolve("lib-1.0.jar")
    java.nio.file.Files.write(jar, Array.emptyByteArray)
    java.nio.file.Files.write(dir.resolve("lib-1.0.pom"), pom(dependency("commons-dbcp", "commons-dbcp", "<scope>provided</scope>")).getBytes("UTF-8"))
    val conflicts = List(
      conflict("lib", "lib.Pool", "org.apache.commons.dbcp.PoolingDataSource"),
      conflict("lib", "lib.Pool", "org.apache.commons.pool.impl.GenericObjectPool"),
      conflict("lib", "lib.Other", "org.apache.commons.pool.impl.GenericObjectPool"),
      conflict("app", "app.Main", "org.apache.commons.dbcp.PoolingDataSource")
    )
    val (result, leftOut) = OptionalDependencies.without(EngineResult(Nil, Nil, conflicts), Map("lib" -> jar.toFile))
    assertEquals(leftOut, 2)
    assertEquals(result.conflicts.map(c => (c.usedBy, c.fromClass)), List(("lib", "lib.Other"), ("app", "app.Main")))
  }
}
