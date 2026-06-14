package cherryvale

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

// Reuse the UART drivers/monitors and transaction case classes from the debug package.
import debug.{UartTxDriver, UartRxMonitor, DoRead, DoWrite}

// Smoke test / waveform-inspection harness for the full Soc1 (DebugMaster -> Crossbar -> BasysIo).
//
// NOTE: BasysIO peripherals live behind the crossbar at addresses whose TOP NIBBLE is 0xF
// (see Crossbar.scala: basysSelect = addr(31,28) === 0xF). So:
//   LED     -> 0xF0000000
//   switches -> 0xF0000004
//   buttons  -> 0xF0000008
// A read to a non-0xF address never routes to a slave and the master hangs in WaitForAck
// (i.e. "operation timed out").
class Soc1Spec extends AnyFlatSpec with Matchers with ChiselSim {
  private val ClocksPerBaud = 6

  private val LedAddr = BigInt("F0000000", 16)
  private val SwAddr = BigInt("F0000004", 16)
  private val BtnAddr = BigInt("F0000008", 16)

  behavior of "Soc1"

  it should "read switches/buttons over UART through the crossbar" in {
    simulate(new Soc1(ClocksPerBaud, emitFormal = false)) { dut =>
      val uartTx = new UartTxDriver(ClocksPerBaud, dut.io.uartRx)
      val uartRx = new UartRxMonitor(ClocksPerBaud, dut.io.uartTx)

      // Drive the board inputs. RegNext inside BasysIo samples these, so set them
      // before the transactions arrive.
      dut.io.sw.poke(0x1234.U)
      dut.io.btn.poke(0xa.U)

      // Read switches and buttons. (LED read returns its reset value of 7.)
      uartTx.encodeRead(DoRead(LedAddr))
      uartTx.encodeRead(DoRead(SwAddr))
      uartTx.encodeRead(DoRead(BtnAddr))

      // Write the LEDs then read them back. NOTE: this currently exercises the
      // LED-write decode bug in BasysIo.scala (full-address compare vs addr(3,0)) --
      // the read-back will come back as 7 (reset value) until that is fixed.
      uartTx.encodeWrite(DoWrite(LedAddr, 0xbeef))
      uartTx.encodeRead(DoRead(LedAddr))

      for (_ <- 0 until 4000) {
        uartTx.tick()
        uartRx.tick()
        dut.clock.step()
      }

      // Each read returns 1 status byte + 4 data bytes (LSB first); each write returns
      // 1 status byte. Decode the data words for easy inspection.
      println(s"raw uartRx bytes: ${uartRx.bytes.map(b => f"0x$b%02x")}")
    }
  }
}
