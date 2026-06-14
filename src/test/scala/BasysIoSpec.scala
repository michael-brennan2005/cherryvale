import chisel3._
import org.scalatest.flatspec.AnyFlatSpec
import trunk.CherrytrunkSlaveTestBase
import common.BasysIo

/** Tests for BasysIO * */
class BasysIoSpec extends AnyFlatSpec with CherrytrunkSlaveTestBase {

  private def bus(d: BasysIo) = (d.io.req, d.io.resp)

  behavior of "BasysIo (cherrytrunk slave)"

  it should "read the switch and button registers" in {
    withMaster(new BasysIo, bus) { (d, m) =>
      d.io.sw.poke(0x1234.U)
      d.io.btn.poke(0xa.U)
      d.clock.step() // let RegNext sample the inputs

      val (sw, swErr) = m.read(0x4)
      sw shouldBe BigInt(0x1234)
      swErr shouldBe false

      m.read(0x8)._1 shouldBe BigInt(0xa)
    }
  }

  it should "write and read back the LED register" in {
    withMaster(new BasysIo, bus) { (_, m) =>
      m.write(0x0, 0xbeef) shouldBe false
      m.read(0x0)._1 shouldBe BigInt(0xbeef)
    }
  }

  it should "ack with no error on a basic read" in {
    withMaster(new BasysIo, bus) { (_, m) =>
      m.read(0x0)._2 shouldBe false
    }
  }
}
