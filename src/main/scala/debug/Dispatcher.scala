package debug

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util._
import cherrytrunk.Request
import cherrytrunk.Response
import formal._
import formal.Utils.implies

// Decode bytes from UART line into cherrytrunk transactions, and encode those responses
// back into bytes to be transmitted.
//
// For requests, Control byte has following format:
// 0b100X_XXXY
// X: cherrytrunk write mask
// Y: 0 for reads, 1 for writes
//
// For responses, status byte has following format:
// 0b0000_00XY
// X: 0 for read response, 1 for write response
// Y: 0 on successful read/write, 1 on fail
//
// TODO: pipeline this - while waiting for transaction to complete we can be decoding
// the next operation; we can start a new transaction as we encode the last one
class Dispatcher(emitFormal: Boolean = false) extends Module {
  val io = IO(new Bundle {
    // Bytes coming from Uart RX
    val deq = Flipped(DecoupledIO(UInt(8.W)))

    // Bytes to Uart TX
    val enq = DecoupledIO(UInt(8.W))

    val req = Output(new Request)
    val resp = Input(new Response)
  })

  private object State extends ChiselEnum {
    val WaitForControl = Value(0.U)
    val GetAddrBytes = Value(1.U)
    val GetDataBytes = Value(2.U)
    val WaitForAck = Value(3.U)
    val SendStatusByte = Value(4.U)
    val SendDataBytes = Value(5.U)
  }

  private val state = RegInit(State.WaitForControl)
  val req = RegInit(0.U.asTypeOf(new Request))
  val resp = RegInit(0.U.asTypeOf(new Response))
  val counter = RegInit(0.U(4.W))

  io.deq.ready := state === State.WaitForControl || state === State.GetAddrBytes || state === State.GetDataBytes
  req.stb := false.B
  req.cyc := false.B

  io.enq.valid := false.B
  io.enq.bits := 0.U

  switch(state) {
    is(State.WaitForControl) {
      when(io.deq.fire && io.deq.bits(7, 5) === "b100".U) {
        state := State.GetAddrBytes
        req.mask := io.deq.bits(4, 1)
        req.we := io.deq.bits(0)
        counter := 3.U
      }
    }
    is(State.GetAddrBytes) {
      when(io.deq.fire) {
        req.addr := Cat(io.deq.bits, req.addr(31, 8))

        when(counter === 0.U) {
          when(!req.we) {
            // read
            state := State.WaitForAck
            req.stb := true.B
            req.cyc := true.B
          }.otherwise {
            // write
            state := State.GetDataBytes
            counter := 3.U
          }
        }.otherwise {
          counter := counter - 1.U
        }
      }
    }
    is(State.GetDataBytes) {
      when(io.deq.fire) {
        req.data := Cat(io.deq.bits, req.data(31, 8))

        when(counter === 0.U) {
          state := State.WaitForAck
          req.stb := true.B
          req.cyc := true.B
        }.otherwise {
          counter := counter - 1.U
        }
      }
    }
    is(State.WaitForAck) {
      when(io.resp.ack) {
        state := State.SendStatusByte
        req.cyc := false.B
        resp := io.resp
      }

      req.cyc := true.B
    }
    is(State.SendStatusByte) {
      io.enq.bits := Cat(req.we, Mux(resp.err, 1.U(1.W), 0.U(1.W)))
      io.enq.valid := true.B

      when(io.enq.fire) {
        state := Mux(req.we || resp.err, State.WaitForControl, State.SendDataBytes)
        counter := 3.U
      }
    }
    is(State.SendDataBytes) {
      io.enq.bits := resp.data(7, 0)
      io.enq.valid := true.B

      when(io.enq.fire) {
        resp.data := resp.data(31, 8)

        when(counter === 0.U) {
          state := State.WaitForControl
        }.otherwise {
          counter := counter - 1.U
        }
      }
    }
  }

  io.req := req

  if (emitFormal) {
    // Assume that the control bit always provides a valid write mask
    assume(
      Utils.implies(
        state === State.WaitForControl && io.deq.fire && io.deq.bits(7, 5) === "b100".U,
        CherrytrunkProperties.validCherrytrunkMask(io.deq.bits(4, 1))
      )
    )

    // Assume non-zero data on a response (so we can verify actual output to FIFO queue),
    // and that queue will be ready to accept data (so we don't just hang)
    assume(implies(io.resp.ack, io.resp.data =/= 0.U))
    assume(implies(state === State.SendStatusByte, io.enq.ready === true.B))
    assume(implies(state === State.SendDataBytes, io.enq.ready === true.B))

    // Covers - ensure we reach all states
    cover(state === State.WaitForControl)
    cover(state === State.GetAddrBytes)
    cover(state === State.GetDataBytes)
    cover(state === State.WaitForAck)
    cover(state === State.SendStatusByte)
    cover(state === State.SendDataBytes)

    // Ensure we complete a full encode/decode sequence
    val clockCounter = RegInit(0.U(16.W))
    clockCounter := clockCounter + 1.U
    val pastState = RegNext(state)
    cover(
      clockCounter > 5.U && pastState === State.SendDataBytes && state === State.WaitForControl
    )

    // Covers - ensure transaction actually launches and completes
    cover(io.req.stb)
    cover(resp.ack)

    CherrytrunkProperties.checkMaster(io.req, io.resp)
  }
}

object DispatcherFormal extends Formal {
  def build = new Dispatcher(emitFormal = true)

  override def checks: Seq[Check] = Seq(Bmc(20), Cover(60), Prove(60))
}
