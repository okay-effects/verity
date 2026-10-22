/** verity: Scala 3.3 LTS and 2.13, as okay-faces and okay-simple; the JVM, and Scala.js for the page of `verity app` */
val scala3 = "3.3.8"
val scala2 = "2.13.18"

ThisBuild / organization := "io.github.okay-effects"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := scala3
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / resolvers += "okay-effects" at "https://okay-effects.github.io/okay-simple/maven"
/** okay-simple's release; okay-faces' snapshot, published locally until released */
val okayEffects = "0.1.1"
val okayFaces = "0.1.0-SNAPSHOT"
val okayHttp = "0.1.0-SNAPSHOT"
val okayStore = "0.1.0-SNAPSHOT"

def crossBuilt: Seq[Def.Setting[?]] = Seq(
  crossScalaVersions := Seq(scala3, scala2),
  scalacOptions ++= (if (scalaVersion.value.startsWith("3")) Seq("-deprecation", "-feature") else Seq("-deprecation", "-feature", "-Xlint")),
  libraryDependencies += "org.scalameta" %%% "munit" % "1.2.3" % Test)

/** THE DOMAIN AND THE SCENARIO: the kinds, what a record carries, the effects and their handlers, the dialogue — on
 * okay-store; the JVM and Scala.js (the page of `verity browser` runs the dialogue), agents and the disk on the JVM */
lazy val core = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("core")).settings(crossBuilt, name := "verity-core",
  libraryDependencies ++= Seq(
    "io.github.okay-effects" %%% "okay-effects" % okayEffects,
    "io.github.okay-effects" %%% "okay-effects-shape" % okayEffects,
    "io.github.okay-effects" %%% "okay-effects-io" % okayEffects,
    "io.github.okay-effects" %%% "okay-faces" % okayFaces,
    "io.github.okay-effects" %%% "okay-store" % okayStore,
    "io.github.okay-effects" %%% "okay-http" % okayHttp,
    "io.github.okay-effects" %%% "okay-http-rest" % okayHttp,
    "io.github.okay-effects" %%% "okay-faces-rest" % okayFaces))
  .jvmSettings(
    Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "main" / "scala-jvm",
    libraryDependencies ++= Seq(
      "io.github.okay-effects" %% "okay-mcp" % "0.1.0-SNAPSHOT",
      "io.github.okay-effects" %% "okay-faces-mcp" % okayFaces,
      "io.github.okay-effects" %% "okay-faces-html" % okayFaces,
      "io.github.okay-effects" %% "okay-faces-test" % okayFaces % Test,
      "io.github.okay-effects" %% "okay-effects-machine" % okayEffects % Test,
      "io.github.okay-effects" %% "okay-effects-free" % okayEffects % Test))
  .jsSettings(Test / unmanagedSourceDirectories := Nil)

/** THE PAGE: of `verity app`, okay-faces' remote face over the REST face, not a line of verity's own; of `verity browser`, the dialogue
 * itself in the browser, its store and file on the server over REST. One script, linked by `sbt web/fullLinkJS`, tested under jsdom */
lazy val web = (project in file("web")).enablePlugins(ScalaJSPlugin).dependsOn(core.js).settings(crossBuilt, name := "verity-web", publish / skip := true,
  scalaJSUseMainModuleInitializer := true,
  libraryDependencies ++= Seq(
    "io.github.okay-effects" %%% "okay-faces-laminar" % okayFaces,
    "io.github.okay-effects" %%% "okay-http" % okayHttp,
    "io.github.okay-effects" %%% "okay-effects-machine" % okayEffects,
    "com.raquo" %%% "laminar" % "17.2.1"),
  Test / jsEnv := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv(org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv.Config()
    .withEnv(Map("NODE_PATH" -> sys.env.getOrElse("NODE_PATH", "/opt/homebrew/lib/node_modules")))))

/** THE FACES: `verity [file]` at the terminal, `verity swing [file]` in a window, `verity mcp [file]` for an agent, `verity web [port] [file]` in a browser as pages, `verity app [port] [file]` as a page over REST, `verity browser [port] [file]` the dialogue in the browser, `verity api [port] [file]` as a JSON API */
lazy val app = (project in file("app")).dependsOn(core.jvm).settings(crossBuilt, name := "verity",
  // its own JVM, with the terminal's input: `sbt app/run` reads what is typed, or piped
  fork := true, run / connectInput := true,
  libraryDependencies ++= Seq(
    "io.github.okay-effects" %% "okay-store-jdbc" % okayStore,
    "io.github.okay-effects" %% "okay-faces-term" % okayFaces,
    "io.github.okay-effects" %% "okay-faces-swing" % okayFaces,
    "io.github.okay-effects" %% "okay-effects-machine" % okayEffects))

lazy val root = (project in file(".")).aggregate(core.jvm, core.js, app, web).settings(name := "verity-root", publish / skip := true)
