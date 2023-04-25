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

package xiangshan.mem

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import utils._
import utility._
import xiangshan.ExceptionNO._
import xiangshan._
import xiangshan.backend.fu.PMPRespBundle
import xiangshan.backend.rob.DebugLsInfoBundle
import xiangshan.cache.mmu.{TlbCmd, TlbReq, TlbRequestIO, TlbResp}
import xiangshan.cache.{DcacheStoreRequestIO, DCacheStoreIO, MemoryOpConstants, HasDCacheParameters, StorePrefetchReq}

// Store Pipeline Stage 0
// Generate addr, use addr to query DCache and DTLB
class StoreUnit_S0(implicit p: Parameters) extends XSModule with HasDCacheParameters{
  val io = IO(new Bundle() {
    val in = Flipped(Decoupled(new ExuInput))
    // prefetch req issued by store buffer
    val sb_prefetch = Flipped(DecoupledIO(new StorePrefetchReq))
    // prefetch req replayed by sta missQueue
    val sta_missQueue = Flipped(DecoupledIO(new StorePrefetchReq))
    val rsIdx = Input(UInt(log2Up(IssQueSize).W))
    val isFirstIssue = Input(Bool())
    val out = Decoupled(new LsPipelineBundle)
    val dtlbReq = DecoupledIO(new TlbReq)
    val dcache = DecoupledIO(new DcacheStoreRequestIO)
  })

  // send req to dtlb
  // val saddr = io.in.bits.src(0) + SignExt(io.in.bits.uop.ctrl.imm(11,0), VAddrBits)
  val imm12 = WireInit(io.in.bits.uop.ctrl.imm(11,0))
  val saddr_lo = io.in.bits.src(0)(11,0) + Cat(0.U(1.W), imm12)
  val saddr_hi = Mux(saddr_lo(12),
    Mux(imm12(11), io.in.bits.src(0)(VAddrBits-1, 12), io.in.bits.src(0)(VAddrBits-1, 12)+1.U),
    Mux(imm12(11), io.in.bits.src(0)(VAddrBits-1, 12)+SignExt(1.U, VAddrBits-12), io.in.bits.src(0)(VAddrBits-1, 12)),
  )
  val saddr = Cat(saddr_hi, saddr_lo(11,0))

  val use_flow_fromRS = io.in.valid
  val use_flow_fromPrefetch = !use_flow_fromRS && (io.sta_missQueue.valid || io.sb_prefetch.valid)
  val use_flow_fromStoreBuffer = !use_flow_fromRS && io.sb_prefetch.valid
  val use_flow_fromStaMissQueue = !use_flow_fromRS && io.sta_missQueue.valid && !io.sb_prefetch.valid

  val prefetch_vaddr = Mux(use_flow_fromStoreBuffer, io.sb_prefetch.bits.vaddr, io.sta_missQueue.bits.vaddr)
  val fake_prefetch_exinput = WireInit(0.U.asTypeOf(new ExuInput))
  val fake_prefetch_exinput_uop = fake_prefetch_exinput.uop

  // request to tlb
  io.dtlbReq.bits.vaddr := Mux(use_flow_fromRS, saddr, prefetch_vaddr)
  io.dtlbReq.valid := use_flow_fromRS || use_flow_fromPrefetch
  io.dtlbReq.bits.cmd := TlbCmd.write
  io.dtlbReq.bits.size := Mux(use_flow_fromRS, LSUOpType.size(io.in.bits.uop.ctrl.fuOpType), 3.U)
  io.dtlbReq.bits.kill := DontCare
  io.dtlbReq.bits.memidx.is_ld := false.B
  io.dtlbReq.bits.memidx.is_st := true.B
  io.dtlbReq.bits.memidx.idx := Mux(use_flow_fromRS, io.in.bits.uop.sqIdx.value, DontCare)
  io.dtlbReq.bits.debug.robIdx := Mux(use_flow_fromRS, io.in.bits.uop.robIdx, DontCare)
  io.dtlbReq.bits.no_translate := false.B
  io.dtlbReq.bits.debug.pc := Mux(use_flow_fromRS, io.in.bits.uop.cf.pc, DontCare)
  io.dtlbReq.bits.debug.isFirstIssue := Mux(use_flow_fromRS, io.isFirstIssue, false.B)

  // ready for prefetch sources
  io.sb_prefetch.ready   := io.out.ready && io.dcache.ready && !io.in.valid
  io.sta_missQueue.ready := io.out.ready && io.dcache.ready && !io.in.valid && !io.sb_prefetch.valid

  // not real dcache write
  // just triger a write intent to dcache by prefetch req
  io.dcache.valid           := use_flow_fromPrefetch
  io.dcache.bits.cmd        := MemoryOpConstants.M_PFW
  io.dcache.bits.vaddr      := prefetch_vaddr
  io.dcache.bits.mask       := DontCare
  io.dcache.bits.instrtype  := DCACHE_PREFETCH_SOURCE.U

  io.out.bits := DontCare
  io.out.bits.vaddr := Mux(use_flow_fromRS, saddr, prefetch_vaddr)

  // Now data use its own io
  // io.out.bits.data := genWdata(io.in.bits.src(1), io.in.bits.uop.ctrl.fuOpType(1,0))
  io.out.bits.data := io.in.bits.src(1) // FIXME: remove data from pipeline
  io.out.bits.uop := Mux(use_flow_fromRS, io.in.bits.uop, fake_prefetch_exinput_uop)
  io.out.bits.miss := DontCare
  io.out.bits.rsIdx := Mux(use_flow_fromRS, io.rsIdx, DontCare)
  io.out.bits.mask := Mux(use_flow_fromRS, genWmask(io.out.bits.vaddr, io.in.bits.uop.ctrl.fuOpType(1,0)), 3.U)
  io.out.bits.isFirstIssue := Mux(use_flow_fromRS, io.isFirstIssue, false.B)
  io.out.bits.wlineflag := Mux(use_flow_fromRS, io.in.bits.uop.ctrl.fuOpType === LSUOpType.cbo_zero, false.B)
  io.out.bits.isHWPrefetch := use_flow_fromPrefetch
  io.out.valid := Mux(use_flow_fromRS, true.B, use_flow_fromPrefetch && io.dcache.fire)
  io.in.ready := io.out.ready
  when(io.in.valid && io.isFirstIssue) {
    io.out.bits.uop.debugInfo.tlbFirstReqTime := GTimer()
  }

  // exception check
  val addrAligned = LookupTree(io.in.bits.uop.ctrl.fuOpType(1,0), List(
    "b00".U   -> true.B,              //b
    "b01".U   -> (io.out.bits.vaddr(0) === 0.U),   //h
    "b10".U   -> (io.out.bits.vaddr(1,0) === 0.U), //w
    "b11".U   -> (io.out.bits.vaddr(2,0) === 0.U)  //d
  ))

  io.out.bits.uop.cf.exceptionVec(storeAddrMisaligned) := Mux(use_flow_fromRS, !addrAligned, false.B)

  XSPerfAccumulate("in_valid", io.in.valid)
  XSPerfAccumulate("in_fire", io.in.fire)
  XSPerfAccumulate("in_fire_first_issue", io.in.fire && io.isFirstIssue)
  XSPerfAccumulate("addr_spec_success", io.out.fire() && saddr(VAddrBits-1, 12) === io.in.bits.src(0)(VAddrBits-1, 12))
  XSPerfAccumulate("addr_spec_failed", io.out.fire() && saddr(VAddrBits-1, 12) =/= io.in.bits.src(0)(VAddrBits-1, 12))
  XSPerfAccumulate("addr_spec_success_once", io.out.fire() && saddr(VAddrBits-1, 12) === io.in.bits.src(0)(VAddrBits-1, 12) && io.isFirstIssue)
  XSPerfAccumulate("addr_spec_failed_once", io.out.fire() && saddr(VAddrBits-1, 12) =/= io.in.bits.src(0)(VAddrBits-1, 12) && io.isFirstIssue)
}

// Store Pipeline Stage 1
// TLB resp (send paddr to dcache)
class StoreUnit_S1(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    val in = Flipped(Decoupled(new LsPipelineBundle))
    val out = Decoupled(new LsPipelineBundle)
    val lsq = ValidIO(new LsPipelineBundle())
    val dtlbResp = Flipped(DecoupledIO(new TlbResp()))
    val rsFeedback = ValidIO(new RSFeedback)
    val reExecuteQuery = Valid(new LoadReExecuteQueryIO)
    val s1_kill = Output(Bool())
  })

  // mmio cbo decoder
  val is_mmio_cbo = io.in.bits.uop.ctrl.fuOpType === LSUOpType.cbo_clean ||
    io.in.bits.uop.ctrl.fuOpType === LSUOpType.cbo_flush ||
    io.in.bits.uop.ctrl.fuOpType === LSUOpType.cbo_inval

  val s1_paddr = io.dtlbResp.bits.paddr(0)
  val s1_tlb_miss = io.dtlbResp.bits.miss

  val s1_mmio = is_mmio_cbo
  val s1_exception = ExceptionNO.selectByFu(io.out.bits.uop.cf.exceptionVec, staCfg).asUInt.orR

  io.in.ready := true.B

  io.dtlbResp.ready := true.B // TODO: why dtlbResp needs a ready?

  // st-ld violation dectect request.
  io.reExecuteQuery.valid := io.in.valid && !s1_tlb_miss && !io.in.bits.isHWPrefetch
  io.reExecuteQuery.bits.robIdx := io.in.bits.uop.robIdx
  io.reExecuteQuery.bits.paddr := s1_paddr
  io.reExecuteQuery.bits.mask := io.in.bits.mask

  // Send TLB feedback to store issue queue
  // Store feedback is generated in store_s1, sent to RS in store_s2
  io.rsFeedback.valid := io.in.valid && !io.in.bits.isHWPrefetch
  io.rsFeedback.bits.hit := !s1_tlb_miss
  io.rsFeedback.bits.flushState := io.dtlbResp.bits.ptwBack
  io.rsFeedback.bits.rsIdx := io.in.bits.rsIdx
  io.rsFeedback.bits.sourceType := RSFeedbackType.tlbMiss
  XSDebug(io.rsFeedback.valid,
    "S1 Store: tlbHit: %d robIdx: %d\n",
    io.rsFeedback.bits.hit,
    io.rsFeedback.bits.rsIdx
  )
  io.rsFeedback.bits.dataInvalidSqIdx := DontCare

  // get paddr from dtlb, check if rollback is needed
  // writeback store inst to lsq
  io.out.valid := io.in.valid && !s1_tlb_miss
  io.out.bits := io.in.bits
  io.out.bits.paddr := s1_paddr
  io.out.bits.miss := false.B
  io.out.bits.mmio := s1_mmio
  io.out.bits.atomic := s1_mmio
  io.out.bits.uop.cf.exceptionVec(storePageFault) := io.dtlbResp.bits.excp(0).pf.st
  io.out.bits.uop.cf.exceptionVec(storeAccessFault) := io.dtlbResp.bits.excp(0).af.st

  io.lsq.valid := io.in.valid && !io.in.bits.isHWPrefetch
  io.lsq.bits := io.out.bits
  io.lsq.bits.miss := s1_tlb_miss

  // kill dcache write intent request when tlb miss or exception
  io.s1_kill := (s1_tlb_miss || s1_exception || s1_mmio)

  // mmio inst with exception will be writebacked immediately
  // io.out.valid := io.in.valid && (!io.out.bits.mmio || s1_exception) && !s1_tlb_miss

  // write below io.out.bits assign sentence to prevent overwriting values
  val s1_tlb_memidx = io.dtlbResp.bits.memidx
  when(s1_tlb_memidx.is_st && io.dtlbResp.valid && !s1_tlb_miss && s1_tlb_memidx.idx === io.out.bits.uop.sqIdx.value) {
    // printf("Store idx = %d\n", s1_tlb_memidx.idx)
    io.out.bits.uop.debugInfo.tlbRespTime := GTimer()
  }

  XSPerfAccumulate("in_valid", io.in.valid)
  XSPerfAccumulate("in_fire", io.in.fire)
  XSPerfAccumulate("in_fire_first_issue", io.in.fire && io.in.bits.isFirstIssue)
  XSPerfAccumulate("tlb_miss", io.in.fire && s1_tlb_miss)
  XSPerfAccumulate("tlb_miss_first_issue", io.in.fire && s1_tlb_miss && io.in.bits.isFirstIssue)
  XSPerfAccumulate("sta_prefetch_num", io.in.fire &&  io.in.bits.isHWPrefetch)
  XSPerfAccumulate("normal_store_num", io.in.fire && !io.in.bits.isHWPrefetch)
}

class StoreUnit_S2(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    val in = Flipped(Decoupled(new LsPipelineBundle))
    val pmpResp = Flipped(new PMPRespBundle)
    val static_pm = Input(Valid(Bool()))
    val out = Decoupled(new LsPipelineBundle)
    val s2_kill = Output(Bool())
  })
  val pmp = WireInit(io.pmpResp)
  when (io.static_pm.valid) {
    pmp.ld := false.B
    pmp.st := false.B
    pmp.instr := false.B
    pmp.mmio := io.static_pm.bits
  }

  val s2_exception = ExceptionNO.selectByFu(io.out.bits.uop.cf.exceptionVec, staCfg).asUInt.orR
  val is_mmio = io.in.bits.mmio || pmp.mmio

  // kill dcache write intent request when mmio or exception
  io.s2_kill := (is_mmio || s2_exception)

  io.in.ready := true.B
  io.out.bits := io.in.bits
  io.out.bits.mmio := is_mmio && !s2_exception
  io.out.bits.atomic := io.in.bits.atomic || pmp.atomic
  io.out.bits.uop.cf.exceptionVec(storeAccessFault) := io.in.bits.uop.cf.exceptionVec(storeAccessFault) || pmp.st
  io.out.valid := io.in.valid && (!is_mmio || s2_exception) && !io.in.bits.isHWPrefetch
}

class StoreUnit_S3(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    val in = Flipped(Decoupled(new LsPipelineBundle))
    val stout = DecoupledIO(new ExuOutput) // writeback store
  })

  io.in.ready := true.B

  io.stout.valid := io.in.valid
  io.stout.bits.uop := io.in.bits.uop
  io.stout.bits.data := DontCare
  io.stout.bits.redirectValid := false.B
  io.stout.bits.redirect := DontCare
  io.stout.bits.debug.isMMIO := io.in.bits.mmio
  io.stout.bits.debug.paddr := io.in.bits.paddr
  io.stout.bits.debug.vaddr := io.in.bits.vaddr
  io.stout.bits.debug.isPerfCnt := false.B
  io.stout.bits.fflags := DontCare

}

class StoreUnit(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    val stin = Flipped(Decoupled(new ExuInput))
    val sb_prefetch = Flipped(DecoupledIO(new StorePrefetchReq))
    val sta_missQueue = Flipped(DecoupledIO(new StorePrefetchReq))
    val redirect = Flipped(ValidIO(new Redirect))
    val dcache = new DCacheStoreIO
    val feedbackSlow = ValidIO(new RSFeedback)
    val tlb = new TlbRequestIO()
    val pmp = Flipped(new PMPRespBundle())
    val rsIdx = Input(UInt(log2Up(IssQueSize).W))
    val isFirstIssue = Input(Bool())
    val lsq = ValidIO(new LsPipelineBundle)
    val lsq_replenish = Output(new LsPipelineBundle())
    val stout = DecoupledIO(new ExuOutput) // writeback store
    // store mask, send to sq in store_s0
    val storeMaskOut = Valid(new StoreMaskBundle)
    val reExecuteQuery = Valid(new LoadReExecuteQueryIO)
    val issue = Valid(new ExuInput)
    val debug_ls = Output(new DebugLsInfoBundle)
  })

  val store_s0 = Module(new StoreUnit_S0)
  val store_s1 = Module(new StoreUnit_S1)
  val store_s2 = Module(new StoreUnit_S2)
  val store_s3 = Module(new StoreUnit_S3)

  store_s0.io.in <> io.stin
  store_s0.io.dtlbReq <> io.tlb.req
  store_s0.io.dcache <> io.dcache.req
  store_s0.io.sta_missQueue <> io.sta_missQueue
  store_s0.io.sb_prefetch <> io.sb_prefetch
  io.tlb.req_kill := false.B
  store_s0.io.rsIdx := io.rsIdx
  store_s0.io.isFirstIssue := io.isFirstIssue

  io.dcache.s1_paddr := store_s1.io.out.bits.paddr
  // Note: now, store prefetch will be issued after store commit, so don't case about redirect
  io.dcache.s1_kill := store_s1.io.s1_kill
  io.dcache.s2_kill := store_s2.io.s2_kill
  io.dcache.s2_pc := store_s2.io.out.bits.uop.cf.pc

  // TODO: dcache resp
  io.dcache.resp.ready := true.B

  io.storeMaskOut.valid := store_s0.io.in.valid
  io.storeMaskOut.bits.mask := store_s0.io.out.bits.mask
  io.storeMaskOut.bits.sqIdx := store_s0.io.out.bits.uop.sqIdx

  PipelineConnect(store_s0.io.out, store_s1.io.in, true.B, store_s0.io.out.bits.uop.robIdx.needFlush(io.redirect))
  io.issue.valid := store_s1.io.in.valid && !store_s1.io.dtlbResp.bits.miss && !store_s1.io.in.bits.isHWPrefetch
  io.issue.bits := RegEnable(store_s0.io.in.bits, store_s0.io.in.valid)

  store_s1.io.dtlbResp <> io.tlb.resp
  io.lsq <> store_s1.io.lsq
  io.reExecuteQuery := store_s1.io.reExecuteQuery

  PipelineConnect(store_s1.io.out, store_s2.io.in, true.B, store_s1.io.out.bits.uop.robIdx.needFlush(io.redirect))

  // feedback tlb miss to RS in store_s2
  io.feedbackSlow.bits := RegNext(store_s1.io.rsFeedback.bits)
  io.feedbackSlow.valid := RegNext(store_s1.io.rsFeedback.valid && !store_s1.io.out.bits.uop.robIdx.needFlush(io.redirect))

  store_s2.io.pmpResp <> io.pmp
  store_s2.io.static_pm := RegNext(io.tlb.resp.bits.static_pm)
  io.lsq_replenish := store_s2.io.out.bits // mmio and exception
  PipelineConnect(store_s2.io.out, store_s3.io.in, true.B, store_s2.io.out.bits.uop.robIdx.needFlush(io.redirect))

  store_s3.io.stout <> io.stout

  io.debug_ls := DontCare
  io.debug_ls.s1.isTlbFirstMiss := io.tlb.resp.valid && io.tlb.resp.bits.miss && io.tlb.resp.bits.debug.isFirstIssue && !store_s1.io.in.bits.isHWPrefetch
  io.debug_ls.s1_robIdx := store_s1.io.in.bits.uop.robIdx.value

  private def printPipeLine(pipeline: LsPipelineBundle, cond: Bool, name: String): Unit = {
    XSDebug(cond,
      p"$name" + p" pc ${Hexadecimal(pipeline.uop.cf.pc)} " +
        p"addr ${Hexadecimal(pipeline.vaddr)} -> ${Hexadecimal(pipeline.paddr)} " +
        p"op ${Binary(pipeline.uop.ctrl.fuOpType)} " +
        p"data ${Hexadecimal(pipeline.data)} " +
        p"mask ${Hexadecimal(pipeline.mask)}\n"
    )
  }

  printPipeLine(store_s0.io.out.bits, store_s0.io.out.valid, "S0")
  printPipeLine(store_s1.io.out.bits, store_s1.io.out.valid, "S1")
}
