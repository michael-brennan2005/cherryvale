import chisel3._
import _root_.circt.stage.ChiselStage
import cpu.Cpu
import cpu.Utils

object Main extends App {
  val mem = Utils.buildMemInit(
    program = """lw x1, 0x400(x0)
    sw x1, 0x404(x0)
    beq x0, x0, -8
    """,
    data = Seq()
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
