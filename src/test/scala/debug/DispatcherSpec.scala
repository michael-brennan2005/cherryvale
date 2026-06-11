package debug

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DispatcherSpec extends AnyFlatSpec with Matchers with ChiselSim {
  private val ClocksPerBaud = 6

  behavior of "Dispatcher"

  // rw = true => write; rw = false => read
  def sendCommand(dut: Dispatcher, rw: Boolean, addr: Int, data: Option[Int]): Unit = {
    dut.io.deq.valid.poke(true.B)

    val control = if (rw) "b10011111".U else "b10011110".U
    val addrBytes = Seq(
      (addr & 0xff).toByte,
      ((addr >> 8) & 0xff).toByte,
      ((addr >> 16) & 0xff).toByte,
      ((addr >> 24) & 0xff).toByte
    )

    val dataBytes = data match {
      case Some(value) =>
        Seq(
          (value & 0xff).toByte,
          ((value >> 8) & 0xff).toByte,
          ((value >> 16) & 0xff).toByte,
          ((value >> 24) & 0xff).toByte
        )
      case None => Seq()
    }

    dut.io.deq.valid.poke(true.B)

    dut.io.deq.bits.poke(control)
    dut.clock.step(1)

    for (byte <- addrBytes) {
      dut.io.deq.bits.poke(byte)
      dut.clock.step(1)
    }

    for (byte <- dataBytes) {
      dut.io.deq.bits.poke(byte)
      dut.clock.step(1)
    }

    dut.io.deq.valid.poke(false.B)
  }

  // rw = true => write; rw = false => read
  // will set enq to ready
  def expectSequence(dut: Dispatcher, rw: Boolean, erred: Boolean, data: Option[Int]): Unit = {
    def bToI(b: Boolean) = if (b) 1 else 0

    val status = (bToI(rw) << 1) | bToI(erred)
    val bytes = data match {
      case Some(value) =>
        Seq(
          (value & 0xff).toByte,
          ((value >> 8) & 0xff).toByte,
          ((value >> 16) & 0xff).toByte,
          ((value >> 24) & 0xff).toByte
        )
      case None => Seq()
    }

    dut.io.enq.ready.poke(true.B)

    dut.io.enq.valid.expect(true.B)
    dut.io.enq.bits.expect(status.U)
    dut.clock.step(1)

    for (byte <- bytes) {
      dut.io.enq.valid.expect(true.B)
      dut.io.enq.bits.expect(byte)
      dut.clock.step(1)
    }

    dut.io.enq.ready.poke(false.B)
  }

  // TODO: check for write mask
  def expectCherrytrunkMessage(
      dut: Dispatcher,
      rw: Boolean,
      addr: Int,
      data: Option[Int]
  ): Unit = {
    dut.io.req.rw.expect(rw)
    // what are we doing here
    dut.io.req.addr.expect((addr.toLong & 0xffffffffL).U)

    data match {
      case Some(value) => dut.io.req.data.expect((value.toLong & 0xffffffffL).U)
      case None        => {}
    }
  }

  it should "complete a read transaction" in {
    simulate(new Dispatcher) { dut =>
      {
        sendCommand(dut, false, 0xaabbccdd, None)
        expectCherrytrunkMessage(dut, false, 0xaabbccdd, None)

        // Simulate response from bus
        dut.io.resp.ack.poke(true.B)
        dut.io.resp.err.poke(false.B)
        dut.io.resp.data.poke("h44332211".U)
        dut.clock.step(1)
        dut.io.resp.ack.poke(false.B)
        dut.io.resp.err.poke(false.B)
        dut.io.resp.data.poke("h0".U)

        // Expect proper response from dispatcher
        expectSequence(dut, false, false, Some(0x44332211))

        // no more bytes so line should sit idle
        dut.io.enq.valid.expect(false.B)
        dut.clock.step(10)
        dut.io.enq.valid.expect(false.B)
      }
    }
  }

  it should "complete a write transaction" in {
    simulate(new Dispatcher) { dut =>
      {
        sendCommand(dut, true, 0xaabbccdd, Some(0x44332211))
        expectCherrytrunkMessage(dut, true, 0xaabbccdd, Some(0x44332211))

        // Simulate response from bus
        dut.io.resp.ack.poke(true.B)
        dut.io.resp.err.poke(false.B)
        dut.clock.step(1)
        dut.io.resp.ack.poke(false.B)
        dut.io.resp.err.poke(false.B)

        // Expect proper response from dispatcher
        expectSequence(dut, true, false, None)

        // no more bytes so line should sit idle
        dut.io.enq.valid.expect(false.B)
        dut.clock.step(10)
        dut.io.enq.valid.expect(false.B)
      }
    }
  }

  it should "report an erred read transaction" in {
    simulate(new Dispatcher) { dut =>
      {
        sendCommand(dut, false, 0xaabbccdd, None)
        expectCherrytrunkMessage(dut, false, 0xaabbccdd, None)

        // Simulate response from bus
        dut.io.resp.ack.poke(true.B)
        dut.io.resp.err.poke(true.B)
        dut.clock.step(1)
        dut.io.resp.ack.poke(false.B)
        dut.io.resp.err.poke(false.B)

        // Expect proper response from dispatcher
        expectSequence(dut, false, true, None)

        // no more bytes so line should sit idle
        dut.io.enq.valid.expect(false.B)
        dut.clock.step(10)
        dut.io.enq.valid.expect(false.B)
      }
    }
  }

  it should "report an erred write transaction" in {
    simulate(new Dispatcher) { dut =>
      {
        sendCommand(dut, true, 0xaabbccdd, Some(0x11223344))
        expectCherrytrunkMessage(dut, true, 0xaabbccdd, Some(0x11223344))

        // Simulate response from bus
        dut.io.resp.ack.poke(true.B)
        dut.io.resp.err.poke(true.B)
        dut.clock.step(1)
        dut.io.resp.ack.poke(false.B)
        dut.io.resp.err.poke(false.B)

        // Expect proper response from dispatcher
        expectSequence(dut, true, true, None)

        // no more bytes so line should sit idle
        dut.io.enq.valid.expect(false.B)
        dut.clock.step(10)
        dut.io.enq.valid.expect(false.B)
      }
    }
  }
}
