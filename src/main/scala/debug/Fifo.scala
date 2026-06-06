package debug

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import chisel3.layers.Verification
import chisel3.ltl.AssertProperty
import chisel3.ltl.Sequence._
import chisel3.ltl.CoverProperty
import formal.Formal
import formal.Utils.implies
import formal.Utils.anyconst

class Fifo[T <: Data](gen: T, bufferSize: Int) extends Module {
  val io = IO(new Bundle {
    // Enqueue channel - ready goes lo when queue is full
    val enq = Flipped(DecoupledIO(gen))

    // Dequeue channel - valid goes lo when queue is empty
    val deq = DecoupledIO(gen)
  })

  // proper RAM init: make sure bufferSize is power of two
  assert(
    bufferSize > 0 && ((bufferSize & (bufferSize - 1)) == 0),
    s"Incorrect buffer size ${bufferSize} - must be power of 2"
  )

  // Address size is 1 more bit than actually needed so we can detect if an address has wrapped
  // around using its MSB.
  val addrSize = log2Ceil(bufferSize) + 1
  val writeAddr = RegInit(0.U(addrSize.W))
  val readAddr = RegInit(0.U(addrSize.W))

  val buf = Mem(bufferSize, gen)
  val bufCount = writeAddr - readAddr
  val empty = (bufCount === 0.U)
  // for 4 element buffer, valid indices are 0,1,2,3
  // if bufCount gets to 4 that means a subsequent write would be out-of-bounds
  val full = (bufCount === bufferSize.U)

  val write = io.enq.valid && !full
  val read = io.deq.ready && !empty
  io.enq.ready := !full
  io.deq.valid := !empty

  when(write === true.B) {
    writeAddr := writeAddr + 1.U
    buf.write(writeAddr(addrSize - 2, 0), io.enq.bits)
  }

  when(read === true.B) {
    readAddr := readAddr + 1.U
  }
  io.deq.bits := buf.read(readAddr(addrSize - 2, 0))

  // Count can never exceed the bufferSize
  assert(bufCount <= bufferSize.U)

  // In case a rewrite changes anything, have these assertions to ensure buffer counting still works.
  assert(empty === (bufCount === 0.U))
  assert(full === (bufCount === bufferSize.U))
  assert(bufCount === (writeAddr - readAddr))

  // Prove that we can write two arbitrary values in succession, then read those same values back later.

  // Data defintions
  val firstAddr = anyconst(UInt(addrSize.W))
  val firstData = anyconst(gen)
  val distanceToFirst = (firstAddr - readAddr)
  val firstAddrInFifo = (!empty && distanceToFirst < bufCount)

  val secondAddr = firstAddr + 1.U
  val secondData = anyconst(gen)
  val distanceToSecond = (secondAddr - readAddr)
  val secondAddrInFifo = (!empty && distanceToSecond < bufCount)

  // FSM for tracking writes and reads
  val waitForFirstWrite :: waitForSecondWrite :: waitForFirstRead :: waitForSecondRead :: nil =
    Enum(4)
  val state = RegInit(waitForFirstWrite)
  switch(state) {
    is(waitForFirstWrite) {
      when(write && (writeAddr === firstAddr) && (io.enq.bits === firstData)) {
        state := waitForSecondWrite
      }
    }
    is(waitForSecondWrite) {
      when(read && (readAddr === firstAddr)) {
        // abort if we read the first value out before writing the second
        state := waitForFirstWrite
      }.elsewhen(write) {
        // abort if wrong value is written (should secondData since secondAddr = firstAddr + 1),
        // otherwise move to next state
        state := Mux(io.enq.bits === secondData, waitForFirstRead, waitForFirstWrite)
      }
    }
    is(waitForFirstRead) {
      when(read && readAddr === firstAddr) {
        state := waitForSecondRead
      }
    }
    is(waitForSecondRead) {
      when(read) {
        state := waitForFirstWrite
      }
    }
  }

  // Assertions
  // By waitForSecondWrite:
  // - first value must be in FIFO
  // - we must be waiting at secondAddress to write the 2nd piece of data
  assert(implies(state === waitForSecondWrite, firstAddrInFifo))
  assert(implies(state === waitForSecondWrite, buf(firstAddr) === firstData))
  assert(implies(state === waitForSecondWrite, writeAddr === secondAddr))

  // By waitForFirstRead:
  // - first value must be in FIFO
  // - second value must be in FIFO
  assert(implies(state === waitForFirstRead, firstAddrInFifo))
  assert(implies(state === waitForFirstRead, buf(firstAddr) === firstData))
  assert(implies(state === waitForFirstRead, secondAddrInFifo))
  assert(implies(state === waitForFirstRead, buf(secondAddr) === secondData))

  when(readAddr === firstAddr) {
    assert(implies(state === waitForFirstRead, io.deq.bits === firstData))
  }

  // By waitForSecondRead:
  // - only the second value needs to be in the FIFO
  // - the output data should match our second value until the next read
  assert(implies(state === waitForSecondRead, secondAddrInFifo))
  assert(implies(state === waitForSecondRead, buf(secondAddr) === secondData))

  assert(implies(state === waitForSecondRead, io.deq.bits === secondData))
}

object FifoFormal extends Formal {
  def build = new Fifo(UInt(8.W), 16)
}
