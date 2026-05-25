import chisel3._
import _root_.circt.stage.ChiselStage
import cpu.Cpu

object Main extends App {
  ChiselStage.emitSystemVerilogFile(
    new Top,
    args = Array(
      "--target-dir",
      "./build/sv/"
    ),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-default-layer-specialization=enable",
      "-preserve-values=named",
      "-preserve-aggregate=all"
    )
  )
}
