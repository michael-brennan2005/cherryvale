package pit

import chisel3._
import _root_.circt.stage.ChiselStage

// Pipeline state register
class PSR[T <: Data](gen: T) extends Module {
  val io = IO(new Bundle {
    val flush = Input(Bool())
    val stall = Input(Bool())

    val in = Input(gen)
    val out = Output(gen)
  })

  val reg = RegInit(0.U.asTypeOf(gen))

  when(io.flush) {
    reg := 0.U.asTypeOf(gen)
  }.elsewhen(!io.stall) {
    reg := io.in
  }

  io.out := reg
}
