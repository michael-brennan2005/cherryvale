package debug

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chisel3.simulator.PeekPokeAPI
import cherrytrunk.Request
import cherrytrunk.Response
import com.carlosedp.riscvassembler.ObjectUtils.NumericManipulation

// Transactions
// TODO: These are fine for now but once we figure out a more reusable bus model it may be smart
// to move these.
case class DoWrite(address: BigInt, data: BigInt)
case class DoRead(address: BigInt)

case class SuccessfulRead(data: BigInt)
case class SuccessfulWrite()
case class FailedRead()
case class FailedWrite()

// Drivers & Monitors
// TODO: can this be made more reusable? If you provide constructor methods for memreads, writes, and init
// probably; may just make it a trait atp.
class BusModel(req: Request, resp: Response) extends PeekPokeAPI {
  val memorySize = 30
  var memory: Seq[BigInt] = Seq.tabulate(memorySize)(n => n * 10)

  var state = 0 // 0 -> Waiting for request, 1 -> Sending response

  def tick(): Unit = {
    if (state == 0) {
      val doTransaction = req.stb.peekBoolean() && req.cyc.peekBoolean()
      val isWrite = req.we.peekBoolean()
      val addr = (req.addr.peek().litValue >> 2)
      val addrValid = (addr) < memorySize

      if (!doTransaction) {
        resp.data.poke(0.U)
        resp.err.poke(false.B)
        resp.ack.poke(false.B)
        return
      }

      if (!addrValid) {
        resp.data.poke(0.U)
        resp.err.poke(true.B)
        resp.ack.poke(true.B)
        state = 1
        return
      }

      if (isWrite) {
        memory = memory.updated(addr.toInt, req.data.peek().litValue)
        resp.data.poke(0.U)
        resp.ack.poke(true.B)
        resp.err.poke(false.B)
      } else {
        resp.data.poke(memory(addr.toInt))
        resp.ack.poke(true.B)
        resp.err.poke(false.B)
      }

      state = 1
      return
    } else if (state == 1) {
      resp.data.poke(0.U)
      resp.err.poke(false.B)
      resp.ack.poke(false.B)

      state = 0
    }
  }
}

class DebugMasterSpec extends AnyFlatSpec with Matchers with ChiselSim {
  private val ClocksPerBaud = 6

  behavior of "DebugMaster"

  // TODO: DebugMaster works, models & transactions works, but we should put this into
  // a proper test setup w/ harness
  it should "work" in {
    simulate(new DebugMaster(ClocksPerBaud, emitFormal = false)) { dut =>
      val bus = new BusModel(dut.io.req, dut.io.resp)
      val uartTx = new UartTxDriver(ClocksPerBaud, dut.io.uartRx)
      val uartRx = new UartRxMonitor(ClocksPerBaud, dut.io.uartTx)

      uartTx.encodeRead(DoRead(4))
      uartTx.encodeWrite(DoWrite(4, 55))
      uartTx.encodeRead(DoRead(4))
      uartTx.encodeWrite(DoWrite(300, 5500))
      for (i <- 0 until 1800) {
        uartTx.tick()
        uartRx.tick()
        bus.tick()
        dut.clock.step()
      }

      println(s"uartRx out: ${uartRx.bytes}")
    }
  }

}
