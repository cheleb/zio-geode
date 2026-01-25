import sbt.TestFramework

val scala3Version = "3.8.1"

inThisBuild(
  Seq(
    scalaVersion := scala3Version,
    version := "0.1.0-SNAPSHOT",
    organization := "dev.cheleb"
  )
)

lazy val root = project
  .in(file("."))
  .aggregate(core, forkedTests)

lazy val core = coreProject("zio-geode", "core")
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.24",
      "dev.zio" %% "zio-streams" % "2.1.24",
      "dev.zio" %% "zio-config" % "4.0.6",
      "dev.zio" %% "zio-config-magnolia" % "4.0.6",
      "dev.zio" %% "zio-config-typesafe" % "4.0.6",
      "org.apache.geode" % "geode-core" % "1.15.2",
      "org.apache.geode" % "geode-cq" % "1.15.2",
      "org.slf4j" % "log4j-over-slf4j" % "2.0.17" % Test,
      "org.apache.logging.log4j" % "log4j-core" % "2.25.3" % Test,
      "ch.qos.logback" % "logback-classic" % "1.5.26" % Test,
      "dev.zio" %% "zio-test" % "2.1.24" % Test,
      "dev.zio" %% "zio-test-sbt" % "2.1.24" % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Test / fork := true
  )

lazy val forkedTests = coreProject("zio-geode-forked-tests", "forked-tests")
  .dependsOn(core)
  .settings(
    libraryDependencies ++= Seq(
      "org.slf4j" % "log4j-over-slf4j" % "2.0.17" % Test,
      "org.apache.logging.log4j" % "log4j-core" % "2.25.3" % Test,
      "ch.qos.logback" % "logback-classic" % "1.5.26" % Test,
      "dev.zio" %% "zio-test" % "2.1.24" % Test,
      "dev.zio" %% "zio-test-sbt" % "2.1.24" % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Test / fork := true,
    Test / testForkedParallel := true,
    Test / parallelExecution := false
  )

def coreProject(projectId: String, folder: String) =
  Project(id = projectId, base = file("modules/" + folder))
