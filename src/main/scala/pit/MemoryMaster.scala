package pit

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import trunk.Request
import trunk.Response

// A CherryTrunk master device for the Fetch and Execute stage of CherryPit. This module converts
// ready-valid, MemoryRequest type transactions into CherryTrunk transactions os that code and data
// fetch can access the system.
class MemoryMaster extends Module {
  val io = IO(new Bundle {
    // Core-side connect
    val coreSideReq = DeqIO(new MemoryRequest)
    val coreSideResp = EnqIO(UInt(32.W))

    // Bus-side connect
    val busSideReq = Output(new Request)
    val busSideResp = Input(new Response)
  })

  val busSideReq = RegInit(0.U.asTypeOf(new Request))
  val coreSideRespOutBits = RegInit(0.U(32.W))
  val coreSideRespOutValid = RegInit(false.B)

  io.busSideReq := busSideReq
  io.coreSideResp.bits := coreSideRespOutBits
  io.coreSideResp.valid := coreSideRespOutValid

  // Mem request -> Cherrytrunk request
  busSideReq.stb := false.B

  when(io.coreSideReq.fire) {
    busSideReq.addr := io.coreSideReq.bits.addr
    busSideReq.we := io.coreSideReq.bits.we
    busSideReq.data := io.coreSideReq.bits.writeData
    busSideReq.mask := io.coreSideReq.bits.writeMask
    busSideReq.stb := true.B
    busSideReq.cyc := true.B
  }

  // We can accept a new request if
  // - we are not in a pending cherrytrunk transaction
  // - we have a free slot to move a response into
  io.coreSideReq.ready := !busSideReq.cyc && !(!io.coreSideResp.ready && coreSideRespOutValid)

  // Response logic
  // TODO: eventually we need to accomodate erroring - for now assume we can't though and always
  // return data.
  when(io.busSideResp.ack) {
    coreSideRespOutBits := io.busSideResp.data
    coreSideRespOutValid := true.B
    busSideReq.cyc := false.B
  }

  when(io.coreSideResp.fire) {
    coreSideRespOutValid := false.B
  }
}
