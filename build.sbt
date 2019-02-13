name := "Scala-tensorflow-sharetrade-helper"

version := "0.1"

scalaVersion := "2.12.8"

resolvers += "dnvriend" at "http://dl.bintray.com/dnvriend/maven"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.5.15",
  "com.typesafe.akka" %% "akka-testkit" % "2.5.15" % Test,
  "com.typesafe.akka" %% "akka-persistence" % "2.5.15",
  
  "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",
  "org.iq80.leveldb"            % "leveldb"          % "0.10",
  "com.github.dnvriend" %% "akka-persistence-inmemory" % "2.5.15.1" % Test,

  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.typesafe.akka" % "akka-slf4j_2.12" % "2.5.15",
  "org.platanios" %% "tensorflow" % "0.4.1" classifier "darwin-cpu-x86_64",
  "org.scalacheck"    %% "scalacheck"         % "1.13.5",
  "org.scalatest"     %% "scalatest"          % "3.0.4"
)

scalacOptions += "-feature"