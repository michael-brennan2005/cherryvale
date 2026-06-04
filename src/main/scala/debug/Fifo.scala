package debug

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import chisel3.layers.Verification
import chisel3.ltl.AssertProperty
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

  if (emitFormal) {
    formal.DecoupledProperties.emitRx(io.in)
    formal.DecoupledProperties.emitTx(io.out)
    // element count can never exceed 2^N elements
    AssertProperty(bufCount <= bufferSize.U)

    // checks on bufCount, empty, and fill to catch any issues that may arise from rewriting things
    // later
    AssertProperty(bufCount === writeAddr - readAddr)
    AssertProperty(empty === (bufCount === 0.U))
    AssertProperty(full === (bufCount === bufferSize.U))

    // Verify that if we write two arbitrary values in succession, we can read those same values
    // back later
    val firstAddr = Reg(UInt(addrSize.W))
    val secondAddr = Reg(UInt(addrSize.W))

    val firstData = Reg(gen)
    val secondData = Reg(gen)

    val distanceToFirst = firstAddr - readAddr
    val firstAddrInFifo = Wire(Bool())

    val distanceToSecond = secondAddr - readAddr
    val secondAddrInFifo = Wire(Bool())

    when(!empty && distanceToFirst < bufCount) {
      firstAddrInFifo := true.B
    }.otherwise {
      firstAddrInFifo := false.B
    }

    when(!empty && distanceToSecond < bufCount) {
      secondAddrInFifo := true.B
    }.otherwise {
      secondAddrInFifo := false.B
    }

    val state = RegInit(0.U(2.W))
    switch(state) {
      is(0.U) {
        // IDLE state - start by writing first value to FIFO at firstAddr
        when(write && (writeAddr === firstAddr) && (io.in.bits === firstData)) {
          state := 1.U
        }
      }
      is(1.U) {
        // Assert first value has been written, and that we are waiting at second address to write
        // 2nd piece of data
        AssertProperty(firstAddrInFifo)
        AssertProperty(buf(firstAddr) === firstData)
        AssertProperty(writeAddr === secondAddr)

        // if we read the first value out at this stage then abort our check
        when(read && (readAddr === firstAddr)) {
          state := 0.U
        }.elsewhen(write) {
          // Otherwise if we write the second value then move to the next state - if its the wrong
          // value then abort the check
          state := Mux(
            io.in.bits === secondData,
            2.U,
            0.U
          )
        }
      }
      is(2.U) {
        // Assert first value is still in FIFO
        AssertProperty(firstAddrInFifo)
        AssertProperty(buf(firstAddr) === firstData)

        // Assert second value is now in fifo
        AssertProperty(secondAddrInFifo)
        AssertProperty(buf(secondAddr) === secondData)

        when(readAddr === firstAddr) {
          AssertProperty(io.out.bits === firstData)
        }

        // Wait until we read the first value back out of the FIFO
        when(read && readAddr === firstAddr) {
          state := 3.U
        }
      }
      is(3.U) {
        // Only second value needs to be in FIFO
        AssertProperty(secondAddrInFifo)
        AssertProperty(buf(secondAddr) === secondData)

        // Output data must match our second value until the next read
        AssertProperty(io.out.bits === secondData)

        // Finally, return to idle when the last item is read
        when(read) {
          state := 0.U
        }
      }
    }

    // Cover properties
    val wasFull = RegInit(false.B)
    when(full) {
      wasFull := true.B
    }

    CoverProperty(wasFull && empty)
    CoverProperty(past(wasFull, 2) && (!past(wasFull) && full))
  }
}

// emit just FIFO for formal verification
object FifoFormal extends App {
  ChiselStage.emitSystemVerilogFile(
    new Fifo(UInt(8.W), 8, true),
    args = Array(
      "--target-dir",
      "./build/sv/"
    ),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-default-layer-specialization=enable",
      "-preserve-values=named",
      "-preserve-aggregate=all",
      "--disable-opt-passes",
      "--lowering-options=disallowLocalVariables"
    )
  )
}
