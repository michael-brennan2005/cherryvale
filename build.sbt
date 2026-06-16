// See README.md for license details.

ThisBuild / scalaVersion := "2.13.18"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "com.github.michaelbrennan2005"
Global / concurrentRestrictions := Seq(
  Tags.limitAll(4) // only 4 tests at a time so I dont crash my computer
)

val chiselVersion = "7.7.0"

lazy val root = (project in file("."))
  .settings(
    name := "chiseltest",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "org.scalatest" %% "scalatest" % "3.2.19" % "test",
      "com.carlosedp" %% "riscvassembler" % "1.10.0"
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations"
    ),
    addCompilerPlugin(
      "org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full
    )
  )
