import mill._
import mill.scalalib._

import ammonite.ops._

object runner extends ScalaModule {
  def scalaVersion = "2.12.8"

  def mainClass = Some("inc.benchmark.Runner")

  def millSourcePath = super.millSourcePath / up

  def ivyDeps = T {
    super.ivyDeps() ++ Agg(
      ivy"com.github.scopt::scopt:3.7.1",
      ivy"com.github.tototoshi::scala-csv:1.3.6",
      ivy"com.lihaoyi::os-lib:0.2.8",
      ivy"com.lihaoyi::ujson:0.7.5",
      ivy"org.postgresql:postgresql:42.2.6"
    )
  }
}