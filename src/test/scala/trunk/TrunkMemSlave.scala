package trunk

import chisel3._
import chisel3.util._
import pit.MemoryRequest

/** Test-only behavioral CherryTrunk *slave* -- the bus-side counterpart to the master-side
  * [[BusMaster]] BFM. Lets us exercise a cherrytrunk *master* (e.g. `pit.MemoryMaster`) against a
  * real, latency-configurable memory without standing up a whole SoC.
  *
  * Protocol obligations honored (see `harness.CherrytrunkProperties`):
  *   - accepts a transaction on a single-cycle `req.stb` pulse while `req.cyc` is held;
  *   - drives `resp.ack` for exactly one cycle to complete the transaction;
  *   - `resp.data` is 0 except on the ack cycle;
  *   - read data is presented on the ack cycle, masked write committed on the ack cycle.
  *
  * `latency` injects extra wait cycles before the ack so we can open a multi-cycle window and watch
  * the master's stb-pulse / cyc-hold / field-stability behavior. `latency == 0` acks the cycle after
  * the strobe (the inherent SyncReadMem read latency).
  *
  * Reads return the FULL stored word regardless of `req.mask`: that matches the `MemoryRequest` read
  * contract the core relies on (see `pit.MemoryRequest.we`). CherryTrunk's "zero the masked-out
  * bytes on a read" semantic is intentionally not modeled here -- it isn't part of the core<->cache
  * contract this slave stands in for.
  *
  * TODO: `resp.err` is always false. Bus-error propagation is out of scope until the core-side
  * interface grows an error channel.
  *
  * `sim` adds a 0-latency `simReq`/`simResp` port (mirrors `pit.BadCache`'s sim block) so tests can
  * seed/inspect memory deterministically without going through the bus.
  */
class TrunkMemSlave(words: Int = 256, latency: Int = 0, sim: Boolean = false) extends Module {
  require(latency >= 0, "latency must be non-negative")

  val io = IO(new Bundle {
    val req = Input(new Request)
    val resp = Output(new Response)

    val simReq = if (sim) Some(DeqIO(new MemoryRequest)) else None
    val simResp = if (sim) Some(Valid(UInt(32.W))) else None
  })

  // 256 words viewed as 4 byte-lanes so masked writes need no read-modify-write (same trick as
  // pit.BadCache); lane i == bits [8i+7 : 8i].
  val mem = SyncReadMem(words, Vec(4, UInt(8.W)))
  private def laneData(word: UInt): Vec[UInt] =
    VecInit(Seq.tabulate(4)(i => word(8 * i + 7, 8 * i)))
  private def laneMask(mask: UInt): Vec[Bool] =
    VecInit(Seq.tabulate(4)(i => mask(i)))

  // ---- Single-outstanding transaction FSM ----
  val busy = RegInit(false.B)
  val cnt = Reg(UInt(32.W))
  val addrReg = Reg(UInt(32.W))
  val weReg = Reg(Bool())
  val maskReg = Reg(UInt(4.W))
  val dataReg = Reg(UInt(32.W))

  val accept = !busy && io.req.stb && io.req.cyc
  val completing = busy && (cnt === 0.U)
  val preDone = busy && (cnt === 1.U) // cycle before completion: issue the read so data is live on
  // the ack cycle.

  when(accept) {
    busy := true.B
    cnt := latency.U
    addrReg := io.req.addr
    weReg := io.req.we
    maskReg := io.req.mask
    dataReg := io.req.data
  }.elsewhen(completing) {
    busy := false.B
  }.elsewhen(busy) {
    cnt := cnt - 1.U
  }

  // latency == 0 acks the cycle after accept, so its read must fire at accept; for larger latencies
  // it fires one cycle before completion (cnt === 1). Neither coincides with the write commit (the
  // completion cycle), so there is no same-address read/write collision.
  val readFire = if (latency == 0) accept else preDone
  val readIdx = Mux(accept, io.req.addr >> 2, addrReg >> 2)
  val memOut = mem.read(readIdx, readFire).asUInt

  when(completing && weReg) {
    mem.write(addrReg >> 2, laneData(dataReg), laneMask(maskReg))
  }

  io.resp.ack := completing
  io.resp.data := Mux(completing && !weReg, memOut, 0.U)
  io.resp.err := false.B

  if (sim) {
    // Always-ready 0-latency seed/inspect port. The read result is live the cycle after the access.
    val sreq = io.simReq.get
    val sresp = io.simResp.get
    sreq.ready := true.B
    val sIdx = sreq.bits.addr >> 2
    val sReadFire = sreq.fire && !sreq.bits.we
    sresp.bits := mem.read(sIdx, sReadFire).asUInt
    when(sreq.fire && sreq.bits.we) {
      mem.write(sIdx, laneData(sreq.bits.writeData), laneMask(sreq.bits.writeMask))
    }
    sresp.valid := RegNext(sReadFire, false.B)
  }
}
