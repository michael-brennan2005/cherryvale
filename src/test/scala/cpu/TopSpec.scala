import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.carlosedp.riscvassembler.RISCVAssembler

class TopSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Top"

  // We can't rely on $readmemh in this verilator setup because verilator's
  // auto-randomization of plain `reg` arrays kills the initial-block load.
  // So we instantiate Top with `exposeDebug = true`, hold the CPU in reset,
  // poke the program via the debug port, then release.
  //
  // memFile = "" disables loadMemoryFromFileInline so it doesn't fight us.

  /** Preload the program (a list of 32-bit words) at consecutive 4-byte addrs
    * via the debug port, then release reset.
    */
  private def loadAndStart(dut: Top, program: Seq[BigInt]): Unit = {
    dut.reset.poke(true.B)
    dut.io.debug.get.poke(true.B)
    dut.io.mem_debug.get.w_en.poke(false.B)
    dut.io.sw.poke(0.U)
    dut.clock.step()

    for ((inst, idx) <- program.zipWithIndex) {
      dut.io.mem_debug.get.addr.poke((idx * 4).U)
      dut.io.mem_debug.get.w_data.poke(inst.U)
      dut.io.mem_debug.get.w_en.poke(true.B)
      dut.clock.step()
    }

    dut.io.mem_debug.get.w_en.poke(false.B)
    dut.reset.poke(false.B)
    dut.io.debug.get.poke(false.B)
  }

  private def assemble(program: String): Seq[BigInt] =
    RISCVAssembler
      .fromString(program)
      .split('\n')
      .filter(_.nonEmpty)
      .map(line => BigInt(line.trim, 16))
      .toSeq

  it should "mirror switches to LEDs through full CPU execution" in {
    val program = assemble(
      """lw x1, 0x400(x0)
        |sw x1, 0x404(x0)
        |beq x0, x0, -8
        |""".stripMargin
    )

    simulate(new Top(memFile = "", exposeDebug = true)) { dut =>
      loadAndStart(dut, program)

      // Set switches, let the loop run a few iterations, check LED.
      dut.io.sw.poke("hABCD".U)
      dut.clock.step(10)
      dut.io.led.expect("hABCD".U)

      // Change switches; loop must still be running for LED to track.
      dut.io.sw.poke("h1234".U)
      dut.clock.step(10)
      dut.io.led.expect("h1234".U)

      dut.io.sw.poke("hFFFF".U)
      dut.clock.step(10)
      dut.io.led.expect("hFFFF".U)
    }
  }

  it should "keep looping across many switch changes (catches stuck BEQ)" in {
    val program = assemble(
      """lw x1, 0x400(x0)
        |sw x1, 0x404(x0)
        |beq x0, x0, -8
        |""".stripMargin
    )

    simulate(new Top(memFile = "", exposeDebug = true)) { dut =>
      loadAndStart(dut, program)

      // Walk through 5 distinct switch values, each separated by enough cycles
      // for the loop to execute. If BEQ stops taking, the LED freezes at the
      // first value and the later expects will fail.
      val values = Seq("h0000", "hAAAA", "h5555", "h0F0F", "hF0F0")
      for (v <- values) {
        dut.io.sw.poke(v.U)
        dut.clock.step(20)
        dut.io.led.expect(v.U)
      }
    }
  }
}
