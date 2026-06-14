package harness

import chisel3._
import chisel3.util._
import chisel3.layers.Verification
import Utils._

/** Formal properties for devices exposing [[chisel3.util.Decoupled]] ports. Based off of:
  * https://cjdrake.substack.com/p/readyvalid-protocol-primer
  */
object DecoupledProperties {
  def emitProducer[T <: Data](decoupled: DecoupledIO[T]): Unit = {
    emit(decoupled, true)
  }

  def emitConsumer[T <: Data](decoupled: DecoupledIO[T]): Unit = {
    emit(decoupled, false)
  }

  private def emit[T <: Data](decoupled: DecoupledIO[T], tx: Boolean): Unit = {
    val isPastValid = RegInit(false.B)
    isPastValid := true.B

    val pastReady = RegNext(decoupled.ready)
    val pastValid = RegNext(decoupled.valid)
    val pastBits = RegNext(decoupled.bits)

    // Ready must stay asserted while waiting - ready && !valid => ready
    val readyStaysAsserted = implies(isPastValid && (pastReady && !pastValid), decoupled.ready)

    // Valid and data must stay stable until ready - !ready && valid => valid && $stable(bits)
    val validStaysAsserted =
      implies(
        isPastValid && (!pastReady && pastValid),
        decoupled.valid && (pastBits === decoupled.bits)
      )

    if (tx) {
      assume(readyStaysAsserted)
      assert(validStaysAsserted)
    } else {
      assert(readyStaysAsserted)
      assume(validStaysAsserted)
    }

    // Cover a completed data transfer and valid waiting on ready
    chisel3.cover(decoupled.ready && decoupled.valid)
    chisel3.cover(!decoupled.ready && decoupled.valid)
  }
}
