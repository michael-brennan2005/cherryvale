package cherrytrunk

import chisel3._
import chisel3.simulator.PeekPokeAPI

/** Bus Functional Model: drives a single cherrytrunk master onto a slave's `Request`/`Response`
  * port pair.
  *
  * The driver is deliberately latency-agnostic. It pulses `stb` for exactly one cycle (per the
  * protocol), holds the rest of the request valid until `ack`, and then waits for `ack` by polling
  * rather than hand-counting cycles -- so it works whether the slave answers combinationally or
  * after any number of pipeline stages. A `maxCycles` budget turns a missing `ack` (a liveness
  * failure) into a test failure instead of a hang.
  *
  * Must be constructed and used inside a `simulate(...)` block (the peek/poke extension methods
  * resolve against the running simulation).
  */
class BusMaster(req: Request, resp: Response, clk: Clock, maxCycles: Int = 64) extends PeekPokeAPI {
  private val mask32 = (BigInt(1) << 32) - 1

  /** Hold the bus idle: deassert the strobe and zero the request fields. */
  def idle(): Unit = {
    req.stb.poke(false.B)
    req.we.poke(false.B)
    req.addr.poke(0.U)
    req.data.poke(0.U)
    req.mask.poke(0.U)
  }

  /** Issue one transaction and return `(data, err)` captured while `ack` is high. `data` is
    * meaningful only for reads; `err` is the slave's error flag.
    */
  private def transact(addr: BigInt, rw: Boolean, data: BigInt, mask: Int): (BigInt, Boolean) = {
    // Present the request and strobe for one cycle.
    req.addr.poke((addr & mask32).U)
    req.data.poke((data & mask32).U)
    req.mask.poke(mask.U)
    req.we.poke(rw.B)
    req.stb.poke(true.B)
    clk.step() // rising edge samples the strobe

    // Strobe is a one-cycle pulse; the remaining fields stay valid until ack falls.
    req.stb.poke(false.B)

    // Wait for ack (it may already be asserted for a single-cycle-latency slave).
    var cycles = 0
    while (resp.ack.peek().litValue == 0 && cycles < maxCycles) {
      clk.step()
      cycles += 1
    }
    assert(
      resp.ack.peek().litValue == 1,
      s"cherrytrunk transaction to 0x${addr.toString(16)} timed out: no ack within $maxCycles cycles"
    )

    val rdata = resp.data.peek().litValue
    val rerr = resp.err.peek().litValue == 1

    clk.step() // advance past the one-cycle ack
    idle()
    (rdata, rerr)
  }

  /** Read `addr` with byte-enable `mask`. Returns `(data, err)`. */
  def read(addr: BigInt, mask: Int = 0xf): (BigInt, Boolean) =
    transact(addr, rw = false, data = 0, mask = mask)

  /** Write `data` to `addr` with byte-enable `mask`. Returns `err`. */
  def write(addr: BigInt, data: BigInt, mask: Int = 0xf): Boolean =
    transact(addr, rw = true, data = data, mask = mask)._2
}
