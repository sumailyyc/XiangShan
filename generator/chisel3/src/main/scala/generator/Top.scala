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

package generator

import chisel3._
import chisel3.util._
import chisel3.stage._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy.{LazyModule, DisableMonitors}
import top.{ArgParser, XSTop}
import difftest.DifftestModule
import xiangshan.DebugOptionsKey
import utility._

object TopMain extends App {
  val (config, firrtlOpts, firtoolOpts) = ArgParser.parse(args)
  require(firtoolOpts.isEmpty, "firtoolOpts in SFC should be empty")

  // tools: init to close dpi-c when in fpga
  val envInFPGA = config(DebugOptionsKey).FPGAPlatform
  val enableConstantin = config(DebugOptionsKey).EnableConstantin
  Constantin.init(enableConstantin && !envInFPGA)
  ChiselDB.init(!envInFPGA)

  val soc = DisableMonitors(p => LazyModule(new top.XSTop()(p)))(config)
  Generator.execute(firrtlOpts, soc.module, firtoolOpts)
  FileRegisters.write(fileDir = "./build", filePrefix = "XSTop.")
}

object SimTop extends App {
  // Keep this the same as TopMain except that SimTop is used here instead of XSTop
  val (config, firrtlOpts, firtoolOpts) = ArgParser.parse(args)
  require(firtoolOpts.isEmpty, "firtoolOpts in SFC should be empty")

  // tools: init to close dpi-c when in fpga
  val envInFPGA = config(DebugOptionsKey).FPGAPlatform
  val enableConstantin = config(DebugOptionsKey).EnableConstantin
  Constantin.init(enableConstantin && !envInFPGA)
  ChiselDB.init(!envInFPGA)

  Generator.execute(
    firrtlOpts,
    DisableMonitors(p => new top.SimTop()(p))(config),
    firtoolOpts
  )

  // tools: write cpp files
  ChiselDB.addToFileRegisters
  Constantin.addToFileRegisters
  FileRegisters.write(fileDir = "./build")
  DifftestModule.finish("XiangShan")
}

object Generator {
  def execute(args: Array[String], mod: => chisel3.RawModule, firtoolOpts: Array[String]) = {
    (new ChiselStage).execute(args, Seq(ChiselGeneratorAnnotation(mod _)))
  }
}
