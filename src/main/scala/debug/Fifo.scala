package debug

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import chisel3.layers.Verification
import chisel3.ltl.AssertProperty
import chisel3.ltl.Sequence._
import chisel3.ltl.CoverProperty
import formal.Utils.past

class Fifo[T <: Data](gen: T, bufferSize: Int, emitFormal: Boolean = false) extends Module {
  val io = IO(new Bundle {
    // in.ready goes LO when the queue is full
    val in = Flipped(DecoupledIO(gen))

    // in.valid goes LO when the queue is empty
    val out = DecoupledIO(gen)
  })

  // proper RAM init: make sure bufferSize is power of two
  assert(
    bufferSize > 0 && ((bufferSize & (bufferSize - 1)) == 0),
    s"Incorrect buffer size ${bufferSize} - must be power of 2"
  )

  val addrSize = log2Ceil(bufferSize) + 1
  val writeAddr = RegInit(0.U(addrSize.W))
  val readAddr = RegInit(0.U(addrSize.W))

  val buf = SyncReadMem(bufferSize, gen)
  val bufCount = writeAddr - readAddr
  val empty = (bufCount === 0.U)
  // for 4 element buffer, valid indices are 0,1,2,3
  // if bufCount gets to 4 that means a subsequent write would be out-of-bounds
  val full = (bufCount === bufferSize.U)

  val write = io.in.valid && !full
  val read = io.out.ready && !empty
  io.in.ready := !full
  io.out.valid := !empty

  when(write === true.B) {
    writeAddr := writeAddr + 1.U
    // TODO: use only all the bits in the address - MSB
    buf.write(writeAddr, io.in.bits)
  }

  when(read === true.B) {
    readAddr := readAddr + 1.U
  }
  // TODO: use only all the bits in the address - MSB
  io.out.bits := buf.read(readAddr)

  assert(!(write && full), "wrote while full")
}
