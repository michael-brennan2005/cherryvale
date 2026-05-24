import com.carlosedp.riscvassembler.RISCVAssembler

object Utils {
  // Convert risc-V to little-endian byte array. Useful for testing
  def riscVToByteArray(program: String): Seq[BigInt] = {
    RISCVAssembler
      .fromString(program)
      .split('\n')
      .filter(_.nonEmpty)
      .map(line => BigInt(line, 16))
      .map(inst =>
        Seq(
          (inst & 0x000000ff),
          (inst & 0x0000ff00) >> 8,
          (inst & 0x00ff0000) >> 16,
          (inst & 0xff000000) >> 24
        )
      )
      .flatten
      .toSeq
  }
}
