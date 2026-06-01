package cpu

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

class HazardUnit extends Module {
  val io = IO(new Bundle {
    val pcOverride = Input(Bool())

    val stallPc = Output(Bool())
    val stallFetchOutput = Output(Bool())
    val flushFetchOutput = Output(Bool())
    val flushDecodeOutput = Output(Bool())

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

  io.stallPc := lwStall
  io.stallFetchOutput := lwStall
  io.flushFetchOutput := io.pcOverride
  io.flushDecodeOutput := lwStall | io.pcOverride
}
