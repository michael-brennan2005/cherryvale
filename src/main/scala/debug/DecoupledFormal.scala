package debug

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.ltl.{AssertProperty, AssumeProperty}
import chisel3.ltl.Sequence._
import chisel3.util._
import chisel3.layers.Verification
import chisel3.ltl.CoverProperty

/** Formal properties for devices exposing [[chisel3.util.Decoupled]] ports. Based off of:
  * https://cjdrake.substack.com/p/readyvalid-protocol-primer
  */
object DecoupledFormalProperties {
  def emitTx[T <: Data](decoupled: DecoupledIO[T]): Unit = {
    layer.block(Verification) {
      def stable(x: Data) = x === RegNext(x)

      // Ready must remain stable until Valid/Data
      val readyStable = (decoupled.ready && !decoupled.valid) |=> decoupled.ready

      // Valid and data must remain stable until ready
      val validDataStable =
        (!decoupled.ready && decoupled.valid) |=> (decoupled.valid && stable(decoupled.bits))

      AssumeProperty(readyStable)
      AssertProperty(validDataStable)

      // Cover data transfer
      CoverProperty(decoupled.ready && decoupled.valid)

      // Cover back pressure
      CoverProperty(!decoupled.ready && decoupled.valid)
    }
  }
}
