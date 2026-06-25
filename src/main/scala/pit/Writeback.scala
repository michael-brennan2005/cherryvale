package pit

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// Writeback stage - for now, a simple almost-entirely-combinational stage that selects
// writes results to registers. Also contains cycle and instruction counting logic.
class Writeback extends Module {
  val io = IO(new Bundle {
    // Global CPU signals
    val halt = Input(Bool())
    val clear = Input(Bool())

    // Pipeline I/O
    val in = DeqIO(new ExecuteOutput)

    // Writeback data to ReadOperands stage
    val regDestIdx = Output(UInt(5.W))
    val regDestData = Output(UInt(32.W))
    val regDestEn = Output(Bool())
  })

  // Cycle and instruction counting - (TODO: connect these somewhere)
  val cycles = RegInit(0.U(64.W))
  val insts = RegInit(0.U(64.W))

  // Only count cycles when we are actively processing instructions, not on halt or clear
  when(io.clear) {
    cycles := 0.U
  }.elsewhen(!io.halt) {
    cycles := cycles + 1.U
  }

  // We can accept data if:
  // - we are not in halt or clear
  io.in.ready := !io.halt && !io.clear

  io.regDestEn := false.B
  io.regDestIdx := 0.U
  io.regDestData := 0.U

  when(io.clear || io.halt) {
    // Do not allow a write on halt or clear
    io.regDestEn := false.B
  }.elsewhen(io.in.fire) {
    io.regDestIdx := io.in.bits.regDestIdx
    io.regDestEn := io.in.bits.control.writeToReg
    io.regDestData := MuxLookup(
      io.in.bits.control.regFileWriteSrc,
      0.U(32.W)
    )(
      Seq(
        RegFileWriteSrc.data -> io.in.bits.memResult,
        RegFileWriteSrc.aluResult -> io.in.bits.aluResult,
        RegFileWriteSrc.pcPlusFour -> io.in.bits.fetch.pcPlus4,
        RegFileWriteSrc.immediate -> io.in.bits.immediate
      )
    )

    insts := insts + 1.U
  }

  // For debugging: inst and pc gets optimized out as later stages don't use them - wrap in dontTouch
  // so they stay through the entire pipeline and its easy to see which stage is at which instruction
  val wire = dontTouch(io.in.bits.fetch.inst)
  val wire1 = dontTouch(io.in.bits.fetch.pc)
  val wire2 = dontTouch(cycles)
  val wire3 = dontTouch(insts)
}
