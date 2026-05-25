## This file is a general .xdc for the Basys3 rev B board
## To use it in a project:
## - uncomment the lines corresponding to used pins
## - rename the used ports (in each line, after get_ports) according to the top level signal names in the project

## Clock signal
set_property -dict {PACKAGE_PIN W5 IOSTANDARD LVCMOS33} [get_ports clock]
create_clock -period 1000.000 -name sys_clk_pin -waveform {0.000 5.000} -add [get_ports clock]


## Switches
set_property -dict {PACKAGE_PIN V17 IOSTANDARD LVCMOS33} [get_ports {io_sw[0]}]
set_property -dict {PACKAGE_PIN V16 IOSTANDARD LVCMOS33} [get_ports {io_sw[1]}]
set_property -dict {PACKAGE_PIN W16 IOSTANDARD LVCMOS33} [get_ports {io_sw[2]}]
set_property -dict {PACKAGE_PIN W17 IOSTANDARD LVCMOS33} [get_ports {io_sw[3]}]
set_property -dict {PACKAGE_PIN W15 IOSTANDARD LVCMOS33} [get_ports {io_sw[4]}]
set_property -dict {PACKAGE_PIN V15 IOSTANDARD LVCMOS33} [get_ports {io_sw[5]}]
set_property -dict {PACKAGE_PIN W14 IOSTANDARD LVCMOS33} [get_ports {io_sw[6]}]
set_property -dict {PACKAGE_PIN W13 IOSTANDARD LVCMOS33} [get_ports {io_sw[7]}]
set_property -dict {PACKAGE_PIN V2 IOSTANDARD LVCMOS33} [get_ports {io_sw[8]}]
set_property -dict {PACKAGE_PIN T3 IOSTANDARD LVCMOS33} [get_ports {io_sw[9]}]
set_property -dict {PACKAGE_PIN T2 IOSTANDARD LVCMOS33} [get_ports {io_sw[10]}]
set_property -dict {PACKAGE_PIN R3 IOSTANDARD LVCMOS33} [get_ports {io_sw[11]}]
set_property -dict {PACKAGE_PIN W2 IOSTANDARD LVCMOS33} [get_ports {io_sw[12]}]
set_property -dict {PACKAGE_PIN U1 IOSTANDARD LVCMOS33} [get_ports {io_sw[13]}]
set_property -dict {PACKAGE_PIN T1 IOSTANDARD LVCMOS33} [get_ports {io_sw[14]}]
set_property -dict {PACKAGE_PIN R2 IOSTANDARD LVCMOS33} [get_ports {io_sw[15]}]

## LEDs
set_property -dict {PACKAGE_PIN U16 IOSTANDARD LVCMOS33} [get_ports {io_led[0]}]
set_property -dict {PACKAGE_PIN E19 IOSTANDARD LVCMOS33} [get_ports {io_led[1]}]
set_property -dict {PACKAGE_PIN U19 IOSTANDARD LVCMOS33} [get_ports {io_led[2]}]
set_property -dict {PACKAGE_PIN V19 IOSTANDARD LVCMOS33} [get_ports {io_led[3]}]
set_property -dict {PACKAGE_PIN W18 IOSTANDARD LVCMOS33} [get_ports {io_led[4]}]
set_property -dict {PACKAGE_PIN U15 IOSTANDARD LVCMOS33} [get_ports {io_led[5]}]
set_property -dict {PACKAGE_PIN U14 IOSTANDARD LVCMOS33} [get_ports {io_led[6]}]
set_property -dict {PACKAGE_PIN V14 IOSTANDARD LVCMOS33} [get_ports {io_led[7]}]
set_property -dict {PACKAGE_PIN V13 IOSTANDARD LVCMOS33} [get_ports {io_led[8]}]
set_property -dict {PACKAGE_PIN V3 IOSTANDARD LVCMOS33} [get_ports {io_led[9]}]
set_property -dict {PACKAGE_PIN W3 IOSTANDARD LVCMOS33} [get_ports {io_led[10]}]
set_property -dict {PACKAGE_PIN U3 IOSTANDARD LVCMOS33} [get_ports {io_led[11]}]
set_property -dict {PACKAGE_PIN P3 IOSTANDARD LVCMOS33} [get_ports {io_led[12]}]
set_property -dict {PACKAGE_PIN N3 IOSTANDARD LVCMOS33} [get_ports {io_led[13]}]
set_property -dict {PACKAGE_PIN P1 IOSTANDARD LVCMOS33} [get_ports {io_led[14]}]
set_property -dict {PACKAGE_PIN L1 IOSTANDARD LVCMOS33} [get_ports {io_led[15]}]


##7 Segment Display
#set_property -dict { PACKAGE_PIN W7   IOSTANDARD LVCMOS33 } [get_ports {io_seg[0]}]
#set_property -dict { PACKAGE_PIN W6   IOSTANDARD LVCMOS33 } [get_ports {io_seg[1]}]
#set_property -dict { PACKAGE_PIN U8   IOSTANDARD LVCMOS33 } [get_ports {io_seg[2]}]
#set_property -dict { PACKAGE_PIN V8   IOSTANDARD LVCMOS33 } [get_ports {io_seg[3]}]
#set_property -dict { PACKAGE_PIN U5   IOSTANDARD LVCMOS33 } [get_ports {io_seg[4]}]
#set_property -dict { PACKAGE_PIN V5   IOSTANDARD LVCMOS33 } [get_ports {io_seg[5]}]
#set_property -dict { PACKAGE_PIN U7   IOSTANDARD LVCMOS33 } [get_ports {io_seg[6]}]

#set_property -dict { PACKAGE_PIN V7   IOSTANDARD LVCMOS33 } [get_ports io_dp]

#set_property -dict { PACKAGE_PIN U2   IOSTANDARD LVCMOS33 } [get_ports {io_an[0]}]
#set_property -dict { PACKAGE_PIN U4   IOSTANDARD LVCMOS33 } [get_ports {io_an[1]}]
#set_property -dict { PACKAGE_PIN V4   IOSTANDARD LVCMOS33 } [get_ports {io_an[2]}]
#set_property -dict { PACKAGE_PIN W4   IOSTANDARD LVCMOS33 } [get_ports {io_an[3]}]


##Buttons
#set_property -dict { PACKAGE_PIN U18   IOSTANDARD LVCMOS33 } [get_ports btnC]
#set_property -dict { PACKAGE_PIN T18   IOSTANDARD LVCMOS33 } [get_ports btnU]
#set_property -dict { PACKAGE_PIN W19   IOSTANDARD LVCMOS33 } [get_ports btnL]
#set_property -dict { PACKAGE_PIN T17   IOSTANDARD LVCMOS33 } [get_ports btnR]
set_property -dict {PACKAGE_PIN U17 IOSTANDARD LVCMOS33} [get_ports reset]


##Pmod Header JA
#set_property -dict { PACKAGE_PIN J1   IOSTANDARD LVCMOS33 } [get_ports {JA[0]}];#Sch name = JA1
#set_property -dict { PACKAGE_PIN L2   IOSTANDARD LVCMOS33 } [get_ports {JA[1]}];#Sch name = JA2
#set_property -dict { PACKAGE_PIN J2   IOSTANDARD LVCMOS33 } [get_ports {JA[2]}];#Sch name = JA3
#set_property -dict { PACKAGE_PIN G2   IOSTANDARD LVCMOS33 } [get_ports {JA[3]}];#Sch name = JA4
#set_property -dict { PACKAGE_PIN H1   IOSTANDARD LVCMOS33 } [get_ports {JA[4]}];#Sch name = JA7
#set_property -dict { PACKAGE_PIN K2   IOSTANDARD LVCMOS33 } [get_ports {JA[5]}];#Sch name = JA8
#set_property -dict { PACKAGE_PIN H2   IOSTANDARD LVCMOS33 } [get_ports {JA[6]}];#Sch name = JA9
#set_property -dict { PACKAGE_PIN G3   IOSTANDARD LVCMOS33 } [get_ports {JA[7]}];#Sch name = JA10

##Pmod Header JB
#set_property -dict { PACKAGE_PIN A14   IOSTANDARD LVCMOS33 } [get_ports {JB[0]}];#Sch name = JB1
#set_property -dict { PACKAGE_PIN A16   IOSTANDARD LVCMOS33 } [get_ports {JB[1]}];#Sch name = JB2
#set_property -dict { PACKAGE_PIN B15   IOSTANDARD LVCMOS33 } [get_ports {JB[2]}];#Sch name = JB3
#set_property -dict { PACKAGE_PIN B16   IOSTANDARD LVCMOS33 } [get_ports {JB[3]}];#Sch name = JB4
#set_property -dict { PACKAGE_PIN A15   IOSTANDARD LVCMOS33 } [get_ports {JB[4]}];#Sch name = JB7
#set_property -dict { PACKAGE_PIN A17   IOSTANDARD LVCMOS33 } [get_ports {JB[5]}];#Sch name = JB8
#set_property -dict { PACKAGE_PIN C15   IOSTANDARD LVCMOS33 } [get_ports {JB[6]}];#Sch name = JB9
#set_property -dict { PACKAGE_PIN C16   IOSTANDARD LVCMOS33 } [get_ports {JB[7]}];#Sch name = JB10

##Pmod Header JC
#set_property -dict { PACKAGE_PIN K17   IOSTANDARD LVCMOS33 } [get_ports {JC[0]}];#Sch name = JC1
#set_property -dict { PACKAGE_PIN M18   IOSTANDARD LVCMOS33 } [get_ports {JC[1]}];#Sch name = JC2
#set_property -dict { PACKAGE_PIN N17   IOSTANDARD LVCMOS33 } [get_ports {JC[2]}];#Sch name = JC3
#set_property -dict { PACKAGE_PIN P18   IOSTANDARD LVCMOS33 } [get_ports {JC[3]}];#Sch name = JC4
#set_property -dict { PACKAGE_PIN L17   IOSTANDARD LVCMOS33 } [get_ports {JC[4]}];#Sch name = JC7
#set_property -dict { PACKAGE_PIN M19   IOSTANDARD LVCMOS33 } [get_ports {JC[5]}];#Sch name = JC8
#set_property -dict { PACKAGE_PIN P17   IOSTANDARD LVCMOS33 } [get_ports {JC[6]}];#Sch name = JC9
#set_property -dict { PACKAGE_PIN R18   IOSTANDARD LVCMOS33 } [get_ports {JC[7]}];#Sch name = JC10

##Pmod Header JXADC
#set_property -dict { PACKAGE_PIN J3   IOSTANDARD LVCMOS33 } [get_ports {JXADC[0]}];#Sch name = XA1_P
#set_property -dict { PACKAGE_PIN L3   IOSTANDARD LVCMOS33 } [get_ports {JXADC[1]}];#Sch name = XA2_P
#set_property -dict { PACKAGE_PIN M2   IOSTANDARD LVCMOS33 } [get_ports {JXADC[2]}];#Sch name = XA3_P
#set_property -dict { PACKAGE_PIN N2   IOSTANDARD LVCMOS33 } [get_ports {JXADC[3]}];#Sch name = XA4_P
#set_property -dict { PACKAGE_PIN K3   IOSTANDARD LVCMOS33 } [get_ports {JXADC[4]}];#Sch name = XA1_N
#set_property -dict { PACKAGE_PIN M3   IOSTANDARD LVCMOS33 } [get_ports {JXADC[5]}];#Sch name = XA2_N
#set_property -dict { PACKAGE_PIN M1   IOSTANDARD LVCMOS33 } [get_ports {JXADC[6]}];#Sch name = XA3_N
#set_property -dict { PACKAGE_PIN N1   IOSTANDARD LVCMOS33 } [get_ports {JXADC[7]}];#Sch name = XA4_N


##VGA Connector
#set_property -dict { PACKAGE_PIN G19   IOSTANDARD LVCMOS33 } [get_ports {vgaRed[0]}]
#set_property -dict { PACKAGE_PIN H19   IOSTANDARD LVCMOS33 } [get_ports {vgaRed[1]}]
#set_property -dict { PACKAGE_PIN J19   IOSTANDARD LVCMOS33 } [get_ports {vgaRed[2]}]
#set_property -dict { PACKAGE_PIN N19   IOSTANDARD LVCMOS33 } [get_ports {vgaRed[3]}]
#set_property -dict { PACKAGE_PIN N18   IOSTANDARD LVCMOS33 } [get_ports {vgaBlue[0]}]
#set_property -dict { PACKAGE_PIN L18   IOSTANDARD LVCMOS33 } [get_ports {vgaBlue[1]}]
#set_property -dict { PACKAGE_PIN K18   IOSTANDARD LVCMOS33 } [get_ports {vgaBlue[2]}]
#set_property -dict { PACKAGE_PIN J18   IOSTANDARD LVCMOS33 } [get_ports {vgaBlue[3]}]
#set_property -dict { PACKAGE_PIN J17   IOSTANDARD LVCMOS33 } [get_ports {vgaGreen[0]}]
#set_property -dict { PACKAGE_PIN H17   IOSTANDARD LVCMOS33 } [get_ports {vgaGreen[1]}]
#set_property -dict { PACKAGE_PIN G17   IOSTANDARD LVCMOS33 } [get_ports {vgaGreen[2]}]
#set_property -dict { PACKAGE_PIN D17   IOSTANDARD LVCMOS33 } [get_ports {vgaGreen[3]}]
#set_property -dict { PACKAGE_PIN P19   IOSTANDARD LVCMOS33 } [get_ports Hsync]
#set_property -dict { PACKAGE_PIN R19   IOSTANDARD LVCMOS33 } [get_ports Vsync]


##USB-RS232 Interface
#set_property -dict { PACKAGE_PIN B18   IOSTANDARD LVCMOS33 } [get_ports RsRx]
#set_property -dict { PACKAGE_PIN A18   IOSTANDARD LVCMOS33 } [get_ports RsTx]


##USB HID (PS/2)
#set_property -dict { PACKAGE_PIN C17   IOSTANDARD LVCMOS33   PULLUP true } [get_ports PS2Clk]
#set_property -dict { PACKAGE_PIN B17   IOSTANDARD LVCMOS33   PULLUP true } [get_ports PS2Data]


##Quad SPI Flash
##Note that CCLK_0 cannot be placed in 7 series devices. You can access it using the
##STARTUPE2 primitive.
#set_property -dict { PACKAGE_PIN D18   IOSTANDARD LVCMOS33 } [get_ports {QspiDB[0]}]
#set_property -dict { PACKAGE_PIN D19   IOSTANDARD LVCMOS33 } [get_ports {QspiDB[1]}]
#set_property -dict { PACKAGE_PIN G18   IOSTANDARD LVCMOS33 } [get_ports {QspiDB[2]}]
#set_property -dict { PACKAGE_PIN F18   IOSTANDARD LVCMOS33 } [get_ports {QspiDB[3]}]
#set_property -dict { PACKAGE_PIN K19   IOSTANDARD LVCMOS33 } [get_ports QspiCSn]


## Configuration options, can be used for all designs
set_property CONFIG_VOLTAGE 3.3 [current_design]
set_property CFGBVS VCCO [current_design]

## SPI configuration mode options for QSPI boot, can be used for all designs
set_property BITSTREAM.GENERAL.COMPRESS TRUE [current_design]
set_property BITSTREAM.CONFIG.CONFIGRATE 33 [current_design]
set_property CONFIG_MODE SPIx4 [current_design]

set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[8]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[22]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[24]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[28]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[3]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[15]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[30]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[6]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[19]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[7]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[20]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[2]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[14]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[27]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[9]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[23]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[11]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[5]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[17]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[25]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[4]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[16]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[29]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[18]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[21]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[0]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[12]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[1]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[13]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[26]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[10]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/pc/io_o_pc[31]}]
set_property MARK_DEBUG true [get_nets {cpu/control/io_i_inst[5]}]
set_property MARK_DEBUG true [get_nets {cpu/control/io_i_inst[6]}]
set_property MARK_DEBUG true [get_nets {cpu/control/io_i_inst[14]}]
set_property MARK_DEBUG true [get_nets {cpu/control/io_i_inst[30]}]
set_property MARK_DEBUG true [get_nets {cpu/control/io_i_inst[0]}]
set_property MARK_DEBUG true [get_nets {cpu/control/io_i_inst[12]}]
set_property MARK_DEBUG true [get_nets {cpu/control/io_i_inst[4]}]
set_property MARK_DEBUG true [get_nets {cpu/control/io_i_inst[13]}]
set_property MARK_DEBUG true [get_nets {cpu/control/io_i_inst[2]}]
set_property MARK_DEBUG true [get_nets {cpu/control/io_i_inst[3]}]
set_property MARK_DEBUG true [get_nets {cpu/control/io_i_inst[1]}]

set_property MARK_DEBUG true [get_nets {cpu/memory/io_sw[0]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_sw[1]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_sw[2]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_sw[5]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_sw[3]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_sw[4]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_sw[6]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_sw[15]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_sw[7]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_sw[8]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_sw[9]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_sw[10]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_sw[11]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_sw[12]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_sw[13]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_sw[14]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_led[7]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_led[0]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_led[1]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_led[2]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_led[11]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_led[3]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_led[12]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_led[13]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_led[15]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_led[4]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_led[14]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_led[5]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_led[6]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_led[8]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_led[9]}]
set_property MARK_DEBUG true [get_nets {cpu/memory/io_led[10]}]


set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[5]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[6]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[0]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[1]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[2]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[3]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[4]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[19]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[26]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[28]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[20]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[25]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[24]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[12]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[27]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[31]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[30]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[29]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[22]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[23]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[7]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[8]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[13]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[14]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[15]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[9]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[16]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[17]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[18]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[10]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[11]}]
set_property MARK_DEBUG true [get_nets {cpu/data_path/alu_src[21]}]
set_property MARK_DEBUG true [get_nets {cpu/control/io_control[alu_src]}]
connect_debug_port u_ila_0/probe5 [get_nets [list {cpu/control/io_control[alu_src]}]]

create_debug_core u_ila_0 ila
set_property ALL_PROBE_SAME_MU true [get_debug_cores u_ila_0]
set_property ALL_PROBE_SAME_MU_CNT 1 [get_debug_cores u_ila_0]
set_property C_ADV_TRIGGER false [get_debug_cores u_ila_0]
set_property C_DATA_DEPTH 1024 [get_debug_cores u_ila_0]
set_property C_EN_STRG_QUAL false [get_debug_cores u_ila_0]
set_property C_INPUT_PIPE_STAGES 0 [get_debug_cores u_ila_0]
set_property C_TRIGIN_EN false [get_debug_cores u_ila_0]
set_property C_TRIGOUT_EN false [get_debug_cores u_ila_0]
set_property port_width 1 [get_debug_ports u_ila_0/clk]
connect_debug_port u_ila_0/clk [get_nets [list clock_IBUF_BUFG]]
set_property PROBE_TYPE DATA_AND_TRIGGER [get_debug_ports u_ila_0/probe0]
set_property port_width 16 [get_debug_ports u_ila_0/probe0]
connect_debug_port u_ila_0/probe0 [get_nets [list {cpu/memory/io_led[0]} {cpu/memory/io_led[1]} {cpu/memory/io_led[2]} {cpu/memory/io_led[3]} {cpu/memory/io_led[4]} {cpu/memory/io_led[5]} {cpu/memory/io_led[6]} {cpu/memory/io_led[7]} {cpu/memory/io_led[8]} {cpu/memory/io_led[9]} {cpu/memory/io_led[10]} {cpu/memory/io_led[11]} {cpu/memory/io_led[12]} {cpu/memory/io_led[13]} {cpu/memory/io_led[14]} {cpu/memory/io_led[15]}]]
create_debug_port u_ila_0 probe
set_property PROBE_TYPE DATA_AND_TRIGGER [get_debug_ports u_ila_0/probe1]
set_property port_width 16 [get_debug_ports u_ila_0/probe1]
connect_debug_port u_ila_0/probe1 [get_nets [list {cpu/memory/io_sw[0]} {cpu/memory/io_sw[1]} {cpu/memory/io_sw[2]} {cpu/memory/io_sw[3]} {cpu/memory/io_sw[4]} {cpu/memory/io_sw[5]} {cpu/memory/io_sw[6]} {cpu/memory/io_sw[7]} {cpu/memory/io_sw[8]} {cpu/memory/io_sw[9]} {cpu/memory/io_sw[10]} {cpu/memory/io_sw[11]} {cpu/memory/io_sw[12]} {cpu/memory/io_sw[13]} {cpu/memory/io_sw[14]} {cpu/memory/io_sw[15]}]]
create_debug_port u_ila_0 probe
set_property PROBE_TYPE DATA_AND_TRIGGER [get_debug_ports u_ila_0/probe2]
set_property port_width 32 [get_debug_ports u_ila_0/probe2]
connect_debug_port u_ila_0/probe2 [get_nets [list {cpu/data_path/alu_src[0]} {cpu/data_path/alu_src[1]} {cpu/data_path/alu_src[2]} {cpu/data_path/alu_src[3]} {cpu/data_path/alu_src[4]} {cpu/data_path/alu_src[5]} {cpu/data_path/alu_src[6]} {cpu/data_path/alu_src[7]} {cpu/data_path/alu_src[8]} {cpu/data_path/alu_src[9]} {cpu/data_path/alu_src[10]} {cpu/data_path/alu_src[11]} {cpu/data_path/alu_src[12]} {cpu/data_path/alu_src[13]} {cpu/data_path/alu_src[14]} {cpu/data_path/alu_src[15]} {cpu/data_path/alu_src[16]} {cpu/data_path/alu_src[17]} {cpu/data_path/alu_src[18]} {cpu/data_path/alu_src[19]} {cpu/data_path/alu_src[20]} {cpu/data_path/alu_src[21]} {cpu/data_path/alu_src[22]} {cpu/data_path/alu_src[23]} {cpu/data_path/alu_src[24]} {cpu/data_path/alu_src[25]} {cpu/data_path/alu_src[26]} {cpu/data_path/alu_src[27]} {cpu/data_path/alu_src[28]} {cpu/data_path/alu_src[29]} {cpu/data_path/alu_src[30]} {cpu/data_path/alu_src[31]}]]
create_debug_port u_ila_0 probe
set_property PROBE_TYPE DATA_AND_TRIGGER [get_debug_ports u_ila_0/probe3]
set_property port_width 32 [get_debug_ports u_ila_0/probe3]
connect_debug_port u_ila_0/probe3 [get_nets [list {cpu/data_path/pc/io_o_pc[0]} {cpu/data_path/pc/io_o_pc[1]} {cpu/data_path/pc/io_o_pc[2]} {cpu/data_path/pc/io_o_pc[3]} {cpu/data_path/pc/io_o_pc[4]} {cpu/data_path/pc/io_o_pc[5]} {cpu/data_path/pc/io_o_pc[6]} {cpu/data_path/pc/io_o_pc[7]} {cpu/data_path/pc/io_o_pc[8]} {cpu/data_path/pc/io_o_pc[9]} {cpu/data_path/pc/io_o_pc[10]} {cpu/data_path/pc/io_o_pc[11]} {cpu/data_path/pc/io_o_pc[12]} {cpu/data_path/pc/io_o_pc[13]} {cpu/data_path/pc/io_o_pc[14]} {cpu/data_path/pc/io_o_pc[15]} {cpu/data_path/pc/io_o_pc[16]} {cpu/data_path/pc/io_o_pc[17]} {cpu/data_path/pc/io_o_pc[18]} {cpu/data_path/pc/io_o_pc[19]} {cpu/data_path/pc/io_o_pc[20]} {cpu/data_path/pc/io_o_pc[21]} {cpu/data_path/pc/io_o_pc[22]} {cpu/data_path/pc/io_o_pc[23]} {cpu/data_path/pc/io_o_pc[24]} {cpu/data_path/pc/io_o_pc[25]} {cpu/data_path/pc/io_o_pc[26]} {cpu/data_path/pc/io_o_pc[27]} {cpu/data_path/pc/io_o_pc[28]} {cpu/data_path/pc/io_o_pc[29]} {cpu/data_path/pc/io_o_pc[30]} {cpu/data_path/pc/io_o_pc[31]}]]
create_debug_port u_ila_0 probe
set_property PROBE_TYPE DATA_AND_TRIGGER [get_debug_ports u_ila_0/probe4]
set_property port_width 32 [get_debug_ports u_ila_0/probe4]
connect_debug_port u_ila_0/probe4 [get_nets [list {cpu/control/io_i_inst[0]} {cpu/control/io_i_inst[1]} {cpu/control/io_i_inst[2]} {cpu/control/io_i_inst[3]} {cpu/control/io_i_inst[4]} {cpu/control/io_i_inst[5]} {cpu/control/io_i_inst[6]} {cpu/control/io_i_inst[7]} {cpu/control/io_i_inst[8]} {cpu/control/io_i_inst[9]} {cpu/control/io_i_inst[10]} {cpu/control/io_i_inst[11]} {cpu/control/io_i_inst[12]} {cpu/control/io_i_inst[13]} {cpu/control/io_i_inst[14]} {cpu/control/io_i_inst[15]} {cpu/control/io_i_inst[16]} {cpu/control/io_i_inst[17]} {cpu/control/io_i_inst[18]} {cpu/control/io_i_inst[19]} {cpu/control/io_i_inst[20]} {cpu/control/io_i_inst[21]} {cpu/control/io_i_inst[22]} {cpu/control/io_i_inst[23]} {cpu/control/io_i_inst[24]} {cpu/control/io_i_inst[25]} {cpu/control/io_i_inst[26]} {cpu/control/io_i_inst[27]} {cpu/control/io_i_inst[28]} {cpu/control/io_i_inst[29]} {cpu/control/io_i_inst[30]} {cpu/control/io_i_inst[31]}]]
set_property C_CLK_INPUT_FREQ_HZ 300000000 [get_debug_cores dbg_hub]
set_property C_ENABLE_CLK_DIVIDER false [get_debug_cores dbg_hub]
set_property C_USER_SCAN_CHAIN 1 [get_debug_cores dbg_hub]
connect_debug_port dbg_hub/clk [get_nets clock_IBUF_BUFG]
