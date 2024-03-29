import sbt._
import Keys._

object ScalaHostBuild extends Build {
  import Dependencies._
  import Settings._

  lazy val commonDependencies = Seq(
    libraryDependencies <++= (scalaVersion)(sv => Seq(
      reflect(sv) % "provided",
      compiler(sv) % "provided",
      meta
    )),
    addCompilerPlugin(paradise)
  )

  lazy val root = Project(
    id = "root",
    base = file("root"),
    settings = sharedSettings ++ commonDependencies ++ Seq(
      test in Test := (test in tests in Test).value,
      dontPackage
    )
  ) aggregate (plugin, tests)

  lazy val foundation = Project(
    id   = "scalahost-foundation",
    base = file("foundation"),
    settings = publishableSettings ++ commonDependencies ++ Seq(
      flatSource,
      libraryDependencies += metafoundation,
      dontPackage
    )
  )

  lazy val plugin = Project(
    id   = "scalahost",
    base = file("plugin"),
    settings = publishableSettings ++ commonDependencies ++ packaging ++ Seq(
      libraryDependencies += interpreter
    )
  ) dependsOn (foundation % "optional") // not really optional, used for fatjar

  lazy val sandbox = Project(
    id   = "sandbox",
    base = file("sandbox"),
    settings = sharedSettings ++ commonDependencies ++ Seq(
      usePlugin(plugin)
    )
  )

  lazy val tests = Project(
    id   = "tests",
    base = file("tests"),
    settings = sharedSettings ++ commonDependencies ++ Seq(
      usePlugin(plugin),
      libraryDependencies ++= Seq(scalatest, scalacheck),
      dontPackage
    )
  ) dependsOn (plugin)
}