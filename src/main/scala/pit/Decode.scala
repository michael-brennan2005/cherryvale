package pit

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util._

class DecodeOutput extends Bundle {
  val control = Output(new ControlSignals)

  val reg1Idx = Output(UInt(5.W))
  val reg2Idx = Output(UInt(5.W))
  val regDestIdx = Output(UInt(5.W))

  val immediate = Output(UInt(32.W))

  // pc, pc+4, instruction
  val fetch = new FetchOutput
}
// Decode stage - handles control signal generation, and getting register operand indices and
// instruction immediate
class Decode extends Module {
  val io = IO(new Bundle {
    // Global CPU signals
    val halt = Input(Bool())
    val clear = Input(Bool())

    // Pipeline I/O
    val in = DeqIO(new FetchOutput)
    val out = EnqIO(new DecodeOutput)
  })

  // Register & module definitions
  val control = Module(new ControlUnit)
  val outBits = RegInit(0.U.asTypeOf(new DecodeOutput))
  val outValid = RegInit(false.B)

  // Decode logic
  val inst = io.in.bits.inst
  val reg1Idx = inst(19, 15)
  val reg2Idx = inst(24, 20)
  val regDestIdx = inst(11, 7)

  control.io.inst := inst
  val controlSignals = control.io.control
  // format: off
  val immediate = MuxLookup(controlSignals.immEncoding, 0.U(32.W))(
    Seq(
      ImmediateEncoding.iType -> inst(31, 20).asSInt.pad(32).asUInt,
      ImmediateEncoding.sType -> Cat(inst(31, 25), inst(11, 7)).asSInt.pad(32).asUInt,
      ImmediateEncoding.bType -> Cat(inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W)).asSInt.pad(32).asUInt,
      ImmediateEncoding.jType -> Cat(inst(31), inst(20), inst(30, 21), 0.U(1.W)).asSInt.pad(32).asUInt,
      ImmediateEncoding.uType -> Cat(inst(31, 12), Fill(12, "b0".U(1.W)))
    )
  )
  // format: on

  // Output register. Priority: flush discards, halt freezes, otherwise load the next decoded
  // instruction or drain the current one.
  when(io.clear) {
    outValid := false.B
  }.elsewhen(io.halt) {
    /* hold the output registers*/
  }.elsewhen(io.in.fire) {
    outValid := true.B
    outBits.control := controlSignals
    outBits.reg1Idx := reg1Idx
    outBits.reg2Idx := reg2Idx
    outBits.regDestIdx := regDestIdx
    outBits.immediate := immediate
    outBits.fetch := io.in.bits
  }.elsewhen(io.out.ready) {
    outValid := false.B
  }
  io.out.bits := outBits
  io.out.valid := outValid

  // We can accept data if:
  // - we are not in halt or clear
  // - we can move the decoded result into output reg - NOT(output isnt ready but output is valid)
  io.in.ready := !io.halt && !io.clear && !(!io.out.ready && outValid)

}
