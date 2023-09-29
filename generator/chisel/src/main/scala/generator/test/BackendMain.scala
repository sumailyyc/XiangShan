package generator.test

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy.LazyModule
import top.{ArgParser, BaseConfig}
import generator.Generator
import xiangshan.backend.regfile.IntPregParams
import xiangshan.{XSCoreParameters, XSCoreParamsKey, XSTileKey}
import xiangshan.backend._

object BackendMain extends App {
  val (config, firrtlOpts, firtoolOpts) = ArgParser.parse(args)

  val defaultConfig = config.alterPartial({
    // Get XSCoreParams and pass it to the "small module"
    case XSCoreParamsKey => config(XSTileKey).head
  })

  val backendParams = defaultConfig(XSCoreParamsKey).backendParams
  val backend = LazyModule(new Backend(backendParams)(defaultConfig))

  Generator.execute(
    firrtlOpts :+ "--full-stacktrace" :+ "--target-dir" :+ "backend",
    backend.module,
    firtoolOpts
  )
  println("done")
}

