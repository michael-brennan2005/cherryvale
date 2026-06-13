package formal

import chisel3._
import chisel3.util._
import chisel3.ltl.{AssertProperty, AssumeProperty}
import chisel3.layer
import chisel3.layers.Verification
import cherrytrunk.Request
import cherrytrunk.Response
import formal.Utils.implies

/** Formal properties for the Cherrytrunk bus. This module encodes both slave and master properties,
  * and exposes 2 methods for emitting the correct assertions/assumes. checkSlave will assert all
  * slave properties and assume all master properties, and checkMaster will assert all master
  * properties and assume all slave properties.
  */
// TODO: This is all super broken and does not work right now
object CherrytrunkProperties {

  /** The legal cherrytrunk byte-enable mask values (single byte / half / word). */
  val LegalMasks: Seq[Int] = Seq(0x0, 0x1, 0x2, 0x4, 0x8, 0x3, 0xc, 0xf)

  /** Verify a slave: assert the slave's obligations, assume a well-behaved master. */
  def checkSlave(req: Request, resp: Response, n: Int = 16): Unit =
    emit(req, resp, n, slaveSide = true)

  /** Verify a master: assert the master's obligations, assume a well-behaved slave. */
  def checkMaster(req: Request, resp: Response, n: Int = 16): Unit =
    emit(req, resp, n, slaveSide = false)

  private def emit(req: Request, resp: Response, n: Int, slaveSide: Boolean): Unit = {
    // ---- auxiliary verification-only state ----
    // A transaction is outstanding from the strobe until its acknowledgement.
    val transaction = RegInit(false.B)
    when(req.stb) { transaction := true.B }
    when(resp.ack) { transaction := false.B }

    // Cycles elapsed since the strobe while waiting for ack (for bounded liveness).
    val sinceStb = RegInit(0.U(log2Ceil(n + 2).W))
    when(req.stb && !resp.ack) {
      sinceStb := 1.U
    }.elsewhen(sinceStb =/= 0.U && !resp.ack) {
      sinceStb := sinceStb + 1.U
    }.elsewhen(resp.ack) {
      sinceStb := 0.U
    }

    val maskLegal = validCherrytrunkMask(req.mask)

    // ---- slave obligations ----
    val ackIsPulse = !(RegNext(resp.ack, false.B) && resp.ack) // ack high <= one cycle
    val dataZeroIdle = implies(!resp.ack, (resp.data === 0.U)) // data == 0 unless ack
    val noSpuriousAck = !resp.ack || transaction || req.stb // ack only for a real request
    val boundedLive = sinceStb <= n.U // ack arrives within n cycles
    val slaveProps = Seq(ackIsPulse, dataZeroIdle, noSpuriousAck, boundedLive)

    // ---- master obligations ----
    val stbIsPulse = !(RegNext(req.stb, false.B) && req.stb) // stb high <= one cycle
    val addrStable = implies(transaction, (req.addr === RegNext(req.addr))) // fields held while
    val dataStable = implies(transaction, (req.data === RegNext(req.data))) //   a transaction is
    val maskStable = implies(transaction, (req.mask === RegNext(req.mask))) //   outstanding
    val rwStable = implies(transaction, (req.we === RegNext(req.we)))
    val maskValid = implies(req.stb, maskLegal) // legal mask on every strobe
    val cycHeld = implies(req.stb || transaction, req.cyc) // cyc held high for full transaction
    val masterProps =
      Seq(stbIsPulse, addrStable, dataStable, maskStable, rwStable, maskValid, cycHeld)

    if (slaveSide) {
      masterProps.foreach(assume(_))
      slaveProps.foreach(assert(_))
    } else {
      slaveProps.foreach(assume(_))
      masterProps.foreach(assert(_))
    }
  }

  // Cherrytrunk protocol - check if a 4bit sequence is a valid mask
  def validCherrytrunkMask(m: UInt): Bool = {
    Seq(0x0, 0x1, 0x2, 0x4, 0x8, 0x3, 0xc, 0xf)
      .map(mask => m === mask.U)
      .reduce(_ || _)
  }
}
