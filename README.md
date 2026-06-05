# About
Cherryvale is a RISC-V core/SoC implemented in Chisel HDL, and primarily tested on a Digilent Basys3 dev-board.

# Features
* 5-stage pipelined RISC-V core - fetch, decode, execute, memory, register writeback
* (in progress) support for full RISCV32I base instruciton set
* (planned) formal verification
* (planned) interesting things

# Project structure
```
build/
    chiselsim/        # Outputs generated from ChiselSim
    sv/               # SystemVerilog outputs generated from Chisel
    vivado/           # Files generated from Vivado
src/
    main/scala/       # Chisel code
    test/scala/       # Chisel tests
synth/
    build.tcl         # Vivado script for synthesis, device upload
    constraints.xdc   # FPGA board file
Makefile              # Task runner
build.sbt             # Building Chisel code & tests
```

# Commands
For testing with waveforms: `(in sbt) testOnly <SPEC> -- -DemitVcd=1`
For formal verification:
```
sbt "runMain CounterFormal"            # all configured checks
sbt "runMain CounterFormal bmc"        # one mode
sbt "runMain CounterFormal bmc:50"     # override that check's depth
```
