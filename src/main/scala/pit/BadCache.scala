package pit

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import chisel3.util.experimental.loadMemoryFromFileInline
import harness.Elaboratable
import trunk.Request
import trunk.Response

// Memory request interface for the sim back-door port only. The real core<->cache interface is
// cherrytrunk (see io.req/io.resp); this is a separate, always-0-latency port tests use to seed and
// inspect memory without going through the bus handshake.
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
// The core-facing port speaks cherrytrunk (Request/Response): a transaction starts on req.stb and
// completes on the single-cycle resp.ack pulse. The cache is a single-outstanding slave - it holds
// one transaction at a time and acks `responseLatency + 1` cycles after the strobe (the +1 is the
// inherent SyncReadMem read latency). responseLatency is a test knob simulating a future bus fetch;
// masters/tests must poll resp.ack, never hand-count cycles.
class BadCache(memInitFile: Option[String], responseLatency: Int = 0, sim: Boolean = false)
    extends Module {
  require(responseLatency >= 0, "responseLatency must be non-negative")

  val io = IO(new Bundle {
    // we use cherrytrunk for the core<->cache interface
    val req = Input(new Request)
    val resp = Output(new Response)

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
    // The exposed sim back-door port is always 0 responseLatency and independent of the bus FSM.
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
    resp.valid := RegNext(req.fire, false.B)
  }

  // ---- Cherrytrunk slave: single-outstanding transaction FSM ----
  // `busy` is the transaction-in-progress flag (the role the protocol's `cyc` plays master-side):
  // set on the strobe, cleared on ack. `cnt` counts down the artificial stall to the ack cycle.
  val busy = RegInit(false.B)
  val cnt = RegInit(0.U(32.W))
  val addrReg = Reg(UInt(32.W))
  val weReg = Reg(Bool())
  val maskReg = Reg(UInt(4.W))
  val wdReg = Reg(UInt(32.W))

  // A strobe while idle starts a transaction. (A well-behaved master also holds cyc; we key off stb
  // like the other slaves in the SoC.) `completing` is the single ack cycle.
  val accept = !busy && io.req.stb
  val completing = busy && (cnt === 0.U)

  when(accept) {
    busy := true.B
    cnt := responseLatency.U
    addrReg := io.req.addr
    weReg := io.req.we
    maskReg := io.req.mask
    wdReg := io.req.data
  }.elsewhen(busy) {
    when(completing) { busy := false.B }
      .otherwise { cnt := cnt - 1.U }
  }

  // The SyncReadMem read must fire one cycle before `completing` so its registered output is ready
  // on the ack cycle. At responseLatency==0 that cycle is the accept cycle itself (read the live
  // request fields); otherwise it is the cnt===1 cycle (read the latched fields).
  val readData = Wire(UInt(32.W))
  if (responseLatency == 0) {
    val readFire = accept && !io.req.we
    readData := mem.read(io.req.addr >> 2, readFire).asUInt
  } else {
    val readFire = busy && (cnt === 1.U) && !weReg
    readData := mem.read(addrReg >> 2, readFire).asUInt
  }

  // Commit a write on the ack cycle. Read and write never coincide within a transaction (one is
  // gated by we, the other by !we), and single-outstanding rules out cross-transaction collisions.
  when(completing && weReg) {
    mem.write(addrReg >> 2, laneData(wdReg), laneMask(maskReg))
  }

  io.resp.ack := completing
  // resp.data must be 0 whenever ack is low (cherrytrunk slave obligation); a write acks with 0.
  io.resp.data := Mux(completing && !weReg, readData, 0.U)
  io.resp.err := false.B // this memory never errs
}

object EmitBadCache extends Elaboratable {
  def build: RawModule = new BadCache(None)
}
