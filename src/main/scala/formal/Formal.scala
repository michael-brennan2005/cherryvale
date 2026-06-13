package formal

import java.io.File

import _root_.circt.stage.ChiselStage

import scala.sys.process.Process

import elaborate.Elaboratable

/** A single SymbiYosys verification task to run against a module.
  *
  * Each check carries its own BMC/induction/cover depth, so a module can mix, e.g.,
  * `Seq(Bmc(40), Cover(20))` -- bounded model checking to 40 cycles plus cover reachability to 20.
  */
sealed trait Check {

  /** sby `mode` keyword: `bmc`, `prove`, or `cover`. */
  def mode: String

  /** sby `depth` -- BMC unroll length, induction depth, or cover trace length. */
  def depth: Int

  /** Return a copy with the depth overridden (used by `mode:depth` CLI args). */
  def withDepth(d: Int): Check
}

/** Bounded model check: prove every `assert` holds for the first `depth` cycles. */
final case class Bmc(depth: Int = 20) extends Check {
  val mode = "bmc"
  def withDepth(d: Int): Check = copy(depth = d)
}

/** Unbounded proof by k-induction (`depth` is the induction depth). */
final case class Prove(depth: Int = 20) extends Check {
  val mode = "prove"
  def withDepth(d: Int): Check = copy(depth = d)
}

/** Cover check: prove every `cover` statement is reachable within `depth` cycles. */
final case class Cover(depth: Int = 20) extends Check {
  val mode = "cover"
  def withDepth(d: Int): Check = copy(depth = d)
}

/** Mix this onto an `object` to make it a formal-verification entry point.
  *
  * Like [[Elaboratable]], you provide [[build]] (a fresh module each call). On top of elaboration,
  * [[main]] generates a reset harness and a SymbiYosys script for the module and runs `sby`,
  * streaming its console output live. Trace waveforms land under `build/formal/<ObjName>/<mode>/`.
  *
  * Formal properties (`assert`/`assume`/`cover`, or the `chisel3.ltl` properties) live in the
  * module itself -- this trait only drives the flow. Declare which checks to run via [[checks]]:
  *
  * {{{
  * object CounterFormal extends Formal {
  *   def build = new scratch.Counter
  *   override def checks = Seq(Bmc(40), Cover(20))
  * }
  * }}}
  *
  * {{{
  * sbt "runMain CounterFormal"            // run every configured check
  * sbt "runMain CounterFormal bmc"        // run only the bmc check
  * sbt "runMain CounterFormal bmc:50"     // run bmc, overriding its depth to 50
  * }}}
  *
  * ==Why the reset harness?==
  * Chisel lowers `RegInit` to a reset-conditioned assignment, not a Verilog `initial`, so a bare
  * BMC run starts registers at arbitrary values. The generated `<Top>Formal` wrapper holds `reset`
  * high in the initial state (via a harness `reg seen = 1'b0`, whose init value yosys formal *does*
  * honor) so `RegInit` defaults take effect.
  */
trait Formal extends Elaboratable {

  /** The checks to run for this module. Override to pick modes and depths. */
  def checks: Seq[Check] = Seq(Bmc())

  /** Directory holding the generated harness, sby scripts, and sby workdirs. */
  def formalDir: String = s"./build/formal/$objName"

  private def objName: String = getClass.getSimpleName.stripSuffix("$")

  /** A parsed Verilog port: direction, optional `[hi:lo]` width, and name. */
  private case class Port(dir: String, width: Option[String], name: String) {

    /** Re-render as a module port declaration, e.g. `input [7:0] io_in_bits`. */
    def decl: String = s"$dir ${width.map(_ + " ").getOrElse("")}$name"
  }

  // -- top module name -------------------------------------------------------------------

  /** The top module name, read authoritatively from the elaborated FIRRTL `circuit` line. */
  private def topName(): String = {
    val chirrtl = ChiselStage.emitCHIRRTL(build)
    val circuit = """(?m)^\s*circuit\s+(\w+)\s*:""".r
    circuit.findFirstMatchIn(chirrtl).map(_.group(1)).getOrElse {
      sys.error(s"$objName: could not find a `circuit` name in emitted FIRRTL")
    }
  }

  // -- port header parsing ---------------------------------------------------------------

  /** Parse the port header of module `top` from emitted SystemVerilog `svText`.
    *
    * firtool emits an ANSI header where the direction keyword carries across lines and each line
    * may carry a trailing `// comment`. We anchor to `module <top>(`, accumulate lines up to the
    * lone `);`, strip comments, then split on commas (a single line can hold several
    * comma-separated ports that all inherit the prior direction).
    */
  private def parsePorts(svText: String, top: String): Seq[Port] = {
    val lines = svText.linesIterator.toVector
    val startIdx = lines.indexWhere(
      _.matches(s"""\\s*module\\s+${java.util.regex.Pattern.quote(top)}\\s*\\(.*""")
    )
    if (startIdx < 0) sys.error(s"$objName: could not find `module $top(` header in emitted SV")

    // Accumulate header lines (comments stripped) from after the `module X(` up to a lone `);`.
    val header = new StringBuilder
    var i = startIdx
    var closed = false
    while (i < lines.length && !closed) {
      val code = lines(i).split("//", 2).head // drop trailing `// comment`
      val afterParen = if (i == startIdx) code.dropWhile(_ != '(').drop(1) else code
      if (afterParen.trim == ");") closed = true
      else header.append(afterParen).append(' ')
      i += 1
    }
    if (!closed) sys.error(s"$objName: unterminated `module $top(` port header in emitted SV")

    val widthRe = """\[\d+:\d+\]""".r
    var currentDir = ""
    val ports = header.toString
      .split(",")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toVector
      .flatMap { entry =>
        var toks = entry.split("\\s+").toList
        toks.headOption match {
          case Some(d @ ("input" | "output" | "inout")) =>
            currentDir = d
            toks = toks.tail
          case _ => // inherit currentDir
        }
        val width = toks.headOption.filter(t => widthRe.pattern.matcher(t).matches())
        if (width.isDefined) toks = toks.tail
        toks match {
          case name :: Nil if currentDir.nonEmpty => Some(Port(currentDir, width, name))
          case Nil                                => None
          case other                              =>
            sys.error(s"$objName: could not parse port entry '$entry' (tokens: $other)")
        }
      }

    if (ports.isEmpty) sys.error(s"$objName: parsed no ports from `module $top(` header")
    val names = ports.map(_.name).toSet
    if (!names("clock") || !names("reset"))
      sys.error(
        s"$objName: top module `$top` is missing a `clock` and/or `reset` port " +
          s"(found: ${ports.map(_.name).mkString(", ")}). The reset harness requires a plain " +
          s"`Module` with implicit clock and reset."
      )
    ports
  }

  // -- generators ------------------------------------------------------------------------

  /** Render the `<top>Formal` reset-harness wrapper. */
  private def renderHarness(top: String, ports: Seq[Port]): String = {
    val passthrough = ports.filterNot(p => p.name == "clock" || p.name == "reset")
    val portDecls = (Port("input", None, "clock") +: passthrough).map("  " + _.decl).mkString(",\n")
    val connections =
      (Seq(".clock(clock)", ".reset(reset)") ++ passthrough.map(p => s".${p.name}(${p.name})"))
        .map("    " + _)
        .mkString(",\n")
    s"""// Generated formal harness for $top. Holds reset high in the initial BMC state so that
       |// Chisel's RegInit values take effect: Chisel lowers RegInit to a reset-conditioned
       |// assignment, not a Verilog `initial`, so without this the solver is free to start
       |// registers at arbitrary values. The harness register `seen` carries an init value,
       |// which yosys formal honors.
       |module ${top}Formal(
       |$portDecls
       |);
       |  reg  seen = 1'b0;        // 0 only in the initial state
       |  wire reset = ~seen;      // reset high on cycle 0, low forever after
       |  always @(posedge clock) seen <= 1'b1;
       |
       |  $top dut(
       |$connections
       |  );
       |endmodule
       |""".stripMargin
  }

  /** Render a `.sby` script for one check. `dutFiles` are the emitted DUT `.sv` files.
    *
    * Formal helper modules (`AnyConst_w*`) carry yosys-native `(* anyconst *)` attributes that
    * `read_slang` would drop, so they are read with the native `read_verilog -sv -formal` frontend
    * *before* slang; slang then links to them as blackboxes via its always-on `--extern-modules`.
    *
    * The `flatten` + `setattr -set keep 1 t:$anyconst` before `prep` is load-bearing: two
    * same-width `$anyconst` cells (e.g. two `UInt(8.W)` free constants) are structurally identical,
    * so `opt_merge` inside `prep` would otherwise collapse them into a single constant -- silently
    * forcing the two "arbitrary" values equal and making the proof unsound. Flattening first gives
    * each instance its own cell; `keep` then stops the merge so they stay independent.
    */
  private def renderSby(top: String, check: Check, harness: File, dutFiles: Seq[File]): String = {
    val allFiles = harness +: dutFiles
    val filesSection = allFiles.map(_.getAbsolutePath).mkString("\n")

    val (formalHelpers, slangFiles) = dutFiles.partition(_.getName.startsWith("AnyConst"))
    val slangRead = (harness +: slangFiles).map(_.getName).mkString(" ")
    val helperLine =
      if (formalHelpers.isEmpty) ""
      else s"read_verilog -sv -formal ${formalHelpers.map(_.getName).mkString(" ")}\n"
    s"""[options]
       |mode ${check.mode}
       |depth ${check.depth}
       |
       |[engines]
       |smtbmc
       |
       |[script]
       |plugin -i slang
       |${helperLine}read_slang --top ${top}Formal $slangRead
       |hierarchy -top ${top}Formal
       |flatten
       |setattr -set keep 1 t:$$anyconst
       |setattr -set keep 1 w:*
       |prep -top ${top}Formal
       |
       |[files]
       |$filesSection
       |""".stripMargin
  }

  // -- run -------------------------------------------------------------------------------

  /** Run sby for one check, streaming its output live. Returns true on PASS. */
  private def runCheck(sbyFile: File, workdir: String): Boolean = {
    val cmd = Seq("sby", "-f", "-d", workdir, sbyFile.getAbsolutePath)
    println(s"\n$$ ${cmd.mkString(" ")}")
    Process(cmd).! == 0 // `.!` streams stdout/stderr to the console and returns the exit code
  }

  override def main(args: Array[String]): Unit = {
    val selected = selectChecks(args)

    val top = topName()
    emitDebug() // writes build/sv/<ObjName>/*.sv (DUT top + any submodules)

    val dutFiles = Option(new File(targetDir).listFiles())
      .getOrElse(Array.empty)
      .filter(_.getName.endsWith(".sv"))
      .sortBy(_.getName)
      .toVector
    val topSv = dutFiles.find(_.getName == s"$top.sv").getOrElse {
      sys.error(
        s"$objName: expected emitted file $top.sv in $targetDir, found: " +
          dutFiles.map(_.getName).mkString(", ")
      )
    }

    val ports = parsePorts(scala.io.Source.fromFile(topSv).mkString, top)

    val outDir = new File(formalDir)
    outDir.mkdirs()
    val harness = new File(outDir, s"${top}Formal.sv")
    writeFile(harness, renderHarness(top, ports))

    val results = selected.map { check =>
      val sbyFile = new File(outDir, s"${check.mode}.sby")
      writeFile(sbyFile, renderSby(top, check, harness, dutFiles))
      val workdir = s"$formalDir/${check.mode}"
      check -> runCheck(sbyFile, workdir)
    }

    println("\n=== formal summary ===")
    results.foreach { case (check, pass) =>
      println(f"  ${check.mode}%-6s depth=${check.depth}%-4d ${if (pass) "PASS" else "FAIL"}")
    }
  }

  /** Resolve the checks to run from CLI args (`mode[:depth]` tokens; empty = all). */
  private def selectChecks(args: Array[String]): Seq[Check] = {
    if (args.isEmpty) return checks

    val overrides = args.map { tok =>
      tok.split(":", 2) match {
        case Array(mode)        => mode -> None
        case Array(mode, depth) =>
          mode -> Some(
            depth.toIntOption.getOrElse(
              sys.error(s"$objName: invalid depth '$depth' in arg '$tok'")
            )
          )
        case _ => sys.error(s"$objName: invalid arg '$tok'")
      }
    }.toMap

    val selected = checks.collect {
      case c if overrides.contains(c.mode) =>
        overrides(c.mode).fold(c)(c.withDepth)
    }
    if (selected.isEmpty)
      sys.error(
        s"$objName: no checks match ${args.mkString(", ")}; " +
          s"available: ${checks.map(_.mode).mkString(" ")}"
      )
    selected
  }

  private def writeFile(file: File, content: String): Unit = {
    val w = new java.io.PrintWriter(file)
    try w.write(content)
    finally w.close()
  }
}
