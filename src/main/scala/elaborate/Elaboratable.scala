package elaborate

import chisel3.RawModule
import _root_.circt.stage.ChiselStage

/** Mix this onto an `object` to make it an elaboration entry point.
  *
  * The only thing you must provide is [[build]], which constructs the module to emit. Because
  * emission re-elaborates the design, [[build]] must produce a *fresh* module every time it is
  * called -- so implement it as a plain `def` (or a by-name expression), never a `val`.
  *
  * Any object mixing this in gets a `main`, so it shows up in `sbt run`'s selection list. Pass
  * `debug` (default) or `release` to pick the mode:
  *
  * {{{
  * sbt "runMain EmitFifo"          // debug build
  * sbt "runMain EmitFifo release"  // release build
  * }}}
  */
trait Elaboratable {

  /** The module to elaborate. Must return a fresh instance on each call. */
  def build: RawModule

  /** Output directory for emitted SystemVerilog. Override to customize. */
  def targetDir: String =
    s"./build/sv/${getClass.getSimpleName.stripSuffix("$")}"

  /** firtool options shared by every build. */
  protected def commonFirtoolOpts: Array[String] = Array(
    "-disable-all-randomization", // drop the `ifdef RANDOMIZE` init garbage
    "--verification-flavor=immediate",
    "--default-layer-specialization=enable"
  )

  /** Debug build: keep structure and names, minimal optimization. */
  def emitDebug(): Unit =
    emit(
      commonFirtoolOpts ++ Array(
        "-disable-opt", // no optimizations, keep structure
        "-preserve-values=named", // keep named wires/regs
        "-preserve-aggregate=all", // keep bundles/vecs as structs/arrays
        "--lowering-options=disallowClockedAssertions,disallowLocalVariables,disallowPackedArrays" // for FV
        // location info (@[File.scala 12:3]) is kept by default
      )
    )

  /** Release build: strip debug info, no name preservation, max optimization. */
  def emitRelease(): Unit =
    emit(
      commonFirtoolOpts ++ Array(
        "-strip-debug-info", // remove @[File.scala 12:3] comments
        "-O=release" // maximal optimization
      )
    )

  private def emit(firtoolOpts: Array[String]): Unit =
    ChiselStage.emitSystemVerilogFile(
      build,
      args = Array("--target-dir", targetDir),
      firtoolOpts = firtoolOpts
    )

  def main(args: Array[String]): Unit = args.headOption match {
    case Some("release")      => emitRelease()
    case Some("debug") | None => emitDebug()
    case Some(other)          =>
      System.err.println(s"unknown mode '$other'; use 'debug' or 'release'")
      sys.exit(1)
  }
}
