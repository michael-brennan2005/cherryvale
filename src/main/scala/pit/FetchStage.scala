package pit

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import trunk.Request
import trunk.Response

class FetchStageOutput extends Bundle {
  val inst = Output(UInt(32.W))
  val pc = Output(UInt(32.W))
  val pcPlusFour = Output(UInt(32.W))
}

class FetchStage extends Module {
  val io = IO(new Bundle {
    // PC -> Fetch -> Decode
    val in = DeqIO(UInt(32.W))
    val out = Valid(new FetchStageOutput)

    // Connections to I$ (instantiated in Tile)
    val codeReq = Output(new Request)
    val codeResp = Input(new Response)

    // Stall signal from HazardUnit
    val stall = Input(Bool())
    val flush = Input(Bool())
  })

  // sReady - ready to accept a new memory request
  // sInProgress - cherrytrunk transaction taking place
  // sDone - cherry trunk action done
  val sReady :: sInProgress :: Nil = Enum(2)

  val state = RegInit(sReady)
  val addr = RegInit(0.U(32.W))
  val inst = RegInit(0.U(32.W))
  val valid = RegInit(false.B)

  // Default outputs
  io.in.ready := !io.stall
  io.out.bits := 0.U.asTypeOf(new FetchStageOutput)
  io.out.valid := false.B

  io.codeReq.cyc := false.B
  io.codeReq.stb := false.B
  io.codeReq.data := 0.U
  io.codeReq.mask := 0.U
  io.codeReq.we := false.B

  switch(state) {
    is(sReady) {
      when(io.in.fire) {
        // fire stb on same cycle io.in fires
        io.codeReq.cyc := true.B
        io.codeReq.stb := true.B
        io.codeReq.addr := io.in.bits
        // Save addr for when we're in progress - defend against case of input changing while
        // we're in the middle of a transaction
        addr := io.in.bits
        state := sInProgress
      }
    }
    is(sInProgress) {
      io.codeReq.cyc := true.B
      io.codeReq.addr := addr

      when(io.codeResp.ack && !io.codeResp.err) {
        io.out.valid := true.B
        io.out.bits.inst := io.codeResp.data
        io.out.bits.pc := addr
        io.out.bits.pcPlusFour := addr + 4.U

        // Save respData in case of a stall - we'll continue to output the same data
        inst := io.codeResp.data
        valid := true.B
      }
    }
  }

  when(io.stall) {
    io.out.bits.inst := inst
    io.out.bits.pc := addr
    io.out.bits.pcPlusFour := addr + 4.U
    io.out.valid := true.B
  }
}
