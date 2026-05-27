package cpu

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import chisel3.util.experimental.loadMemoryFromFileInline

class ReadPort extends Bundle {
  val addr = Input(UInt(32.W))
  val data = Output(UInt(32.W))
}

class ReadWritePort extends Bundle {
  val addr = Input(UInt(32.W))
  val w_data = Input(UInt(32.W))
  val w_en = Input(Bool())
  val data = Output(UInt(32.W))
}

// TODO: holy shit this is not the way to do MMIO
class Memory(readOnlyPorts: Int, zeroed: Boolean = true) extends Module {
  val io = IO(new Bundle {
    val ro = Vec(readOnlyPorts, new ReadPort)
    val rw = new ReadWritePort

    val led = Output(UInt(16.W))
    val sw = Input(UInt(16.W))
  })

  val mem = if (zeroed) {
    RegInit(VecInit(Seq.fill(32)(0.U(32.W))))
  } else {
    RegInit(
      VecInit(
        Seq(
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W),
          0.U(32.W)
        )
      )
    )
  }

  val led_state = RegInit(UInt(16.W), 0.U)
  io.led := led_state

  for (i <- 0 until readOnlyPorts) {
    when(io.ro(i).addr === "h400".U) {
      io.ro(i).data := io.sw
    }.otherwise {
      io.ro(i).data := mem(io.ro(i).addr >> 2)
    }
  }

  when(io.rw.addr === "h400".U) {
    io.rw.data := io.sw
  }.otherwise {
    io.rw.data := mem(io.rw.addr >> 2)
  }

  when(io.rw.w_en === true.B) {
    when(io.rw.addr === "h404".U) {
      led_state := io.rw.w_data(15, 0)
    }.otherwise {
      mem(io.rw.addr >> 2) := io.rw.w_data
    }
  }

}
