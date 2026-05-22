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

class Memory(byteSize: Int, readPorts: Int, readWritePorts: Int)
    extends Module {
  val io = IO(new Bundle {
    val r = Vec(readPorts, new ReadPort)
    val rw = Vec(readWritePorts, new ReadWritePort)
  })

  // Oh man !
  val mem = Mem(byteSize, UInt(8.W))

  for (i <- 0 until readPorts) {
    io.r(i).data := Cat(
      mem(io.r(i).addr + 3.U),
      mem(io.r(i).addr + 2.U),
      mem(io.r(i).addr + 1.U),
      mem(io.r(i).addr)
    )
  }

  for (i <- 0 until readWritePorts) {
    io.rw(i).data := Cat(
      mem(io.rw(i).addr + 3.U),
      mem(io.rw(i).addr + 2.U),
      mem(io.rw(i).addr + 1.U),
      mem(io.rw(i).addr)
    )

    when(io.rw(i).w_en === true.B) {
      mem(io.rw(i).addr + 3.U) := io.rw(i).w_data(31, 24)
      mem(io.rw(i).addr + 2.U) := io.rw(i).w_data(23, 16)
      mem(io.rw(i).addr + 1.U) := io.rw(i).w_data(15, 8)
      mem(io.rw(i).addr) := io.rw(i).w_data(7, 0)
    }
  }
}
