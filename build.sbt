inThisBuild(
  Def.settings(
    scalaVersion := "2.12.21",
    organization := "ch.epfl.scala",
    homepage := Some(url("https://github.com/scalacenter/sbt-missinglink")),
    licenses += (
      "BSD New",
      url("https://github.com/scalacenter/sbt-missinglink/blob/main/LICENSE")
    ),
    developers := List(
      Developer(
        "sjrd",
        "Sébastien Doeraene",
        "sjrdoeraene@gmail.com",
        url("https://github.com/sjrd/")
      )
    ),
  )
)

lazy val `sbt-missinglink` = project
  .in(file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    crossScalaVersions += "3.9.0",
    scalacOptions ++= {
      scalaBinaryVersion.value match {
        case "2.12" =>
          Seq("-release:8")
        case _ =>
          Nil
      }
    },
    pluginCrossBuild / sbtVersion := {
      scalaBinaryVersion.value match {
        case "2.12" =>
          (pluginCrossBuild / sbtVersion).value
        case _ =>
          "2.1.1"
      }
    },
    addSbtPlugin("com.github.sbt" % "sbt2-compat" % "0.1.0"),
    libraryDependencies += "io.github.max-peroch" %% "reachable-linkage-core" % "0.3.0",
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.4" % Test,
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++
        Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
    },
    scriptedBufferLog := false,
  )
