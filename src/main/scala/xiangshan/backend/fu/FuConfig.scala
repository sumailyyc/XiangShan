package xiangshan.backend.fu

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import xiangshan.ExceptionNO._
import xiangshan.SelImm
import xiangshan.backend.Std
import xiangshan.backend.fu.fpu.{FDivSqrt, FMA, FPToFP, FPToInt, IntToFP}
import xiangshan.backend.fu.wrapper.{Alu, BranchUnit, DivUnit, JumpUnit, MulUnit, VSetRiWi, VSetRiWvf, VSetRvfWvf}
import xiangshan.backend.Bundles.ExuInput
import xiangshan.backend.datapath.DataConfig._

/**
  *
  * @param name [[String]] name of fuConfig
  * @param fuType [[Int]] type of func, select from [[xiangshan.backend.fu.FuType]]
  * @param fuGen how to create $fu
  * @param srcData type of src data used by this $fu
  * @param maybeBlock the $fu need ready signal to block internal pipeline
  * @param writeIntRf the $fu write int regfiles
  * @param writeFpRf the $fu write float regfiles
  * @param writeVecRf the $fu write vector regfiles
  * @param writeFflags the $fu write fflags csr
  * @param writeVxsat the $fu write vxsat csr
  * @param dataBits the width of data in the $fu
  * @param piped if the $fu is pipelined
  * @param latency the latency of instuction executed in the $fu
  * @param hasInputBuffer if the $fu has input buffer
  * @param exceptionOut the $fu can produce these exception
  * @param hasLoadError if the $fu has load error out
  * @param flushPipe if the instuction executed in the $fu need flush out
  * @param replayInst if the instuction executed in the $fu can replay in some condition
  * @param trigger if the $fu need trigger out
  * @param needSrcFrm if the $fu need float rounding mode signal
  * @param immType the immediate type of this $fu
  * @param vconfigWakeUp
  * @param maskWakeUp
  *
  * @define fu function unit
  */
case class FuConfig (
  name          : String,
  fuType        : Int,
  fuGen         : (Parameters, FuConfig) => FuncUnit,
  srcData       : Seq[Seq[DataConfig]],
  maybeBlock    : Boolean = false,
  writeIntRf    : Boolean = false,
  writeFpRf     : Boolean = false,
  writeVecRf    : Boolean = false,
  writeFflags   : Boolean = false,
  writeVxsat    : Boolean = false,
  dataBits      : Int = 64,
  piped         : Boolean = false,
  latency       : HasFuLatency = CertainLatency(0),
  hasInputBuffer: (Boolean, Int, Boolean) = (false, 0, false),
  exceptionOut  : Seq[Int] = Seq(),
  hasLoadError  : Boolean = false,
  flushPipe     : Boolean = false,
  replayInst    : Boolean = false,
  trigger       : Boolean = false,
  needSrcFrm    : Boolean = false,
  immType       : Set[UInt] = Set(),
  // vector
  vconfigWakeUp : Boolean = false,
  maskWakeUp    : Boolean = false,
) {
  var vconfigIdx = -1
  var maskSrcIdx = -1
  if (vconfigWakeUp) {
    vconfigIdx = getSpecialSrcIdx(VConfigData(), "when vconfigWakeUp is true, srcData must always contains VConfigData()")
  }
  if (maskWakeUp) {
    maskSrcIdx = getSpecialSrcIdx(MaskSrcData(), "when maskWakeUp is true, srcData must always contains MaskSrcData()")
  }

  require(!piped || piped && latency.latencyVal.isDefined, "The latency value must be set when piped is enable")
  require(!vconfigWakeUp || vconfigWakeUp && vconfigIdx >= 0, "The index of vl src must be set when vlWakeUp is enable")
  require(!maskWakeUp || maskWakeUp && maskSrcIdx >= 0, "The index of mask src must be set when vlWakeUp is enable")

  def numIntSrc : Int = srcData.map(_.count(x => IntRegSrcDataSet.contains(x))).max
  def numFpSrc  : Int = srcData.map(_.count(x => FpRegSrcDataSet.contains(x))).max
  def numVecSrc : Int = srcData.map(_.count(x => VecRegSrcDataSet.contains(x))).max
  def numVfSrc  : Int = srcData.map(_.count(x => VfRegSrcDataSet.contains(x))).max
  def numRegSrc : Int = srcData.map(_.count(x => RegSrcDataSet.contains(x))).max
  def numSrc    : Int = srcData.map(_.length).max

  def readFp: Boolean = numFpSrc > 0

  def fuSel(uop: ExuInput): Bool = {
    // Don't add more shit here!!!
    // Todo: add new FuType to distinguish f2i, f2f
    if (this.fuType == FuType.fmisc) {
      this.name match {
        case FuConfig.F2iCfg.name => uop.rfWen.get
        case FuConfig.F2fCfg.name => uop.fpu.get.fpWen && !uop.fpu.get.div && !uop.fpu.get.sqrt
      }
    } else {
      uop.fuType === this.fuType.U
    }
  }

  /**
    * params(i): data type set of the ith src port
    * @return
    */
  def getRfReadDataCfgSet: Seq[Set[DataConfig]] = {
    val numSrcMax = srcData.map(_.length).max
    // make srcData is uniform sized to avoid exception when transpose
    val alignedSrcData: Seq[Seq[DataConfig]] = srcData.map(x => x ++ Seq.fill(numSrcMax - x.length)(null))
    alignedSrcData.transpose.map(_.toSet.intersect(RegSrcDataSet))
  }

  def getSrcDataType(srcIdx: Int): Set[DataConfig] = {
    srcData
      .map((x: Seq[DataConfig]) => if(x.isDefinedAt(srcIdx)) Some(x(srcIdx)) else None)
      .filter(_.nonEmpty)
      .map(_.get)
      .toSet
  }

  def hasNoDataWB: Boolean = {
    !(writeIntRf || writeFpRf || writeVecRf)
  }

  def getSrcMaxWidthVec = {
    getRfReadDataCfgSet.map(_.map(_.dataWidth).max)
  }

  def genSrcDataVec: Seq[UInt] = {
    getSrcMaxWidthVec.map(w => UInt(w.W))
  }

  // csr's redirect is in its exception bundle
  def hasRedirect: Boolean = Seq(FuType.jmp, FuType.brh).contains(fuType)

  def hasPredecode: Boolean = Seq(FuType.jmp, FuType.brh, FuType.csr).contains(fuType)

  def needPc: Boolean = Seq(FuType.jmp, FuType.brh, FuType.csr, FuType.fence).contains(fuType)

  def needFPUCtrl: Boolean = {
    import FuType._
    Set(fmac, fDivSqrt, fmisc, i2f).contains(fuType)
  }

  def needVecCtrl: Boolean = {
    import FuType._
    Set(vipu, vialuF, vfpu, vppu).contains(fuType)
  }

  def isMul: Boolean = fuType == FuType.mul

  def isDiv: Boolean = fuType == FuType.div

  def isCsr: Boolean = fuType == FuType.csr

  def isFence: Boolean = fuType == FuType.fence

  /**
    * Get index of special src data, like [[VConfigData]], [[MaskSrcData]]
    * @param data [[DataConfig]]
    * @param tips tips if get failed
    * @return the index of special src data
    */
  protected def getSpecialSrcIdx(data: DataConfig, tips: String): Int = {
    val srcIdxVec = srcData.map(x => x.indexOf(data))
    val idx0 = srcIdxVec.head
    for (idx <- srcIdxVec) {
      require(idx >= 0 && idx == idx0, tips + ", and at the same index.")
    }
    idx0
  }
}

object FuConfig {
  val JmpCfg: FuConfig = FuConfig (
    name = "jmp",
    fuType = FuType.jmp,
    fuGen = (p: Parameters, cfg: FuConfig) => Module(new JumpUnit(cfg)(p)).suggestName("jmp"),
    srcData = Seq(
      Seq(IntData()), // jal
    ),
    writeIntRf = true,
    writeFpRf = false,
    immType = Set(SelImm.IMM_I, SelImm.IMM_UJ, SelImm.IMM_U),
  )

  val BrhCfg: FuConfig = FuConfig (
    name = "brh",
    fuType = FuType.brh,
    fuGen = (p: Parameters, cfg: FuConfig) => Module(new BranchUnit(cfg)(p).suggestName("brh")),
    srcData = Seq(
      Seq(IntData(), IntData()),
    ),
    writeIntRf = false,
    writeFpRf = false,
    immType = Set(SelImm.IMM_SB),
  )

  val I2fCfg: FuConfig = FuConfig (
    name = "i2f",
    FuType.i2f,
    fuGen = (p: Parameters, cfg: FuConfig) => Module(new IntToFP(cfg)(p).suggestName("i2f")),
    srcData = Seq(
      Seq(IntData()),
    ),
    writeIntRf = false,
    writeFpRf = true,
    writeFflags = true,
    latency = CertainLatency(2),
    needSrcFrm = true,
  )

  val CsrCfg: FuConfig = FuConfig (
    name = "csr",
    fuType = FuType.csr,
    fuGen = (p: Parameters, cfg: FuConfig) => Module(new CSR(cfg)(p).suggestName("csr")),
    srcData = Seq(
      Seq(IntData()),
    ),
    writeIntRf = true,
    writeFpRf = false,
    exceptionOut = Seq(illegalInstr, breakPoint, ecallU, ecallS, ecallM),
    flushPipe = true
  )

  val AluCfg: FuConfig = FuConfig (
    name = "alu",
    fuType = FuType.alu,
    fuGen = (p: Parameters, cfg: FuConfig) => Module(new Alu(cfg)(p).suggestName("Alu")),
    srcData = Seq(
      Seq(IntData(), IntData()),
    ),
    writeIntRf = true,
    writeFpRf = false,
    immType = Set(SelImm.IMM_I, SelImm.IMM_U),
  )

  val MulCfg: FuConfig = FuConfig (
    name = "mul",
    fuType = FuType.mul,
    fuGen = (p: Parameters, cfg: FuConfig) => Module(new MulUnit(cfg)(p).suggestName("Mul")),
    srcData = Seq(
      Seq(IntData(), IntData()),
    ),
    writeIntRf = true,
    writeFpRf = false,
    latency = CertainLatency(2),
  )

  val DivCfg: FuConfig = FuConfig (
    name = "div",
    fuType = FuType.div,
    fuGen = (p: Parameters, cfg: FuConfig) => Module(new DivUnit(cfg)(p).suggestName("Div")),
    srcData = Seq(
      Seq(IntData(), IntData()),
    ),
    writeIntRf = true,
    writeFpRf = false,
    latency = UncertainLatency(),
    hasInputBuffer = (true, 4, true)
  )

  val FenceCfg: FuConfig = FuConfig (
    name = "fence",
    FuType.fence,
    fuGen = (p: Parameters, cfg: FuConfig) => Module(new Fence(cfg)(p).suggestName("Fence")),
    srcData = Seq(
      Seq(IntData(), IntData()),
    ),
    writeIntRf = false,
    writeFpRf = false,
    latency = UncertainLatency(),
    exceptionOut = Seq(illegalInstr),
    flushPipe = true
  )

  // Todo: split it to simple bitmap exu and complex bku
  val BkuCfg: FuConfig = FuConfig (
    name = "bku",
    fuType = FuType.bku,
    fuGen = (p: Parameters, cfg: FuConfig) => Module(new Bku(cfg)(p).suggestName("Bku")),
    srcData = Seq(
      Seq(IntData(), IntData()),
    ),
    writeIntRf = true,
    writeFpRf = false,
    latency = CertainLatency(1),
  )

  val VSetRvfWvfCfg: FuConfig = FuConfig(
    name = "vsetrvfwvf",
    fuType = FuType.vsetiwf,
    fuGen = (p: Parameters, cfg: FuConfig) => Module(new VSetRvfWvf(cfg)(p).suggestName("VSetRvfWvf")),
    srcData = Seq(
      Seq(FpData(), FpData()),
    ),
    writeIntRf = false,
    writeFpRf = false,
    writeVecRf = true,
    latency = CertainLatency(0),
    immType = Set(SelImm.IMM_VSETVLI, SelImm.IMM_VSETIVLI),
  )

  val VSetRiWvfCfg: FuConfig = FuConfig(
    name = "vsetriwvf",
    fuType = FuType.vsetiwf,
    fuGen = (p: Parameters, cfg: FuConfig) => Module(new VSetRiWvf(cfg)(p).suggestName("VSetRiWvf")),
    srcData = Seq(
      Seq(IntData(), IntData()),
    ),
    writeIntRf = false,
    writeFpRf = false,
    writeVecRf = true,
    latency = CertainLatency(0),
    immType = Set(SelImm.IMM_VSETVLI, SelImm.IMM_VSETIVLI),
  )

  val VSetRiWiCfg: FuConfig = FuConfig(
    name = "vsetriwi",
    fuType = FuType.vsetiwi,
    fuGen = (p: Parameters, cfg: FuConfig) => Module(new VSetRiWi(cfg)(p).suggestName("VSetRiWi")),
    srcData = Seq(
      Seq(IntData(), IntData()),
    ),
    writeIntRf = true,
    writeFpRf = false,
    latency = CertainLatency(0),
    immType = Set(SelImm.IMM_VSETVLI, SelImm.IMM_VSETIVLI),
  )

  val FmacCfg: FuConfig = FuConfig (
    name = "fmac",
    fuType = FuType.fmac,
    fuGen = (p: Parameters, cfg: FuConfig) => Module(new FMA(cfg)(p).suggestName("FMac")),
    srcData = Seq(
      Seq(FpData(), FpData()),
      Seq(FpData(), FpData(), FpData()),
    ),
    writeIntRf = false,
    writeFpRf = true,
    writeFflags = true,
    latency = UncertainLatency(),
    needSrcFrm = true,
  )

  val F2iCfg: FuConfig = FuConfig (
    name = "f2i",
    fuType = FuType.fmisc,
    fuGen = (p: Parameters, cfg: FuConfig) => Module(new FPToInt(cfg)(p).suggestName("F2i")),
    srcData = Seq(
      Seq(FpData(), FpData()),
      Seq(FpData()),
    ),
    writeIntRf = true,
    writeFpRf = false,
    writeFflags = true,
    latency = CertainLatency(2),
    needSrcFrm = true,
  )

  val F2fCfg: FuConfig = FuConfig (
    name = "f2f",
    fuType = FuType.fmisc,
    fuGen = (p: Parameters, cfg: FuConfig) => Module(new FPToFP(cfg)(p).suggestName("F2f")),
    srcData = Seq(
      Seq(FpData(), FpData()),
      Seq(FpData()),
    ),
    writeIntRf = false,
    writeFpRf = true,
    writeFflags = true,
    latency = CertainLatency(2),
    needSrcFrm = true,
  )

  val FDivSqrtCfg: FuConfig = FuConfig (
    name = "fDivSqrt",
    fuType = FuType.fDivSqrt,
    fuGen = (p: Parameters, cfg: FuConfig) => Module(new FDivSqrt(cfg)(p).suggestName("FDivSqrt")),
    srcData = Seq(
      Seq(FpData(), FpData()),
    ),
    writeIntRf = false,
    writeFpRf = true,
    writeFflags = true,
    latency = UncertainLatency(),
    hasInputBuffer = (true, 8, true),
    needSrcFrm = true,
  )

  val LduCfg: FuConfig = FuConfig (
    name = "ldu",
    fuType = FuType.ldu,
    fuGen = null, // Todo
    srcData = Seq(
      Seq(IntData()),
    ),
    writeIntRf = true,
    writeFpRf = true,
    latency = UncertainLatency(),
    exceptionOut = Seq(loadAddrMisaligned, loadAccessFault, loadPageFault),
    flushPipe = true,
    replayInst = true,
    hasLoadError = true,
    immType = Set(SelImm.IMM_I),
  )

  val StaCfg: FuConfig = FuConfig (
    name = "sta",
    fuType = FuType.stu,
    fuGen = null, // Todo
    srcData = Seq(
      Seq(IntData()),
    ),
    writeIntRf = false,
    writeFpRf = false,
    latency = UncertainLatency(),
    exceptionOut = Seq(storeAddrMisaligned, storeAccessFault, storePageFault),
    immType = Set(SelImm.IMM_S),
  )

  val StdCfg: FuConfig = FuConfig (
    name = "std",
    fuType = FuType.stu,
    fuGen = (p: Parameters, cfg: FuConfig) => Module(new Std(cfg)(p).suggestName("Std")),
    srcData = Seq(
      Seq(IntData()),
      Seq(FpData()),
    ),
    writeIntRf = false,
    writeFpRf = false,
    latency = CertainLatency(1),
    exceptionOut = Seq(storeAddrMisaligned, storeAccessFault, storePageFault)
  )

  val MouCfg: FuConfig = FuConfig (
    name = "mou",
    fuType = FuType.mou,
    fuGen = null, // Todo
    srcData = Seq(
      Seq(IntData()),
    ),
    writeIntRf = true,
    writeFpRf = false,
    latency = UncertainLatency(),
    exceptionOut = (LduCfg.exceptionOut ++ StaCfg.exceptionOut ++ StdCfg.exceptionOut).distinct
  )

  val MoudCfg: FuConfig = FuConfig (
    name = "moud",
    fuType = FuType.mou,
    fuGen = null, // Todo
    srcData = Seq(
      Seq(IntData()),
    ),
    writeIntRf = false,
    writeFpRf = false,
    latency = UncertainLatency()
  )

  val VialuCfg = FuConfig (
    name = "vialu",
    fuType = FuType.vialuF,
    fuGen = null,
    srcData = Seq(
      Seq(VecData(), VecData(), VecData(), MaskSrcData(), VConfigData()),  // vs1, vs2, vd_old, v0, vtype&vl
    ),
    writeVecRf = true,
    latency = CertainLatency(1),
  )

  val VipuCfg: FuConfig = FuConfig (
    name = "vipu",
    fuType = FuType.vipu,
    fuGen = null, // Todo
    srcData = Seq(
      Seq(VecData(), VecData(), VecData(), MaskSrcData(), VConfigData()),  // vs1, vs2, vd_old, v0
    ),
    writeIntRf = false,
    writeFpRf = false,
    writeVecRf = true,
    latency = UncertainLatency(),
  )

  val VfpuCfg: FuConfig = FuConfig (
    name = "vfpu",
    fuType = FuType.vfpu,
    fuGen = null, // Todo
    srcData = Seq(
      Seq(VecData(), VecData(), VecData(), MaskSrcData(), VConfigData()),  // vs1, vs2, vd_old, v0
      Seq(FpData(), VecData(), VecData(), MaskSrcData(), VConfigData()),   // f[rs1], vs2, vd_old, v0
    ),
    writeIntRf = false,
    writeFpRf = false,
    writeVecRf = true,
    latency = UncertainLatency(),
  )
  // Todo
  // def VlduCfg = FuConfig ()
  // def VstuCfg = FuConfig ()

}
