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

package top

import circt.stage._
import chisel3._
import chisel3.util._
import chisel3.stage.ChiselGeneratorAnnotation
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy.{LazyModule, DisableMonitors}
import difftest.DifftestModule
import xiangshan.DebugOptionsKey
import utility._

object TopMain extends App {
  val (config, firrtlOpts, firtoolOpts) = ArgParser.parse(args)

  // tools: init to close dpi-c when in fpga
  val envInFPGA = config(DebugOptionsKey).FPGAPlatform
  val enableConstantin = config(DebugOptionsKey).EnableConstantin
  Constantin.init(enableConstantin && !envInFPGA)
  ChiselDB.init(!envInFPGA)

  val soc = DisableMonitors(p => LazyModule(new top.XSTop()(p)))(config)
  Generator.execute(firrtlOpts, soc.module, firtoolOpts)
  FileRegisters.write(fileDir = "./build", filePrefix = "XSTop.")
}

object Generator {
  def execute(args: Array[String], mod: => RawModule, firtoolOpts: Array[String]) = {
    (new ChiselStage).execute(args, Seq(
      ChiselGeneratorAnnotation(mod _),
      CIRCTTargetAnnotation(CIRCTTarget.Verilog)
    ) ++ firtoolOpts.map(opt => FirtoolOption(opt)))
  }
}
