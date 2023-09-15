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

import chisel3.RawModule
import chisel3.stage.ChiselGeneratorAnnotation

abstract class FirrtlCompiler
case object SFC extends FirrtlCompiler
case object MFC extends FirrtlCompiler

object Generator {

  def execute(args: Array[String], mod: => RawModule, fc: FirrtlCompiler, firtoolOpts: Array[String]) = {
    fc match {
      case MFC =>
        (new circt.stage.ChiselStage).execute(args, Seq(
          ChiselGeneratorAnnotation(mod _),
          circt.stage.CIRCTTargetAnnotation(circt.stage.CIRCTTarget.Verilog)
        ) ++ firtoolOpts.map(opt => circt.stage.FirtoolOption(opt)))
        
      case _ =>
        assert(false, s"Unknown firrtl compiler: ${fc.getClass.getName}!")
    }
  }

}
