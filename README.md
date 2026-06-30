# About
CherryVale is a RISC-V core/SoC implemented in Chisel HDL, and primarily tested on a Digilent Basys3 dev-board.

# Features
* 5-stage pipelined RISC-V core - Fetch, Decode, Read Operands, Execute/Mem, Writeback
* Full support for RV32I instruction set
* Custom Wishbone-inspired bus protocol
* Basys3 I/O support

# Big fun todo list
- [ ] Current core integration
  - [ ] FPGA BRAM slave device
  - [ ] CPU control unit - halt/reset/perf counters accessed via bus/UART
  - [ ] Flash utility
- [ ] Core improvements, RISCV features
  - [ ] registered stall signals + skidbuffers to optimize timing
  - [ ] proper caches
  - [ ] mulitply, float, divde units
  - [ ] CSR support
  - [ ] M-mode support
  - [ ] newlib/C toolchain
- [ ] FV/simulation
  - [ ] Proper Cherrytrunk bus models/sim, UVM-style
  - [ ] Verify core stages
- [ ] Basys3 Peripherals
  - [ ] VGA
  - [ ] USB HID
  - [ ] SPI ROM
  - [ ] Switches,LEDs,8-segment
  - [ ] PMOD SPI and I2C

# Project structure
```
build/                # Temp/generated outputs
    chiselsim/          # ChiselSim outputs
    formal/             # FV/YoSys outputs
    sv/                 # SystemVerilog outputs (for synth)
    vivado/             # Vivado outputs
src/
    main/scala/       # RTL code
        pit/            # CherryPit - RISCV core
        trunk/          # CherryTrunk - custom bus protocol
        vale/           # CherryVale - SoC w/ core & peripherals
        debug/          # UART debug master for communicating with SoC
        common/         # One-off and reusable modules
        harness/        # Tools/utils for generating SV, formal runs, etc.
    test/scala/       # Testing & simulation code
software/             # Support tools & FW
    cv-serial/           # Serial driver/monitor for CherryVale debug master
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
