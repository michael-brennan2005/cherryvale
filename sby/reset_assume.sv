// Formal-only environment helper, bound onto BasysIoFormal.
//
//  * Forces the trace to begin with reset asserted (cycle 0). firtool gates the
//    protocol assertions with `disable iff (~hasBeenReset)` and emits
//    `initial hasBeenResetReg = 1'bx`; without a genuine reset at the start an SMT
//    solver may pick a state where hasBeenReset is already 1 while the reset-
//    initialized monitor registers hold garbage. `initState` has a *definite* init
//    value (honored by yosys), so reset is assumed in cycle 0 only.
//  * Assumes no bus transaction is launched while the device is held in reset
//    (a well-behaved master keeps `stb` low during reset).
module ResetAssume(input clock, input reset, input req_stb);
  reg initState = 1'b1;
  always @(posedge clock) begin
    initState <= 1'b0;
    if (initState) assume (reset);
    if (reset)     assume (!req_stb);
  end
endmodule

bind BasysIoFormal ResetAssume reset_assume_i(.clock(clock), .reset(reset), .req_stb(req_stb));
