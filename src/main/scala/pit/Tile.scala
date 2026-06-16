package pit

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util.DecoupledIO
import chisel3.util.DeqIO
import chisel3.util.Valid

// CherryPit core with all the fix-ins: instruction and data cache, exposed ports for testing/sim,
// and (TODO) cpu control unit for halting, resetting, register and CSR inspection, etc.
class Tile(memInitFile: Option[String], sim: Boolean = false, simCacheLatency: Int = 0)
    extends Module {
  val io = IO(new Bundle {
    val halt = Input(Bool())

    val dCacheReq = if (sim) Some(DeqIO(new MemoryRequest)) else None
    val dCacheResp = if (sim) Some(Valid(UInt(32.W))) else None

    val iCacheReq = if (sim) Some(DeqIO(new MemoryRequest)) else None
    val iCacheResp = if (sim) Some(Valid(UInt(32.W))) else None

    val regDebugIdx = if (sim) Some(Input(UInt(5.W))) else None
    val regDebugData = if (sim) Some(Output(UInt(32.W))) else None
  })

  val core = Module(new Core(sim = sim))

  // iCache and dCache are very weird addressing-wise for right now. They are two entirely separate
  // memory regions, but that have the same virtual addresses. The program counter and instruction
  // fetch will be accessing iCache mem, while any loads and stores will be accessing dCache mem.
  // Test cases, for now, should initialize iCache and dCache memory to the same contents, but use
  // the lower half of each space (0x0-0x1FF) for instructions, use the upper half (0x200-0x3FF) for
  // data, and be careful to write tests that honor this.
  val iCache = Module(new BadCache(memInitFile, sim = sim, responseLatency = simCacheLatency))
  val dCache = Module(new BadCache(memInitFile, sim = sim, responseLatency = simCacheLatency))

  core.reset := reset
  core.io.halt := io.halt
  iCache.reset := reset
  dCache.reset := reset

  // iCache and dCache <-> core connections
  iCache.io.req.bits := core.io.codeReq.bits
  iCache.io.req.valid := core.io.codeReq.valid
  core.io.codeReq.ready := iCache.io.req.ready
  core.io.codeResp := iCache.io.resp

  dCache.io.req.bits := core.io.dataReq.bits
  dCache.io.req.valid := core.io.dataReq.valid
  core.io.dataReq.ready := dCache.io.req.ready
  core.io.dataResp := dCache.io.resp

  // sim/debug connections - registers, iCache, dCache
  if (sim) {
    iCache.io.simReq.get.bits := io.iCacheReq.get.bits
    iCache.io.simReq.get.valid := io.iCacheReq.get.valid
    io.iCacheReq.get.ready := iCache.io.simReq.get.ready
    io.iCacheResp.get := iCache.io.simResp.get

    dCache.io.simReq.get.bits := io.dCacheReq.get.bits
    dCache.io.simReq.get.valid := io.dCacheReq.get.valid
    io.dCacheReq.get.ready := dCache.io.simReq.get.ready
    io.dCacheResp.get := dCache.io.simResp.get

    core.io.regDebugIdx.get := io.regDebugIdx.get
    io.regDebugData.get := core.io.regDebugData.get
  }
}
