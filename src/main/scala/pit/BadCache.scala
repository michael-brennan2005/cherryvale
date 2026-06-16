package pit

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import chisel3.util.experimental.loadMemoryFromFileInline
import harness.Elaboratable

// Memory request interface, which will be wrapped in a Decoupled interface by the cache.
class MemoryRequest extends Bundle {
  // Read/write address - The memory unit expects a 4-byte aligned address - lower 2 bits of any
  // address will be ignored.
  val addr = UInt(32.W)

  // Byte mask for writes. Valid values are 0b0000, 0b1111 (full word), 0b0011, 0b1100 (half word),
  // 0b0001, 0b0010, 0b0100, 0b1000 (byte).
  val writeMask = UInt(4.W)

  // Write data for writes. Write data MUST be lane-aligned - bytes to write already sit in their
  // final positions; the memory unit will do no shifting to correct alignment.
  // Example: A halfword store to address 0x2 should be encoded as writeMask=0b1100,
  // writeData(31,16) = data.
  val writeData = UInt(32.W)

  // True for a memory write, false for a memoy read. writeMask has no effect on output data for a
  // read - the full 32-bit word will be returned.
  val we = Bool()
}

// Data and instruction memory for this "bad cache" verison of the core.
// Idea is we'd like to have infrastructure & necessary CPU logic in place for when we actually have
// proper instruction and data caches. For this, each cache can arbitrarily stall (in the case
// it needs to fetch data/code from bus), should have proper write and read mask management,
// and there should be two separate units for instructions and for data.
//
// HOWEVER, at this point we are not implementing actual caching or fetching on the bus to a real RAM
// unit. So instead we have "bad cache", which is just a singular block of memory - 1kB of byte
// addressable memory, stored as 256 32-bit words. This means that data and code will technically
// have completely separate address spaces for a "true" Harvard architecture, which is a compromise
// I'm willing to make right now.
//
// responseLatency is for testing, to simulate a future bus fetch that may take multiple cycles.
// responseLatency = 0 results in plain synchronous read behavior, or one access per cycle.
class BadCache(memInitFile: Option[String], responseLatency: Int = 0, sim: Boolean = false)
    extends Module {
  require(responseLatency >= 0, "responseLatency must be non-negative")

  val io = IO(new Bundle {
    val req = DeqIO(new MemoryRequest)
    // Data is REGISTERED output, because we are using SyncReadMem which does a synchronous read.
    val resp = Valid(UInt(32.W))

    val simReq = if (sim) Some(DeqIO(new MemoryRequest)) else None
    val simResp = if (sim) Some(Valid(UInt(32.W))) else None
  })

  // 256 words viewed as 4 byte-lanes so we can do masked (byte-enabled) writes WITHOUT
  // read-modify-write. Lane i == bits [8i+7 : 8i]; lane 0 is the least-significant byte.
  val mem = SyncReadMem(256, Vec(4, UInt(8.W)))
  memInitFile match {
    case None       => ()
    case Some(path) =>
      loadMemoryFromFileInline(mem, path) // synthesis only; tests seed via the port
  }

  // Lane-aligned write data + per-lane enables from the byte mask.
  private def laneData(word: UInt): Vec[UInt] =
    VecInit(Seq.tabulate(4)(i => word(8 * i + 7, 8 * i)))
  private def laneMask(mask: UInt): Vec[Bool] =
    VecInit(Seq.tabulate(4)(i => mask(i)))

  if (sim) {
    // The exposed port for sim is always 0 responseLatency
    val req = io.simReq.get
    val resp = io.simResp.get

    req.ready := true.B
    val wordIdx = req.bits.addr >> 2
    val readFire = req.fire && !req.bits.we
    resp.bits := mem.read(wordIdx, readFire).asUInt
    when(req.fire && req.bits.we) {
      mem.write(wordIdx, laneData(req.bits.writeData), laneMask(req.bits.writeMask))
    }
    // An access issued this cycle completes next cycle (the inherent SyncReadMem read latency).
    resp.valid := RegNext(io.req.fire, false.B)
  }

  if (responseLatency == 0) {
    // ---- Hit path: plain synchronous SRAM, one access per cycle ----
    io.req.ready := true.B // always ready

    val wordIdx = io.req.bits.addr >> 2
    val readFire = io.req.fire && !io.req.bits.we
    io.resp.bits := mem.read(wordIdx, readFire).asUInt
    when(io.req.fire && io.req.bits.we) {
      mem.write(wordIdx, laneData(io.req.bits.writeData), laneMask(io.req.bits.writeMask))
    }
    // An access issued this cycle completes next cycle (the inherent SyncReadMem read latency).
    io.resp.valid := RegNext(io.req.fire, false.B)
  } else {
    // ---- Stall path: single-outstanding FSM, holds `valid` low for `responseLatency` cycles ----
    val busy = RegInit(false.B)
    val cnt = RegInit(0.U(32.W))
    val addrReg = Reg(UInt(32.W))
    val weReg = Reg(Bool())
    val maskReg = Reg(UInt(4.W))
    val wdReg = Reg(UInt(32.W))

    val accept = !busy && io.req.fire
    val completing = busy && (cnt === 0.U)
    val preDone = busy && (cnt === 1.U) // cycle before completion: issue the read so data is
    // ready on the completion cycle.

    io.req.ready := !busy

    when(accept) {
      busy := true.B
      cnt := responseLatency.U
      addrReg := io.req.bits.addr
      weReg := io.req.bits.we
      maskReg := io.req.bits.writeMask
      wdReg := io.req.bits.writeData
    }.elsewhen(busy) {
      when(completing) { busy := false.B }
        .otherwise { cnt := cnt - 1.U }
    }

    // responseLatency == 1 completes the cycle after accept, so its read must fire at accept; for
    // larger latencies it fires one cycle before completion (cnt === 1). Neither coincides with the
    // write commit (completion cycle), so there is no same-address read/write collision.
    val readFire =
      if (responseLatency == 1) accept && !io.req.bits.we
      else preDone && !weReg
    val readIdx = Mux(accept, io.req.bits.addr >> 2, addrReg >> 2)
    io.resp.bits := mem.read(readIdx, readFire).asUInt

    when(completing && weReg) {
      mem.write(addrReg >> 2, laneData(wdReg), laneMask(maskReg))
    }

    io.resp.valid := completing
  }

}

object EmitBadCache extends Elaboratable {
  def build: RawModule = new BadCache(None)
}
