import sbt.Keys._

val scalaV = "2.11.8"

scalacOptions ++= Seq("-language:implicitConversions", "-unchecked")

scalaVersion := scalaV

addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)

libraryDependencies ++= Seq(
  "org.scala-lang" % "scala-compiler" % scalaV,
  "org.typelevel" %% "cats" % "0.7.0",
  "io.monix" %% "monix" % "2.0.0",
  "io.monix" %% "monix-cats" % "2.0.0"
)

scalacOptions in (Compile, console) ++= Seq(
  "-i", "myrepl.init"
)

tutSettings

tutTargetDirectory := file(".")
