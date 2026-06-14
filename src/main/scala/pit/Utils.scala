package pit

import com.carlosedp.riscvassembler.RISCVAssembler
import chisel3._

object Utils {
  private val MemWords = 32

  def buildMemInit(
      program: String,
      data: Seq[(Int, BigInt)]
  ): Seq[UInt] = {
    val insts = RISCVAssembler
      .fromString(program)
      .split('\n')
      .filter(_.nonEmpty)
      .map(line => BigInt(line, 16))

    val mem = Array.fill[BigInt](MemWords)(BigInt(0))
    for ((inst, idx) <- insts.zipWithIndex) mem(idx) = inst
    for ((addr, value) <- data) mem(addr / 4) = value
    mem.toSeq.map(_.U(32.W))
  }
}
