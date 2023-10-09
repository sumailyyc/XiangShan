/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

import mill._
import scalalib._
import scalafmt._
import os.Path
import publish._
import $file.`rocket-chip`.common
import $file.`rocket-chip`.cde.common
import $file.`rocket-chip`.hardfloat.build

val defaultScalaVersion = "2.13.10"

def defaultVersions(chiselVersion: String) = Map(
  "chisel" -> (chiselVersion match {
    case "chisel"  => ivy"org.chipsalliance::chisel:6.0.0-M3"
    case "chisel3" => ivy"edu.berkeley.cs::chisel3:3.6.0"
    case _         => ivy""
  }),
  "chisel-plugin" -> (chiselVersion match {
    case "chisel"  => ivy"org.chipsalliance:::chisel-plugin:6.0.0-M3"
    case "chisel3" => ivy"edu.berkeley.cs:::chisel3-plugin:3.6.0"
    case _         => ivy""
  }),
  "chiseltest" -> (chiselVersion match {
    case "chisel"  => ivy"edu.berkeley.cs::chiseltest:5.0.1"
    case "chisel3" => ivy"edu.berkeley.cs::chiseltest:0.6.2"
    case _         => ivy""
  }),
  "scalatest" -> ivy"org.scalatest::scalatest:3.2.7",
)

trait CommonModule extends SbtModule with Cross.Module[String] {
  val resourcesPATH = os.pwd.toString() + "/src/main/resources"
  val envPATH = sys.env("PATH") + ":" + resourcesPATH
  override def forkEnv = Map("PATH" -> envPATH)

  override def ivyDeps = Agg(defaultVersions(crossValue)("chisel"))

  override def scalaVersion = defaultScalaVersion

  override def scalacPluginIvyDeps = Agg(defaultVersions(crossValue)("chisel-plugin"))

  override def scalacOptions = super.scalacOptions() ++ Agg("-language:reflectiveCalls", "-Ymacro-annotations", "-Ytasty-reader")

}

object rocketchip extends Cross[RocketChip]("chisel", "chisel3")

trait RocketChip
  extends millbuild.`rocket-chip`.common.RocketChipModule
    with SbtModule
    with Cross.Module[String] {
  def scalaVersion: T[String] = T(defaultScalaVersion)

  override def millSourcePath = os.pwd / "rocket-chip"

  def chiselModule = None

  def chiselPluginJar = None

  def chiselIvy = Some(defaultVersions(crossValue)("chisel"))

  def chiselPluginIvy = Some(defaultVersions(crossValue)("chisel-plugin"))

  def macrosModule = macros

  def hardfloatModule = hardfloat

  def cdeModule = cde

  def mainargsIvy = ivy"com.lihaoyi::mainargs:0.5.0"

  def json4sJacksonIvy = ivy"org.json4s::json4s-jackson:4.0.5"

  object macros extends Macros

  trait Macros
    extends millbuild.`rocket-chip`.common.MacrosModule
      with SbtModule {

    def scalaVersion: T[String] = T(defaultScalaVersion)

    def scalaReflectIvy = ivy"org.scala-lang:scala-reflect:${defaultScalaVersion}"
  }

  object hardfloat extends Hardfloat

  trait Hardfloat
    extends millbuild.`rocket-chip`.hardfloat.common.HardfloatModule {

    def scalaVersion: T[String] = T(defaultScalaVersion)

    override def millSourcePath = os.pwd / "rocket-chip" / "hardfloat" / "hardfloat"

    def chiselModule = None

    def chiselPluginJar = None

    def chiselIvy = Some(defaultVersions(crossValue)("chisel"))

    def chiselPluginIvy = Some(defaultVersions(crossValue)("chisel-plugin"))
  }

  object cde extends CDE

  trait CDE
    extends millbuild.`rocket-chip`.cde.common.CDEModule
      with ScalaModule {

    def scalaVersion: T[String] = T(defaultScalaVersion)

    override def millSourcePath = os.pwd / "rocket-chip" / "cde" / "cde"
  }
}

object huancun extends Cross[HuanCun]("chisel", "chisel3")
trait HuanCun extends CommonModule with ScalafmtModule {

  override def millSourcePath = os.pwd / "huancun"

  override def moduleDeps = super.moduleDeps ++ Seq(
    rocketchip(crossValue), utility(crossValue)
  )
}

object coupledL2 extends Cross[CoupledL2]("chisel", "chisel3")
trait CoupledL2 extends CommonModule with ScalafmtModule {

  override def millSourcePath = os.pwd / "coupledL2"

  override def moduleDeps = super.moduleDeps ++ Seq(
    rocketchip(crossValue),
    huancun(crossValue),
    utility(crossValue)
  )
}

object difftest extends Cross[Difftest]("chisel", "chisel3")
trait Difftest extends CommonModule with ScalafmtModule {

  override def millSourcePath = os.pwd / "difftest"
}

object yunsuan extends Cross[YunSuan]("chisel", "chisel3")
trait YunSuan extends CommonModule with ScalafmtModule {

  override def millSourcePath = os.pwd / "yunsuan"
}

object fudian extends Cross[FuDian]("chisel", "chisel3")
trait FuDian extends CommonModule with ScalafmtModule {

  override def millSourcePath = os.pwd / "fudian"
}

object utility extends Cross[Utility]("chisel", "chisel3")
trait Utility extends CommonModule with ScalafmtModule {

  override def millSourcePath = os.pwd / "utility"

  override def moduleDeps = super.moduleDeps ++ Seq(
    rocketchip(crossValue)
  )
}

object xiangshan extends Cross[XiangShan]("chisel", "chisel3")
trait XiangShan extends CommonModule with ScalafmtModule {

  override def millSourcePath = os.pwd

  override def forkArgs = Seq("-Xmx32G", "-Xss256m")

  override def ivyDeps = super.ivyDeps() ++ Agg(
    defaultVersions(crossValue)("chiseltest")
  )

  override def sources = T.sources {
    super.sources() ++ Seq(PathRef(millSourcePath / s"src-${crossValue}" / "main" / "scala"))
  }

  override def moduleDeps = super.moduleDeps ++ Seq(
    rocketchip(crossValue),
    utility(crossValue),
    huancun(crossValue),
    difftest(crossValue),
    coupledL2(crossValue),
    fudian(crossValue),
    yunsuan(crossValue)
  )

  object test extends SbtModuleTests with TestModule.ScalaTest {
    override def forkArgs = Seq("-Xmx32G", "-Xss256m")

    override def ivyDeps = super.ivyDeps() ++ Agg(
      defaultVersions(crossValue)("scalatest")
    )

    override def sources = T.sources {
      super.sources() ++ Seq(PathRef(millSourcePath / s"src-${crossValue}" / "test" / "scala"))
    }

    def testFramework = "org.scalatest.tools.Framework"
  }
}
