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
    crossScalaVersions += "3.8.4",
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
          "2.0.1"
      }
    },
    addSbtPlugin("com.github.sbt" % "sbt2-compat" % "0.1.0"),
    // No library dependency on the checker: the plugin fetches it at run time with Coursier, into a classloader of its own (see MissingLinkPlugin).
    libraryDependencies += "io.get-coursier" % "interface" % "1.0.28",
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++
        Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
    },
    scriptedBufferLog := false,
  )
