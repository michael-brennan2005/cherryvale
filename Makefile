## --- Config ---
TOP       := Top
GEN_DIR   := build/sv
SIM_DIR   := build/sim
TB_CPP    := sim/cpp/tb_$(TOP).cpp
SV_FILES  := $(GEN_DIR)/$(TOP).sv

SBT       := sbt
VERILATOR := verilator
VIVADO    := vivado -mode batch -notrace -tempDir ../build/vivado

.PHONY: all chisel test verilate sim synth formal clean

# --- Chisel -> SystemVerilog ---
chisel:
	$(SBT) "run"

# Alias for inspecting output
sv: chisel
	@echo "Generated SystemVerilog in $(GEN_DIR)/"

# --- Scala/Chisel tests ---
test:
	$(SBT) test

# Run a single test class: make test-one T=MyModuleSpec
test-one:
	$(SBT) "testOnly *$(T)"

# --- Vivado synth + program ---
synth: chisel
	cd synth && $(VIVADO) -source build.tcl

# --- Cleanup ---
clean:
	rm -rf $(GEN_DIR) $(SIM_DIR) build target
	$(SBT) clean
