package cherrytrunk

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// Request type for cherrytrunk - sent by masters, received by slaves.
class Request extends Bundle {
  // Address to send request to. Must be valid between rising edge of request stb and falling edge of respone ack.
  val addr = UInt(32.W)

  // Data to send on writes. Must be valid between rising edge of request stb and falling edge of respone ack.
  val data = UInt(32.W)

  // For writes: which bytes to actually write to. For reads: which bytes
  // from the response should be valid data (bytes that aren't masked should be zeroed)
  // There are only eight valid values for this mask:
  // - b1000, b0100, b0010, b0001 (for byte reads/writes)
  // - b1100, b0011 (for half reads/writes)
  // - b1111 (for word reads/writes)
  // - b0000 (TODO: what does this mean)
  // Must be valid between rising edge of request stb and falling edge of respone ack.
  // TODO: can slave devices ignore this or should there be some default pattern they conform to.
  val mask = UInt(4.W)

  // True for write request, false for read request.
  // Must be valid between rising edge of request stb and falling edge of respone ack.
  val rw = Bool()

  // Start transaction strobe. Is high for exactly one cycle to signal start
  // of transaction.
  val stb = Bool()
}

// Response type for cherrytrunk - sent by slaves, received by masters.
class Response extends Bundle {
  // Data returned on read transaction. Is valid only when ack is high, and 0
  // otherwise.
  // TODO: on writes what should this be? junk value, dont care, etc.
  val data = UInt(32.W)

  // Transaction acknowledgement. Is high for exactly one cycle to signal
  // end of transaction.
  val ack = Bool()

  // Transaction error. Is valid only when ack is high. High when transaction has
  // erred, low on successful response.
  val err = Bool()
}
