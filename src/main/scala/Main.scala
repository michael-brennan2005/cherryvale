import chisel3._
import cpu.Utils
import debug.Fifo
import debug.UartRx
import elaborate.Elaboratable
import formal.Formal

/** `sbt "runMain EmitFifo"` / `sbt "runMain EmitFifo release"` */
object EmitFifo extends Elaboratable {
  def build = new Fifo(UInt(8.W), 8)
}

/** `sbt "runMain EmitTop"` / `sbt "runMain EmitTop release"` */
object EmitTop extends Elaboratable {
  private def mem = Utils.buildMemInit(
    program = """lui x1, 0
    lw x1, 0x400(x0)
    sw x1, 0x404(x0)
    jal x2, -8
    """,
    data = Seq(0x24 -> 0xffff)
  )

  def build = new Top(Some(mem), debug_port = false)
}

object EmitUartRx extends Elaboratable {
  def build = new UartRx(1000)
}
