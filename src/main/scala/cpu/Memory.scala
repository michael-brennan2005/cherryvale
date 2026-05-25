package cpu

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

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
class Memory(byteSize: Int, readPorts: Int, readWritePorts: Int)
    extends Module {
  val io = IO(new Bundle {
    val r = Vec(readPorts, new ReadPort)
    val rw = Vec(readWritePorts, new ReadWritePort)

    val led = Output(UInt(16.W))
    val sw = Input(UInt(16.W))
  })

  // Oh man !
  val mem = Mem(byteSize, UInt(8.W))

  val led_state = RegInit(UInt(16.W), 0.U)
  io.led := led_state

  for (i <- 0 until readPorts) {
    when(io.r(i).addr === "h400".U) {
      io.r(i).data := io.sw
    }.otherwise {
      io.r(i).data := Cat(
        mem(io.r(i).addr + 3.U),
        mem(io.r(i).addr + 2.U),
        mem(io.r(i).addr + 1.U),
        mem(io.r(i).addr)
      )
    }
  }

  for (i <- 0 until readWritePorts) {
    when(io.rw(i).addr === "h400".U) {
      io.rw(i).data := io.sw
    }.otherwise {
      io.rw(i).data := Cat(
        mem(io.rw(i).addr + 3.U),
        mem(io.rw(i).addr + 2.U),
        mem(io.rw(i).addr + 1.U),
        mem(io.rw(i).addr)
      )
    }

    when(io.rw(i).w_en === true.B) {
      when(io.rw(i).addr === "h401".U) {
        led_state := io.rw(i).w_data(15, 0)
      }.otherwise {
        mem(io.rw(i).addr + 3.U) := io.rw(i).w_data(31, 24)
        mem(io.rw(i).addr + 2.U) := io.rw(i).w_data(23, 16)
        mem(io.rw(i).addr + 1.U) := io.rw(i).w_data(15, 8)
        mem(io.rw(i).addr) := io.rw(i).w_data(7, 0)
      }
    }
  }
}
