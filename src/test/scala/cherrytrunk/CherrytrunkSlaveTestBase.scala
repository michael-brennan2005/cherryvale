package cherrytrunk

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.TestSuite
import org.scalatest.matchers.should.Matchers

/** Lightweight helpers for testing cherrytrunk slave devices -- the bus analog of
  * `cpu.isa.CpuTestBase`. No golden model or compliance battery: just spin up the
  * DUT with a [[BusMaster]] attached and write plain `poke`/`read`/`write`/`expect`
  * assertions per test.
  */
trait CherrytrunkSlaveTestBase extends Matchers with ChiselSim { self: TestSuite =>

  /** Simulate `dut`, attach a [[BusMaster]] to the bus port returned by `bus`,
    * settle reset, then hand the DUT and master to `body`.
    */
  protected def withMaster[T <: Module](
      dut: => T,
      bus: T => (Request, Response)
  )(body: (T, BusMaster) => Unit): Unit =
    simulate(dut) { d =>
      val (req, resp) = bus(d)
      val m = new BusMaster(req, resp, d.clock)
      m.idle()
      d.clock.step(2) // settle out of reset before driving
      body(d, m)
    }
}
