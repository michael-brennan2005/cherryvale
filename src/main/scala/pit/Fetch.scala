package pit

import chisel3._
import _root_.circt.stage.ChiselStage
import chisel3.util.{EnqIO, Valid}
import chisel3.util.DeqIO

class FetchOutput extends Bundle {
  val pc = UInt(32.W)
  val pcPlus4 = UInt(32.W)
  val inst = UInt(32.W)
}

// Fetch stage - handles program counter, accessing instruction memory, and PC redirects.
//
// Buffering model: the I$ holds a completed response until we take it, and Fetch adds one registered
// output stage (`outValid`/`outBits`) downstream of it. Registering the boundary is what makes the
// control signals clean:
//   - halt  = FREEZE. No register in this stage updates, no new request issues, and the output port
//             keeps presenting whatever it already held. A response that *completes in the cache*
//             during halt simply stays parked there (we don't take it) and surfaces on release - it
//             is held, never hidden and never lost. Because the output is a register and not a
//             combinational view of the cache, "freeze" is literally "don't clock the register".
//   - clear / redirectPc = FLUSH (discard). Drop the held output, invalidate + drain the in-flight
//             request, and restart the PC (clear -> 0, redirect -> newPc). Flush outranks freeze.
class Fetch extends Module {
  val io = IO(new Bundle {
    // Global CPU signals
    val halt = Input(Bool())
    val clear = Input(Bool())

    // Stage output
    val out = EnqIO(new FetchOutput)

    // Connection to I$
    val req = EnqIO(new MemoryRequest)
    val resp = DeqIO(UInt(32.W))

    // PC redirection - comes from execute
    val redirectPc = Input(Bool())
    val newPc = Input(UInt(32.W))
  })

  // `pc`      - the address of the NEXT request we will issue.
  // `reqPc`   - the address of the request currently in flight in the cache (the one whose response
  //             we are waiting on / holding). Since the cache is single-outstanding, one register is
  //             enough. This is what labels a response, NOT `pc` - decoupling them is what makes
  //             redirect correct: `pc` can jump to `newPc` while the cache still holds a stale
  //             response for `reqPc`, and we must never pair the two.
  // `inFlight` - true when there is a *wanted* (non-flushed) request in the cache. A flushed request's
  //             response is drained but never surfaced.
  // `outValid`/`outBits` - the registered output stage. This is the stage's entire visible state, and
  //             it is exactly what halt freezes.
  val pc = RegInit(0.U(32.W))
  val reqPc = RegInit(0.U(32.W))
  val inFlight = RegInit(false.B)
  val outValid = RegInit(false.B)
  val outBits = RegInit(0.U.asTypeOf(new FetchOutput))

  // A redirect or clear flushes the in-flight fetch and the held output this cycle. `clear` is a full
  // Fetch reset (restart from pc=0); `redirectPc` is a targeted jump to `newPc`. They share the same
  // flush+drain machinery and differ only in the PC they reset to (see the counter below).
  val flush = io.redirectPc || io.clear

  // Request logic. Always offer `pc` to the cache; the cache back-pressures via req.ready, so a
  // request simply waits here until the cache is free (e.g. while a stale response drains). No new
  // requests issue under halt (freeze) or flush - on a flush `pc` is being updated this same cycle,
  // so issuing would latch a stale address.
  io.req.bits.addr := pc
  io.req.bits.we := false.B
  io.req.bits.writeData := 0.U
  io.req.bits.writeMask := 0.U
  io.req.valid := !io.halt && !flush

  // We take a response from the cache into the output register when it is a wanted, ready response and
  // the output slot is free (empty, or being consumed by the sink this cycle). Frozen under halt.
  val outFree = !outValid || io.out.ready
  val load = !io.halt && !flush && inFlight && io.resp.valid && outFree

  // PC counter. Flush wins over the sequential increment; the increment only happens when a request is
  // actually accepted, so a back-pressured (or halted) request doesn't skip an address.
  when(io.clear) {
    pc := 0.U
  }.elsewhen(io.redirectPc) {
    pc := io.newPc
  }.elsewhen(io.req.fire) {
    pc := pc + 4.U
  }

  // In-flight tracking. A newly accepted request always becomes the in-flight one (even if it
  // displaces a response being consumed on the same cycle). Otherwise the in-flight slot empties when
  // the response is taken/drained, or when a flush invalidates it.
  when(io.req.fire) {
    reqPc := pc
    inFlight := true.B
  }.elsewhen(flush || io.resp.fire) {
    inFlight := false.B
  }

  // Output register. Priority: flush discards, halt freezes, otherwise load the next response or drain
  // once the sink takes the current one. The `load` branch covering the "consume + reload" case is
  // what keeps throughput at one instruction per cycle once the pipeline is primed.
  when(flush) {
    outValid := false.B
  }.elsewhen(io.halt) {
    // Freeze: hold outValid/outBits exactly as they are.
  }.elsewhen(load) {
    outValid := true.B
    outBits.pc := reqPc
    outBits.pcPlus4 := reqPc + 4.U
    outBits.inst := io.resp.bits
  }.elsewhen(io.out.ready) {
    outValid := false.B
  }

  // Consume from the cache when we flush (drain stale), when the in-flight request is unwanted (drain
  // so the cache frees for the redirected request), or when we load its response into the output reg.
  io.resp.ready := flush || !inFlight || load

  io.out.valid := outValid
  io.out.bits := outBits
}
