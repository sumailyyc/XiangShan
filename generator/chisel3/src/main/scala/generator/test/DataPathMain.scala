package generator.test

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy.LazyModule
import top.{ArgParser, BaseConfig}
import generator.Generator
import xiangshan.backend.datapath.DataPath
import xiangshan.{XSCoreParameters, XSCoreParamsKey}
import xiangshan.backend._


object DataPathMain extends App {
  val (config, firrtlOpts, firtoolOpts) = ArgParser.parse(args)

  val backendParams = config(XSCoreParamsKey).backendParams
  val dataPath = LazyModule(new DataPath(backendParams)(config))

  Generator.execute(
    firrtlOpts,
    dataPath.module,
    firtoolOpts
  )
}
