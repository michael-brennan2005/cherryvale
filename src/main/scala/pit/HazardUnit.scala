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

    // pcOverride - thrown by execute stage when branch should be taken
    val pcOverride = Input(Bool())

    // Stall and flushes for all PSRs.
    val stallPc = Output(Bool())
    val flushPc = Output(Bool())

    val stallFetch = Output(Bool())
    val flushFetch = Output(Bool())

    val stallDecode = Output(Bool())
    val flushDecode = Output(Bool())

    val stallExecute = Output(Bool())
    val flushExecute = Output(Bool())

    val stallMemory = Output(Bool())
    val flushMemory = Output(Bool())

    // Forwarding logic - register indices inputs and ALU slection outputs
    val decodeReg1Idx = Input(UInt(5.W))
    val decodeReg2Idx = Input(UInt(5.W))

    val executeReg1Idx = Input(UInt(5.W))
    val executeReg2Idx = Input(UInt(5.W))
    val executeRegDestIdx = Input(UInt(5.W))

    // TODO: better names (these are for forwarding)
    val executeAInputSel = Output(UInt(2.W))
    val executeBInputSel = Output(UInt(2.W))
    val executeRegFileWriteSrc = Input(RegFileWriteSrc())

    val memoryRegDestIdx = Input(UInt(5.W))
    val memoryWriteToReg = Input(Bool())

    val writebackRegDestIdx = Input(UInt(5.W))
    val writebackWriteToReg = Input(Bool())
  })

  // Thank you Harris & Harris!

  // Forward for data hazards
  // format: off

  // We should forward result from a stage if that stage will write a destination register and the destination
  // register matches the source register, so that a preceding stage doesn't use an incorrect value.
  val forwardFromMemoryA = (io.executeReg1Idx === io.memoryRegDestIdx) && io.memoryWriteToReg
  val forwardFromWritebackA = (io.executeReg1Idx === io.writebackRegDestIdx) && io.writebackWriteToReg

  // Because the memory stage will have the most recently executed instruction, it should take precedence
  // over writeback. Also, x0 is hardwired to 0 and should never be forwarded, so we check for that.
  when(forwardFromMemoryA && (io.executeReg1Idx =/= 0.U)) {
    io.executeAInputSel := "b10".U
  }.elsewhen(forwardFromWritebackA && (io.executeReg1Idx =/= 0.U)) {
    io.executeAInputSel := "b01".U
  }.otherwise {
    io.executeAInputSel := "b00".U
  }

  val forwardFromMemoryB = (io.executeReg2Idx === io.memoryRegDestIdx) && io.memoryWriteToReg
  val forwardFromWritebackB = (io.executeReg2Idx === io.writebackRegDestIdx) && io.writebackWriteToReg

  when(forwardFromMemoryB && (io.executeReg2Idx =/= 0.U)) {
    io.executeBInputSel := "b10".U
  }.elsewhen(forwardFromWritebackB && (io.executeReg2Idx =/= 0.U)) {
    io.executeBInputSel := "b01".U
  }.otherwise {
    io.executeBInputSel := "b00".U
  }
  // format: on

  // Stall when a load hazard occurs
  val lwStall =
    (io.executeRegFileWriteSrc === RegFileWriteSrc.data) && (io.executeRegDestIdx =/= 0.U) && ((io.decodeReg1Idx === io.executeRegDestIdx) | (io.decodeReg2Idx === io.executeRegDestIdx))

  val newExecStall =
    (io.memoryRegDestIdx =/= 0.U) && ((io.memoryRegDestIdx === io.executeReg1Idx) || (io.memoryRegDestIdx === io.executeReg2Idx))
  io.stallPc := lwStall || newExecStall || io.halt
  io.flushPc := false.B

  io.stallFetch := lwStall || newExecStall || io.halt
  io.flushFetch := io.pcOverride

  io.stallDecode := lwStall || newExecStall || io.halt
  io.flushDecode := io.pcOverride

  io.stallExecute := io.halt
  io.flushExecute := false.B

  io.stallMemory := io.halt
  io.flushMemory := false.B
}
