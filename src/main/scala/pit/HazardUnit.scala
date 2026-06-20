package pit

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// Hazarding unit. Philosophy is all logic for stalls, flushes, and forwards should be
// contained in this unit.
class HazardUnit extends Module {
  val io = IO(new Bundle {
    // CPU halt - default behavior is all pipeline registers should stall on a halt
    val halt = Input(Bool())

    // pcRedirect - thrown by execute stage, used to check if branch or jump should be taken
    val pcRedirect = Input(Bool())

    // ready/valid signals of I$, used to know if memory load/store is completed or still in progress
    val iCacheRespValid = Input(Bool())
    val iCacheReqReady = Input(Bool())
    val dCacheRespValid = Input(Bool())
    val dCacheReqReady = Input(Bool())

    // see registerUpdateStall for why these inputs are necessary.
    // These need to come from decode stage, not decode stage's output PSR!
    val decodeReg1Idx = Input(UInt(5.W))
    val decodeReg2Idx = Input(UInt(5.W))
    val decodeOutRegDestIdx = Input(UInt(5.W))
    val executeOutRegDestIdx = Input(UInt(5.W))
    // TODO: not super sure about the logic here - this comes from Core.dCacheReg - does that tell us
    // "what register is the in-progress D$ access going to update?"
    val dCacheRegDestIdx = Input(UInt(5.W))
    val memoryOutRegDestIdx = Input(UInt(5.W))

    // Stall and flushes for all PSRs.
    val stallPcOut = Output(Bool())
    val flushPcOut = Output(Bool())

    val stallICache = Output(Bool())

    val stallFetchOut = Output(Bool())
    val flushFetchOut = Output(Bool())

    val stallDecodeOut = Output(Bool())
    val flushDecodeOut = Output(Bool())

    val stallExecuteOut = Output(Bool())
    val flushExecuteOut = Output(Bool())

    val stallDCache = Output(Bool())

    val stallMemoryOut = Output(Bool())
    val flushMemoryOut = Output(Bool())
  })

  // The decode stage is where register operands and indices are fetched. But what if one of the
  // registers for a particular instruction will have its data updated by an instruction further up
  // the line (by an arithmetic inst, lw, etc.?).
  // For now, if a register operand is the destination register of an instruction up the pipeline, we
  // stall until that is no longer the case (i.e register has been updated, and now holds the proper
  // value).
  // TODO: This is super inefficient, and can be fixed with forwarding, maybe splitting decode logic
  // (moving register operand decoding earlier), etc.
  val registerUpdateStall = {
    val reg1NotZero = (io.decodeReg1Idx =/= 0.U)
    val reg1Invalid =
      (io.decodeReg1Idx === io.decodeOutRegDestIdx
        || io.decodeReg1Idx === io.executeOutRegDestIdx
        || io.decodeReg1Idx === io.dCacheRegDestIdx
        || io.decodeReg1Idx === io.memoryOutRegDestIdx)

    val reg2NotZero = (io.decodeReg2Idx =/= 0.U)
    val reg2Invalid =
      (io.decodeReg2Idx === io.decodeOutRegDestIdx
        || io.decodeReg2Idx === io.executeOutRegDestIdx
        || io.decodeReg2Idx === io.dCacheRegDestIdx
        || io.decodeReg2Idx === io.memoryOutRegDestIdx)

    (reg1NotZero && reg1Invalid) || (reg2NotZero && reg2Invalid)
  }

  // General stall logic is that if any stage is taking multiple cycles, then all preceding stages
  // should be stalled. General flush logic is that if any stage is taking multiple cycles, its output
  // register should be flushed (so bogus data does not proceed through the pipeline as though it
  // represents valid code).

  // Stall PC if:
  // - I$ is busy with memory request
  // - D$ is busy with memory request
  // - The proper register operands have not been computed yet (see registerUpdateStall)
  io.stallPcOut := io.halt || !io.iCacheReqReady || !io.dCacheReqReady || registerUpdateStall
  io.flushPcOut := false.B

  // Stall I$ if:
  // - D$ is busy with memory request
  // - The proper register operands have not been computed yet (see registerUpdateStall)
  io.stallICache := io.halt

  // Stall fetchOut if:
  // - D$ is busy with memory request
  // - The proper register operands have not been computed yet (see registerUpdateStall)
  io.stallFetchOut := io.halt || !io.dCacheReqReady || registerUpdateStall
  // Flush fetchOut if:
  // - A valid memory read is not being outputted by I$
  // - A branch has been taken (fetchOut now contains code that shouldn't be executed). The RegNext
  //   is there so we flush whatever instruction was being outputted by the preceding stage, the I$.
  //   There's no way to really flush the output register of I$ so this workaround suffices (TODO:
  //   does it?)
  io.flushFetchOut :=
    (!io.iCacheRespValid && !io.stallFetchOut) ||
      io.pcRedirect ||
      RegNext(io.pcRedirect, false.B)

  // Stall decodeOut if:
  // - D$ is busy with memory request.
  io.stallDecodeOut := io.halt || !io.dCacheReqReady
  // Flush decodeOut if:
  // - A branch has been taken (decodeOut now contains code that shouldn't be executed).
  // - The proper register operands have not been computed yet (see registerUpdateStall)
  io.flushDecodeOut := io.pcRedirect || registerUpdateStall

  // Stall executeOut if:
  // - D$ is busy with memory request.
  io.stallExecuteOut := io.halt || !io.dCacheReqReady
  io.flushExecuteOut := false.B

  io.stallDCache := io.halt

  io.stallMemoryOut := io.halt
  // Flush memoryOut if:
  // - A valid memory read is not being outputted by D$
  io.flushMemoryOut := false.B || !io.dCacheRespValid

  // // Forward for data hazards
  // // format: off

  // // We should forward result from a stage if that stage will write a destination register and the destination
  // // register matches the source register, so that a preceding stage doesn't use an incorrect value.
  // val forwardFromMemoryA = (io.executeReg1Idx === io.memoryRegDestIdx) && io.memoryWriteToReg
  // val forwardFromWritebackA = (io.executeReg1Idx === io.writebackRegDestIdx) && io.writebackWriteToReg

  // // Because the memory stage will have the most recently executed instruction, it should take precedence
  // // over writeback. Also, x0 is hardwired to 0 and should never be forwarded, so we check for that.
  // when(forwardFromMemoryA && (io.executeReg1Idx =/= 0.U)) {
  //   io.executeAInputSel := "b10".U
  // }.elsewhen(forwardFromWritebackA && (io.executeReg1Idx =/= 0.U)) {
  //   io.executeAInputSel := "b01".U
  // }.otherwise {
  //   io.executeAInputSel := "b00".U
  // }

  // val forwardFromMemoryB = (io.executeReg2Idx === io.memoryRegDestIdx) && io.memoryWriteToReg
  // val forwardFromWritebackB = (io.executeReg2Idx === io.writebackRegDestIdx) && io.writebackWriteToReg

  // when(forwardFromMemoryB && (io.executeReg2Idx =/= 0.U)) {
  //   io.executeBInputSel := "b10".U
  // }.elsewhen(forwardFromWritebackB && (io.executeReg2Idx =/= 0.U)) {
  //   io.executeBInputSel := "b01".U
  // }.otherwise {
  //   io.executeBInputSel := "b00".U
  // }
  // // format: on

  // // Stall when a load hazard occurs
  // val lwStall =
  //   (io.executeRegFileWriteSrc === RegFileWriteSrc.data) && (io.executeRegDestIdx =/= 0.U) && ((io.decodeReg1Idx === io.executeRegDestIdx) | (io.decodeReg2Idx === io.executeRegDestIdx))

}
