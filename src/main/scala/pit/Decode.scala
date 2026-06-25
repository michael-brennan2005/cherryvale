package pit

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util._

class DecodeOutput extends Bundle {
  val control = new ControlSignals

  val reg1Idx = UInt(5.W)
  val reg2Idx = UInt(5.W)
  val regDestIdx = UInt(5.W)

  val immediate = UInt(32.W)

  // pc, pc+4, instruction
  val fetch = new FetchOutput
}
// Decode stage - handles control signal generation, getting register operand indices and computing
// instruction immediate
class Decode extends Module {
  val io = IO(new Bundle {
    // Global CPU signals
    val halt = Input(Bool())
    val clear = Input(Bool())

    // PC redirection - comes from execute. When a branch/jump is taken, the instruction held in our
    // output register is on the wrong path, so we flush it (same machinery as `clear`).
    val redirectPc = Input(Bool())

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

  // A redirect or clear flushes the held output this cycle. `clear` is a full pipeline reset;
  // `redirectPc` squashes the wrong-path instruction behind a taken branch. They share flush machinery.
  val flush = io.redirectPc || io.clear

  // Output register. Priority: flush discards, halt freezes, otherwise load the next decoded
  // instruction or drain the current one.
  when(flush) {
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
  // - we can move the decoded result into output reg - NOT(output consumer isnt ready but output is valid)
  io.in.ready := !io.halt && !flush && !(!io.out.ready && outValid)
}
