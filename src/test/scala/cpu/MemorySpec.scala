package cpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MemorySpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Memory"

  // One read-only port + one read/write port, matching the original two-port layout.
  def dut() = new Memory(byteSize = 256, readPorts = 1, readWritePorts = 1)

  it should "read back a word written at addr 0 via the rw port" in {
    simulate(dut()) { dut =>
      dut.io.rw(0).addr.poke(0.U)
      dut.io.rw(0).w_data.poke("hDEADBEEF".U)
      dut.io.rw(0).w_en.poke(true.B)
      dut.clock.step()

      dut.io.rw(0).w_en.poke(false.B)
      dut.io.rw(0).addr.poke(0.U)
      dut.clock.step()
      dut.io.rw(0).data.expect("hDEADBEEF".U)
    }
  }

  it should "store independent words at different addresses via the rw port" in {
    simulate(dut()) { dut =>
      dut.io.rw(0).addr.poke(4.U)
      dut.io.rw(0).w_data.poke("hAAAAAAAA".U)
      dut.io.rw(0).w_en.poke(true.B)
      dut.clock.step()

      dut.io.rw(0).addr.poke(8.U)
      dut.io.rw(0).w_data.poke("h55555555".U)
      dut.io.rw(0).w_en.poke(true.B)
      dut.clock.step()

      dut.io.rw(0).w_en.poke(false.B)

      dut.io.rw(0).addr.poke(4.U)
      dut.clock.step()
      dut.io.rw(0).data.expect("hAAAAAAAA".U)

      dut.io.rw(0).addr.poke(8.U)
      dut.clock.step()
      dut.io.rw(0).data.expect("h55555555".U)
    }
  }

  it should "not mutate memory when write-enable is low" in {
    simulate(dut()) { dut =>
      dut.io.rw(0).addr.poke(12.U)
      dut.io.rw(0).w_data.poke("h12345678".U)
      dut.io.rw(0).w_en.poke(true.B)
      dut.clock.step()

      dut.io.rw(0).w_data.poke("hFFFFFFFF".U)
      dut.io.rw(0).w_en.poke(false.B)
      dut.clock.step()

      dut.io.rw(0).addr.poke(12.U)
      dut.clock.step()
      dut.io.rw(0).data.expect("h12345678".U)
    }
  }

  it should "read back data on the read port that was written via the rw port" in {
    simulate(dut()) { dut =>
      dut.io.rw(0).addr.poke(16.U)
      dut.io.rw(0).w_data.poke("hCAFEBABE".U)
      dut.io.rw(0).w_en.poke(true.B)
      dut.clock.step()

      dut.io.rw(0).w_en.poke(false.B)
      dut.io.r(0).addr.poke(16.U)
      dut.clock.step()
      dut.io.r(0).data.expect("hCAFEBABE".U)
    }
  }

  it should "read different addresses concurrently on both ports" in {
    simulate(dut()) { dut =>
      // Seed two distinct words via the rw port
      dut.io.rw(0).addr.poke(32.U)
      dut.io.rw(0).w_data.poke("h11111111".U)
      dut.io.rw(0).w_en.poke(true.B)
      dut.clock.step()

      dut.io.rw(0).addr.poke(64.U)
      dut.io.rw(0).w_data.poke("h22222222".U)
      dut.io.rw(0).w_en.poke(true.B)
      dut.clock.step()

      dut.io.rw(0).w_en.poke(false.B)

      // Read both simultaneously, one per port
      dut.io.r(0).addr.poke(32.U)
      dut.io.rw(0).addr.poke(64.U)
      dut.clock.step()
      dut.io.r(0).data.expect("h11111111".U)
      dut.io.rw(0).data.expect("h22222222".U)

      // And swap which port reads which address
      dut.io.r(0).addr.poke(64.U)
      dut.io.rw(0).addr.poke(32.U)
      dut.clock.step()
      dut.io.r(0).data.expect("h22222222".U)
      dut.io.rw(0).data.expect("h11111111".U)
    }
  }

  it should "leave the read port read-only (writes through rw only)" in {
    simulate(dut()) { dut =>
      // Write via the rw port at addr 20
      dut.io.rw(0).addr.poke(20.U)
      dut.io.rw(0).w_data.poke("hABCDEF01".U)
      dut.io.rw(0).w_en.poke(true.B)
      dut.clock.step()

      // Drive both read paths to addr 20; rw stops writing
      dut.io.rw(0).w_en.poke(false.B)
      dut.io.r(0).addr.poke(20.U)
      dut.io.rw(0).addr.poke(20.U)
      dut.clock.step()

      // Both ports should observe the value written via rw
      dut.io.r(0).data.expect("hABCDEF01".U)
      dut.io.rw(0).data.expect("hABCDEF01".U)
    }
  }

  it should "store bytes in little-endian order" in {
    simulate(dut()) { dut =>
      // Write 0xDEADBEEF at addr 0: expect mem[0]=EF, mem[1]=BE, mem[2]=AD, mem[3]=DE
      dut.io.rw(0).addr.poke(0.U)
      dut.io.rw(0).w_data.poke("hDEADBEEF".U)
      dut.io.rw(0).w_en.poke(true.B)
      dut.clock.step()

      dut.io.rw(0).w_en.poke(false.B)

      // Read the word that starts one byte in: should be 0x??DEADBE,
      // where ?? is whatever byte lives at addr 4 (uninitialised → just check the
      // low three bytes match the shifted pattern).
      dut.io.rw(0).addr.poke(1.U)
      dut.clock.step()
      val rd = dut.io.rw(0).data.peek().litValue
      (rd & 0xffffffL) shouldBe 0xdeadbeL
    }
  }
}
