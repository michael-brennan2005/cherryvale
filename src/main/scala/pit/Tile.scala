package pit

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util._
import harness.Elaboratable

// CherryPit core with all the fix-ins: instruction and data cache, exposed ports for testing/sim,
// and (TODO) cpu control unit for halting, resetting, register and CSR inspection, etc.
class Tile(memInitFile: Option[String], exposeSimPorts: Boolean = false, simCacheLatency: Int = 0)
    extends Module {
  val io = IO(new Bundle {
    val halt = Input(Bool())
    val clear = Input(Bool())

    // Ports for simulation
    val dCacheReq = if (exposeSimPorts) Some(DeqIO(new MemoryRequest)) else None
    val dCacheResp = if (exposeSimPorts) Some(EnqIO(UInt(32.W))) else None

    val iCacheReq = if (exposeSimPorts) Some(DeqIO(new MemoryRequest)) else None
    val iCacheResp = if (exposeSimPorts) Some(EnqIO(UInt(32.W))) else None

    val regSimIdx = if (exposeSimPorts) Some(Input(UInt(5.W))) else None
    val regSimData = if (exposeSimPorts) Some(Output(UInt(32.W))) else None
  })

  val core = Module(new Core(exposeSimPorts = exposeSimPorts))

  // iCache and dCache are very weird addressing-wise for right now. They are two entirely separate
  // memory regions, but that have the same virtual addresses. The program counter and instruction
  // fetch will be accessing iCache mem, while any loads and stores will be accessing dCache mem.
  // Test cases, for now, should initialize iCache and dCache memory to the same contents, but use
  // the lower half of each space (0x0-0x1FF) for instructions, use the upper half (0x200-0x3FF) for
  // data, and be careful to write tests that honor this.
  val iCache = Module(
    new BadCache(memInitFile, sim = exposeSimPorts, responseLatency = simCacheLatency)
  )
  val dCache = Module(
    new BadCache(memInitFile, sim = exposeSimPorts, responseLatency = simCacheLatency)
  )

  core.io.halt := io.halt
  core.io.clear := io.clear

  // iCache and dCache <-> core connections
  iCache.io.req.bits := core.io.codeReq.bits
  iCache.io.req.valid := core.io.codeReq.valid
  core.io.codeReq.ready := iCache.io.req.ready

  core.io.codeResp.bits := iCache.io.resp.bits
  core.io.codeResp.valid := iCache.io.resp.valid
  iCache.io.resp.ready := core.io.codeResp.ready

  dCache.io.req.bits := core.io.dataReq.bits
  dCache.io.req.valid := core.io.dataReq.valid
  core.io.dataReq.ready := dCache.io.req.ready

  core.io.dataResp.bits := dCache.io.resp.bits
  core.io.dataResp.valid := dCache.io.resp.valid
  dCache.io.resp.ready := core.io.dataResp.ready

  // sim/debug connections - registers, iCache, dCache
  if (exposeSimPorts) {
    iCache.io.simReq.get.bits := io.iCacheReq.get.bits
    iCache.io.simReq.get.valid := io.iCacheReq.get.valid
    io.iCacheReq.get.ready := iCache.io.simReq.get.ready
    io.iCacheResp.get.bits := iCache.io.simResp.get.bits
    io.iCacheResp.get.valid := iCache.io.simResp.get.valid

    dCache.io.simReq.get.bits := io.dCacheReq.get.bits
    dCache.io.simReq.get.valid := io.dCacheReq.get.valid
    io.dCacheReq.get.ready := dCache.io.simReq.get.ready
    io.dCacheResp.get.bits := dCache.io.simResp.get.bits
    io.dCacheResp.get.valid := dCache.io.simResp.get.valid

    core.io.regSimIdx.get := io.regSimIdx.get
    io.regSimData.get := core.io.regSimData.get
  }
}

object EmitTile extends Elaboratable {
  def build: RawModule = new Tile(None, true, 5)
}
