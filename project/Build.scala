import sbt._
import Keys._

import org.ensime.sbt.Plugin.Settings.ensimeConfig
import org.ensime.sbt.util.SExp._

object ApplicationBuild extends Build {
  override lazy val settings = super.settings ++
    Seq(
      name := "sentinel",
      version := "0.2.1",
      organization := "nl.gideondk",
      scalaVersion := "2.10.0",
      parallelExecution in Test := false,
      resolvers ++= Seq(Resolver.mavenLocal,
        "Sonatype OSS Releases" at "http://oss.sonatype.org/content/repositories/releases/",
        "Sonatype OSS Snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/",
        "Typesafe Snapshots" at "http://repo.typesafe.com/typesafe/snapshots/",
        "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/")
    )

  val appDependencies = Seq(
    "org.scalaz" %% "scalaz-core" % "7.0.0-M7" withSources(),
    "org.scalaz" %% "scalaz-effect" % "7.0.0-M7" withSources(),
    "org.specs2" %% "specs2" % "1.13",

    "com.chuusai" % "shapeless_2.10.0" % "1.2.4",
    "com.typesafe.akka" % "akka-actor_2.10" % "2.2-20130410-001403"
  )

  lazy val root = Project(id = "sentinel",
    base = file("."),
    settings = Project.defaultSettings ++ Seq(
      libraryDependencies ++= appDependencies,
      mainClass := Some("Main")
    ) ++ Format.settings
  )
}

object Format {

  import com.typesafe.sbt.SbtScalariform._

  lazy val settings = scalariformSettings ++ Seq(
    ScalariformKeys.preferences := formattingPreferences
  )

  lazy val formattingPreferences = {
    import scalariform.formatter.preferences._
    FormattingPreferences().
      setPreference(AlignParameters, true).
      setPreference(AlignSingleLineCaseStatements, true).
      setPreference(DoubleIndentClassDeclaration, true).
      setPreference(IndentLocalDefs, true).
      setPreference(IndentPackageBlocks, true).
      setPreference(IndentSpaces, 2).
      setPreference(MultilineScaladocCommentsStartOnFirstLine, true).
      setPreference(PreserveSpaceBeforeArguments, false).
      setPreference(PreserveDanglingCloseParenthesis, false).
      setPreference(RewriteArrowSymbols, true).
      setPreference(SpaceBeforeColon, false).
      setPreference(SpaceInsideBrackets, false).
      setPreference(SpacesWithinPatternBinders, true)
  }
}

