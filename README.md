# Project structure
```
build/
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
