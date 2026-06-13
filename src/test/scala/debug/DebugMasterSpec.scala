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
case class DoWrite(address: BigInt, data: BigInt)
case class DoRead(address: BigInt)

case class SuccessfulRead(data: BigInt)
case class SuccessfulWrite()
case class FailedRead()
case class FailedWrite()

// Drivers & Monitors
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

class UartTxDriver(clocksPerBaud: Int, tx: Bool) extends PeekPokeAPI {
  var transactionSeq: Seq[Boolean] = Seq.empty

  private def addByte(byte: Byte): Unit = {
    // Start bit
    transactionSeq = transactionSeq ++ Seq.fill(clocksPerBaud)(false)

    // Data bits (LSB first)
    for (i <- 0 until 8) {
      val bit = ((byte >> i) & 1) == 1
      transactionSeq = transactionSeq ++ Seq.fill(clocksPerBaud)(bit)
    }

    // Stop bit
    transactionSeq = transactionSeq ++ Seq.fill(clocksPerBaud)(true)
  }

  def encodeWrite(write: DoWrite): Unit = {
    val bytes = Seq(
      0b10011111.toByte,
      (write.address & 0xff).toByte,
      ((write.address >> 8) & 0xff).toByte,
      ((write.address >> 16) & 0xff).toByte,
      ((write.address >> 24) & 0xff).toByte,
      (write.data & 0xff).toByte,
      ((write.data >> 8) & 0xff).toByte,
      ((write.data >> 16) & 0xff).toByte,
      ((write.data >> 24) & 0xff).toByte
    )

    for (byte <- bytes) {
      addByte(byte)
    }
  }

  def encodeRead(read: DoRead): Unit = {
    val bytes = Seq(
      0b10011110.toByte,
      (read.address & 0xff).toByte,
      ((read.address >> 8) & 0xff).toByte,
      ((read.address >> 16) & 0xff).toByte,
      ((read.address >> 24) & 0xff).toByte
    )

    for (byte <- bytes) {
      addByte(byte)
    }
  }

  // Returns true if no transaction is currently being transmitted
  def tick(): Boolean = {
    if (transactionSeq.isEmpty) {
      tx.poke(true.B)
      true
    } else {
      tx.poke(transactionSeq.head)
      transactionSeq = transactionSeq.tail
      false
    }
  }
}

class UartRxMonitor(clocksPerBaud: Int, rx: Bool) extends PeekPokeAPI {
  var bytes: Seq[Int] = Seq.empty
  var receiving: Boolean = false
  var countdown: Int = 0
  var bitIndex: Int = 0
  var current: Int = 0
  var waitForHigh: Boolean = false

  def tick(): Unit = {
    val rxBool = rx.peekBoolean()

    if (waitForHigh && rxBool) {
      waitForHigh = false
      return
    } else if (waitForHigh && !rxBool) {
      return
    }

    if (!receiving && !rxBool) {
      receiving = true
      current = 0
      bitIndex = 0
      countdown = clocksPerBaud + clocksPerBaud / 2
    } else {
      countdown -= 1
      if (countdown == 0) {
        if (rxBool) current |= (1 << bitIndex)
        if (bitIndex == 7) {
          bytes = bytes :+ current
          waitForHigh = true
          receiving = false
        } else {
          bitIndex += 1
          countdown = clocksPerBaud
        }
      }
    }
  }
}

class DebugMasterSpec extends AnyFlatSpec with Matchers with ChiselSim {
  private val ClocksPerBaud = 6

  behavior of "DebugMaster"

  it should "work" in {
    simulate(new DebugMaster(ClocksPerBaud, emitFormal = false)) { dut =>
      val bus = new BusModel(dut.io.req, dut.io.resp)
      val uartTx = new UartTxDriver(ClocksPerBaud, dut.io.uartRx)
      val uartRx = new UartRxMonitor(ClocksPerBaud, dut.io.uartTx)

      uartTx.encodeRead(DoRead(4))
      for (i <- 0 until 600) {
        uartTx.tick()
        uartRx.tick()
        bus.tick()
        dut.clock.step()
      }

      println(s"uartRx out: ${uartRx.bytes}")
    }
  }

}
