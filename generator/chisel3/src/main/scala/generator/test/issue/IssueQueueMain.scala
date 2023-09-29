package generator.test.issue

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy.LazyModule
import top.{ArgParser, BaseConfig}
import generator.Generator
import xiangshan.{XSCoreParameters, XSCoreParamsKey}
import xiangshan.backend.issue._

object IssueQueueMain extends App {
  val (config, firrtlOpts, firtoolOpts) = ArgParser.parse(args)

  val backendParams = config(XSCoreParamsKey).backendParams

  val iqParams: IssueBlockParams = backendParams.intSchdParams.get.issueBlockParams.head
  val iq: IssueQueue = LazyModule(new IssueQueue(iqParams)(config))

  Generator.execute(
    firrtlOpts,
    iq.module,
    firtoolOpts
  )
}
