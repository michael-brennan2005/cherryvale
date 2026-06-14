package trunk

import chisel3._
import _root_.circt.stage.ChiselStage

// Cherrytrunk bus crossbar - the beating heart of the system!!!!
class Crossbar extends Module {
  val io = IO(new Bundle {
    val masterReq = Input(new Request)
    val masterResp = Output(new Response)

    val basysReq = Output(new Request)
    val basysResp = Input(new Response)
  })

  // Top 4 bits are used for address selection.
  // 0xF: BasysIO device
  val basysSelect = io.masterReq.addr(31, 28) === "hF".U

  // Defaults when slave or master is involved in transaction
  io.masterResp.ack := false.B
  io.masterResp.err := false.B
  io.masterResp.data := 0.U

  io.basysReq.addr := 0.U
  io.basysReq.data := 0.U
  io.basysReq.mask := 0.U
  io.basysReq.cyc := false.B
  io.basysReq.stb := false.B
  io.basysReq.we := false.B

  when(basysSelect && io.masterReq.cyc) {
    io.basysReq := io.masterReq
    io.masterResp := io.basysResp
  }
}
