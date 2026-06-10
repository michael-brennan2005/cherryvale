package debug

import chisel3.simulator.ChiselSim

// Co-sim for UartTx - meant to simulate a receiver.
class UartTxSim(clocksPerBaud: Int) {
  var state = 0 // 0 -> rxIdle, 1 -> rxData
  var baudCounter = 0
  var bits = 0 // bit counter
  var data = 0 // shift reg

  val sb = new StringBuilder()

  def step(tx: Boolean): Unit = {
    if (state == 0) {
      // detect start bit
      if (!tx) {
        state = 1
        // wait a baud and a half so we'll always sample data in the middle of baud
        baudCounter = clocksPerBaud + (clocksPerBaud / 2) - 1
        bits = 0
        data = 0
      }
    } else if (baudCounter <= 0) {
      if (bits >= 8) {
        state = 0
        sb.append(data.toChar)
      } else {
        bits += 1
        data = if (tx) (0x80 | (data >> 1)) else (data >> 1)
      }
      baudCounter = clocksPerBaud - 1
    } else {
      baudCounter -= 1
    }
  }
}
