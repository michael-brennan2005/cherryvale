package cpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MemorySpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Memory"

  // One read-only port + one read/write port, matching the original two-port layout.
  def dut() = new Memory(readOnlyPorts = 1)

  it should "read back a word written at addr 0 via the rw port" in {
    simulate(dut()) { dut =>
      dut.io.rw.addr.poke(0.U)
      dut.io.rw.w_data.poke("hDEADBEEF".U)
      dut.io.rw.w_en.poke(true.B)
      dut.clock.step()

      dut.io.rw.w_en.poke(false.B)
      dut.io.rw.addr.poke(0.U)
      dut.clock.step()
      dut.io.rw.data.expect("hDEADBEEF".U)
    }
  }

  it should "store independent words at different addresses via the rw port" in {
    simulate(dut()) { dut =>
      dut.io.rw.addr.poke(4.U)
      dut.io.rw.w_data.poke("hAAAAAAAA".U)
      dut.io.rw.w_en.poke(true.B)
      dut.clock.step()

      dut.io.rw.addr.poke(8.U)
      dut.io.rw.w_data.poke("h55555555".U)
      dut.io.rw.w_en.poke(true.B)
      dut.clock.step()

      dut.io.rw.w_en.poke(false.B)

      dut.io.rw.addr.poke(4.U)
      dut.clock.step()
      dut.io.rw.data.expect("hAAAAAAAA".U)

      dut.io.rw.addr.poke(8.U)
      dut.clock.step()
      dut.io.rw.data.expect("h55555555".U)
    }
  }

  it should "not mutate memory when write-enable is low" in {
    simulate(dut()) { dut =>
      dut.io.rw.addr.poke(12.U)
      dut.io.rw.w_data.poke("h12345678".U)
      dut.io.rw.w_en.poke(true.B)
      dut.clock.step()

      dut.io.rw.w_data.poke("hFFFFFFFF".U)
      dut.io.rw.w_en.poke(false.B)
      dut.clock.step()

      dut.io.rw.addr.poke(12.U)
      dut.clock.step()
      dut.io.rw.data.expect("h12345678".U)
    }
  }

  it should "read back data on the read port that was written via the rw port" in {
    simulate(dut()) { dut =>
      dut.io.rw.addr.poke(16.U)
      dut.io.rw.w_data.poke("hCAFEBABE".U)
      dut.io.rw.w_en.poke(true.B)
      dut.clock.step()

      dut.io.rw.w_en.poke(false.B)
      dut.io.ro(0).addr.poke(16.U)
      dut.clock.step()
      dut.io.ro(0).data.expect("hCAFEBABE".U)
    }
  }

  it should "read different addresses concurrently on both ports" in {
    simulate(dut()) { dut =>
      // Seed two distinct words via the rw port
      dut.io.rw.addr.poke(32.U)
      dut.io.rw.w_data.poke("h11111111".U)
      dut.io.rw.w_en.poke(true.B)
      dut.clock.step()

      dut.io.rw.addr.poke(64.U)
      dut.io.rw.w_data.poke("h22222222".U)
      dut.io.rw.w_en.poke(true.B)
      dut.clock.step()

      dut.io.rw.w_en.poke(false.B)

      // Read both simultaneously, one per port
      dut.io.ro(0).addr.poke(32.U)
      dut.io.rw.addr.poke(64.U)
      dut.clock.step()
      dut.io.ro(0).data.expect("h11111111".U)
      dut.io.rw.data.expect("h22222222".U)

      // And swap which port reads which address
      dut.io.ro(0).addr.poke(64.U)
      dut.io.rw.addr.poke(32.U)
      dut.clock.step()
      dut.io.ro(0).data.expect("h22222222".U)
      dut.io.rw.data.expect("h11111111".U)
    }
  }

  it should "leave the read port read-only (writes through rw only)" in {
    simulate(dut()) { dut =>
      // Write via the rw port at addr 20
      dut.io.rw.addr.poke(20.U)
      dut.io.rw.w_data.poke("hABCDEF01".U)
      dut.io.rw.w_en.poke(true.B)
      dut.clock.step()

      // Drive both read paths to addr 20; rw stops writing
      dut.io.rw.w_en.poke(false.B)
      dut.io.ro(0).addr.poke(20.U)
      dut.io.rw.addr.poke(20.U)
      dut.clock.step()

      // Both ports should observe the value written via rw
      dut.io.ro(0).data.expect("hABCDEF01".U)
      dut.io.rw.data.expect("hABCDEF01".U)
    }
  }

  // ---- MMIO ------------------------------------------------------------------

  it should "expose switches at addr 0x400 on the read port" in {
    simulate(dut()) { dut =>
      dut.io.sw.poke("hABCD".U)
      dut.io.ro(0).addr.poke(0x400.U)
      dut.clock.step()
      dut.io.ro(0).data.expect("h0000ABCD".U) // zero-extended from 16 bits
    }
  }

  it should "expose switches at addr 0x400 on the rw port" in {
    simulate(dut()) { dut =>
      dut.io.sw.poke("h1234".U)
      dut.io.rw.w_en.poke(false.B)
      dut.io.rw.addr.poke(0x400.U)
      dut.clock.step()
      dut.io.rw.data.expect("h00001234".U)
    }
  }

  it should "track live changes to the switches at addr 0x400" in {
    simulate(dut()) { dut =>
      dut.io.rw.addr.poke(0x400.U)
      dut.io.rw.w_en.poke(false.B)

      dut.io.sw.poke("hAAAA".U)
      dut.clock.step()
      dut.io.rw.data.expect("h0000AAAA".U)

      dut.io.sw.poke("h5555".U)
      dut.clock.step()
      dut.io.rw.data.expect("h00005555".U)
    }
  }

  it should "latch LED state when writing to addr 0x401" in {
    simulate(dut()) { dut =>
      dut.io.rw.addr.poke(0x404.U)
      dut.io.rw.w_data.poke("hDEADBEEF".U) // only low 16 bits should latch
      dut.io.rw.w_en.poke(true.B)
      dut.clock.step()

      dut.io.rw.w_en.poke(false.B)
      dut.io.led.expect("hBEEF".U)
    }
  }

  it should "hold the LED state across cycles when no write occurs" in {
    simulate(dut()) { dut =>
      // Latch a known value.
      dut.io.rw.addr.poke(0x404.U)
      dut.io.rw.w_data.poke("h0000A5A5".U)
      dut.io.rw.w_en.poke(true.B)
      dut.clock.step()

      // Stop writing; LED should not change.
      dut.io.rw.w_en.poke(false.B)
      dut.io.rw.addr
        .poke(0.U) // poke elsewhere to make sure addr isn't sticky
      dut.io.rw.w_data.poke("hFFFFFFFF".U)
      dut.clock.step(5)
      dut.io.led.expect("hA5A5".U)
    }
  }

  it should "overwrite the LED state on subsequent writes to 0x404" in {
    simulate(dut()) { dut =>
      dut.io.rw.addr.poke(0x404.U)
      dut.io.rw.w_en.poke(true.B)

      dut.io.rw.w_data.poke("h00001111".U)
      dut.clock.step()
      dut.io.rw.w_en.poke(false.B)
      dut.io.led.expect("h1111".U)

      dut.io.rw.w_en.poke(true.B)
      dut.io.rw.w_data.poke("h00002222".U)
      dut.clock.step()
      dut.io.rw.w_en.poke(false.B)
      dut.io.led.expect("h2222".U)
    }
  }

  it should "not touch the LED state when writing to non-MMIO addresses" in {
    simulate(dut()) { dut =>
      // Seed the LED.
      dut.io.rw.addr.poke(0x404.U)
      dut.io.rw.w_data.poke("h0000CAFE".U)
      dut.io.rw.w_en.poke(true.B)
      dut.clock.step()

      // Write to plain memory; LED should be unchanged.
      dut.io.rw.addr.poke(0.U)
      dut.io.rw.w_data.poke("hFFFFFFFF".U)
      dut.io.rw.w_en.poke(true.B)
      dut.clock.step()

      dut.io.rw.w_en.poke(false.B)
      dut.io.led.expect("hCAFE".U)

      // And the byte at addr 0 should reflect the plain-memory write.
      dut.io.rw.addr.poke(0.U)
      dut.clock.step()
      dut.io.rw.data.expect("hFFFFFFFF".U)
    }
  }
}
