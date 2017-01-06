resolvers ++= Seq(
  "Sonatype snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/",
  "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/"
)

addSbtPlugin("com.typesafe.sbt" % "sbt-scalariform" % "1.2.0")

addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-M12")

// or clone this repo and type `sbt publishLocal`
resolvers += Resolver.sonatypeRepo("snapshots")

addSbtPlugin("org.ensime" % "sbt-ensime" % "1.11.1")
