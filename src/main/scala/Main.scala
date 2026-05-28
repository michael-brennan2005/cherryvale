import chisel3._
import _root_.circt.stage.ChiselStage
import cpu.Cpu
import cpu.Utils

object Main extends App {
  val mem = Utils.buildMemInit(
    program = """lui x1, 0
    addi x1, x1, 1
    sw x1, 0x404(x0)
    jal x2, -8
    """,
    data = Seq(0x24 -> 0xffff)
  )
  ChiselStage.emitSystemVerilogFile(
    new Top(Some(mem), false),
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
