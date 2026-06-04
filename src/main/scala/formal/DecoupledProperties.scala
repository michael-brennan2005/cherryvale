package formal

import chisel3._
import chisel3.util._
import chisel3.layers.Verification
import formal.Utils._

/** Formal properties for devices exposing [[chisel3.util.Decoupled]] ports. Based off of:
  * https://cjdrake.substack.com/p/readyvalid-protocol-primer
  *
  * The properties are lowered to `RegNext`-based immediate assertions/covers (via [[Utils]]) rather
  * than concurrent SVA, so the open-source Yosys / SymbiYosys flow can check them without the
  * commercial Verific frontend.
  */
// TODO: this is broken and using the bad assume operators
object DecoupledProperties {
  def emitTx[T <: Data](decoupled: DecoupledIO[T]): Unit = {
    layer.block(Verification) {
      // Ready must stay asserted while waiting:  ready && !valid |=> ready
      nextAssume(
        decoupled.ready && !decoupled.valid,
        decoupled.ready,
        "ready must remain stable until valid"
      )

      // Valid and data must stay stable until ready:  !ready && valid |=> valid && $stable(bits)
      nextImpl(
        !decoupled.ready && decoupled.valid,
        decoupled.valid && stable(decoupled.bits),
        "valid/data must remain stable until ready"
      )

      // Cover a completed data transfer.
      chisel3.cover(decoupled.ready && decoupled.valid)

      // Cover back-pressure (valid waiting on ready).
      chisel3.cover(!decoupled.ready && decoupled.valid)
    }
  }

  /** Receiver (flipped / consumer) counterpart to [[emitTx]].
    *
    * Pass the device's *flipped* `Decoupled` sink port (the one declared
    * `Flipped(Decoupled(...))`). A flipped port is still a [[DecoupledIO]] in Scala -- flipping
    * only reverses signal direction -- so the type is unchanged. Here the device drives `ready` and
    * observes `valid`/`bits`, which is the mirror image of [[emitTx]]: the device's `ready`
    * behavior becomes an assertion, and the upstream producer's `valid`/`bits` behavior becomes an
    * assumption.
    */
  def emitRx[T <: Data](decoupled: DecoupledIO[T]): Unit = {
    layer.block(Verification) {
      // The receiver must keep ready asserted while waiting:  ready && !valid |=> ready
      nextImpl(
        decoupled.ready && !decoupled.valid,
        decoupled.ready,
        "ready must remain stable until valid"
      )

      // Assume a well-behaved producer holds valid/data until accepted:
      //   !ready && valid |=> valid && $stable(bits)
      nextAssume(
        !decoupled.ready && decoupled.valid,
        decoupled.valid && stable(decoupled.bits),
        "valid/data must remain stable until ready"
      )

      // Cover a completed data transfer.
      chisel3.cover(decoupled.ready && decoupled.valid)

      // Cover back-pressure (valid waiting on ready).
      chisel3.cover(!decoupled.ready && decoupled.valid)
    }
  }
}
