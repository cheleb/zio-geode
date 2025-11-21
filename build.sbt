val scala3Version = "3.7.4"

lazy val root = project
  .in(file("."))
  .aggregate(core)

lazy val core = coreProject("zio-geode", "core")
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.22",
      "org.apache.geode" % "geode-core" % "1.15.2"
    )
  )
def coreProject(projectId: String, folder: String) =
  Project(id = projectId, base = file("modules/" + folder))
