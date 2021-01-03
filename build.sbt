organization := "io.github.mpilquist"
name := "strava-summary"

scalaVersion := "2.13.4"

scalacOptions ++= List("-deprecation", "-Xlint")

libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-blaze-client" % "1.0-57-0368053",
  "org.http4s" %% "http4s-blaze-server" % "1.0-57-0368053",
  "org.http4s" %% "http4s-dsl" % "1.0-57-0368053",
  "org.http4s" %% "http4s-circe" % "1.0-57-0368053",
  "io.circe" %% "circe-parser" % "0.13.0"
)
