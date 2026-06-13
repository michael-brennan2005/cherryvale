package debug

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chisel3.simulator.PeekPokeAPI
import cherrytrunk.Request
import cherrytrunk.Response
import com.carlosedp.riscvassembler.ObjectUtils.NumericManipulation

// Reusable Uart TX driver, with functions for encoding specific transactions or raw bytes.
class UartTxDriver(clocksPerBaud: Int, tx: Bool) extends PeekPokeAPI {
  var transactionSeq: Seq[Boolean] = Seq.empty

  private def addByte(byte: Byte): Unit = {
    // Start bit
    transactionSeq = transactionSeq ++ Seq.fill(clocksPerBaud)(false)

    // Data bits (LSB first)
    for (i <- 0 until 8) {
      val bit = ((byte >> i) & 1) == 1
      transactionSeq = transactionSeq ++ Seq.fill(clocksPerBaud)(bit)
    }

    // Stop bit
    transactionSeq = transactionSeq ++ Seq.fill(clocksPerBaud)(true)
  }

  // n is in cycles, not bauds
  def idleLine(n: Int): Unit = {
    transactionSeq ++ Seq.fill(n)(true)
  }

  def encodeByte(byte: Byte): Unit = {
    addByte(byte)
  }

  def encodeWrite(write: DoWrite): Unit = {
    val bytes = Seq(
      0b10011111.toByte,
      (write.address & 0xff).toByte,
      ((write.address >> 8) & 0xff).toByte,
      ((write.address >> 16) & 0xff).toByte,
      ((write.address >> 24) & 0xff).toByte,
      (write.data & 0xff).toByte,
      ((write.data >> 8) & 0xff).toByte,
      ((write.data >> 16) & 0xff).toByte,
      ((write.data >> 24) & 0xff).toByte
    )

    for (byte <- bytes) {
      addByte(byte)
    }
  }

  def encodeRead(read: DoRead): Unit = {
    val bytes = Seq(
      0b10011110.toByte,
      (read.address & 0xff).toByte,
      ((read.address >> 8) & 0xff).toByte,
      ((read.address >> 16) & 0xff).toByte,
      ((read.address >> 24) & 0xff).toByte
    )

    for (byte <- bytes) {
      addByte(byte)
    }
  }

  // Returns true if no transaction is currently being transmitted
  def tick(): Boolean = {
    if (transactionSeq.isEmpty) {
      tx.poke(true.B)
      true
    } else {
      tx.poke(transactionSeq.head)
      transactionSeq = transactionSeq.tail
      false
    }
  }
}

// Reusable UartRx monitor - successfully received bytes get put into self.bytes.
class UartRxMonitor(clocksPerBaud: Int, rx: Bool) extends PeekPokeAPI {
  var bytes: Seq[Int] = Seq.empty
  var receiving: Boolean = false
  var countdown: Int = 0
  var bitIndex: Int = 0
  var current: Int = 0
  var waitForHigh: Boolean = false

  def tick(): Unit = {
    val rxBool = rx.peekBoolean()

    if (waitForHigh && rxBool) {
      waitForHigh = false
      return
    } else if (waitForHigh && !rxBool) {
      return
    }

    if (!receiving && !rxBool) {
      receiving = true
      current = 0
      bitIndex = 0
      countdown = clocksPerBaud + clocksPerBaud / 2
    } else {
      countdown -= 1
      if (countdown == 0) {
        if (rxBool) current |= (1 << bitIndex)
        if (bitIndex == 7) {
          bytes = bytes :+ current
          waitForHigh = true
          receiving = false
        } else {
          bitIndex += 1
          countdown = clocksPerBaud
        }
      }
    }
  }

  def asString(): String = {
    bytes.map(_.toChar).mkString
  }
}
