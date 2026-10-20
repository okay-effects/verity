/** verity: Scala 3.3 LTS and 2.13, as okay-faces and okay-simple; the JVM; Scala.js when the web face comes */
val scala3 = "3.3.8"
val scala2 = "2.13.18"

ThisBuild / organization := "io.github.okay-effects"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := scala3
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / resolvers += "okay-effects" at "https://okay-effects.github.io/okay-simple/maven"
/** okay-simple's and okay-faces' snapshots, published locally until released */
val okayEffects = "0.1.1-SNAPSHOT"
val okayFaces = "0.1.0-SNAPSHOT"

def crossBuilt: Seq[Def.Setting[?]] = Seq(
  crossScalaVersions := Seq(scala3, scala2),
  scalacOptions ++= (if (scalaVersion.value.startsWith("3")) Seq("-deprecation", "-feature") else Seq("-deprecation", "-feature", "-Xlint")),
  libraryDependencies += "org.scalameta" %% "munit" % "1.2.3" % Test)

/** THE DOMAIN AND THE SCENARIO: kinds, versioned records, the journal, the effects and their handlers, the dialogue */
lazy val core = (project in file("core")).settings(crossBuilt, name := "verity-core",
  libraryDependencies ++= Seq(
    "io.github.okay-effects" %% "okay-effects" % okayEffects,
    "io.github.okay-effects" %% "okay-effects-shape" % okayEffects,
    "io.github.okay-effects" %% "okay-effects-io" % okayEffects,
    "io.github.okay-effects" %% "okay-faces" % okayFaces,
    "io.github.okay-effects" %% "okay-mcp" % "0.1.0-SNAPSHOT",
    "io.github.okay-effects" %% "okay-faces-mcp" % okayFaces,
    "io.github.okay-effects" %% "okay-http" % "0.1.0-SNAPSHOT",
    "io.github.okay-effects" %% "okay-http-rest" % "0.1.0-SNAPSHOT",
    "io.github.okay-effects" %% "okay-faces-rest" % okayFaces,
    "io.github.okay-effects" %% "okay-faces-html" % okayFaces,
    "io.github.okay-effects" %% "okay-faces-test" % okayFaces % Test,
    "io.github.okay-effects" %% "okay-effects-machine" % okayEffects % Test,
    "io.github.okay-effects" %% "okay-effects-free" % okayEffects % Test))

/** THE FACES: `verity [file]` at the terminal, `verity swing [file]` in a window, `verity mcp [file]` for an agent, `verity web [port] [file]` in a browser, `verity api [port] [file]` as a JSON API */
lazy val app = (project in file("app")).dependsOn(core).settings(crossBuilt, name := "verity",
  // its own JVM, with the terminal's input: `sbt app/run` reads what is typed, or piped
  fork := true, run / connectInput := true,
  libraryDependencies ++= Seq(
    "io.github.okay-effects" %% "okay-faces-term" % okayFaces,
    "io.github.okay-effects" %% "okay-faces-swing" % okayFaces,
    "io.github.okay-effects" %% "okay-effects-machine" % okayEffects))

lazy val root = (project in file(".")).aggregate(core, app).settings(name := "verity-root", publish / skip := true)
