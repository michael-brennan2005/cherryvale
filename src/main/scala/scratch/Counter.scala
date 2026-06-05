package scratch

import chisel3._
import _root_.circt.stage.ChiselStage
import formal.Bmc
import formal.Formal

class Counter extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())

    val out = Output(UInt(16.W))
  })

  val count = RegInit(0.U(16.W))

  when(io.start && count === 0.U) {
    count := 23.U
  }.elsewhen(count =/= 0.U) {
    count := count - 1.U
  }

  // Prove that if start signal is never raised, the counter will remain zero.
  assert(count < 13.U)

  io.out := count
}

object CounterFormal extends Formal {
  def build = new Counter
  override def checks = Seq(Bmc(20))
}
