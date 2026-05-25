package cpu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ControlUnitSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "ControlUnit"

  it should "produce correct control signals for lw" in {
    // lw x1, 0(x2)
    //   imm[11:0]=0  rs1=2  funct3=010  rd=1  opcode=0000011
    simulate(new ControlUnit) { dut =>
      dut.io.i_inst.poke("h00012083".U)
      dut.io.i_zero.poke(false.B)
      dut.clock.step()

      dut.io.control.result_src.expect(true.B)
      dut.io.control.mem_write.expect(false.B)
      dut.io.control.alu_src.expect(true.B)
      dut.io.control.imm_src.expect(0.U)
      dut.io.control.reg_write.expect(true.B)
      dut.io.control.alu_op.expect("b000".U) // add (address calc)
    }
  }

  it should "produce correct control signals for sw" in {
    // sw x1, 0(x2)
    //   imm[11:5]=0  rs2=1  rs1=2  funct3=010  imm[4:0]=0  opcode=0100011
    simulate(new ControlUnit) { dut =>
      dut.io.i_inst.poke("h00112023".U)
      dut.io.i_zero.poke(false.B)
      dut.clock.step()

      dut.io.control.mem_write.expect(true.B)
      dut.io.control.alu_src.expect(true.B)
      dut.io.control.imm_src.expect(1.U)
      dut.io.control.reg_write.expect(false.B)
      dut.io.control.alu_op.expect("b000".U) // add (address calc)
    }
  }

  it should "produce correct control signals for or" in {
    // or x3, x1, x2
    //   funct7=0000000  rs2=2  rs1=1  funct3=110  rd=3  opcode=0110011
    simulate(new ControlUnit) { dut =>
      dut.io.i_inst.poke("h0020E1B3".U)
      dut.io.i_zero.poke(false.B)
      dut.clock.step()

      dut.io.control.result_src.expect(false.B)
      dut.io.control.mem_write.expect(false.B)
      dut.io.control.alu_src.expect(false.B)
      dut.io.control.reg_write.expect(true.B)
      dut.io.control.alu_op.expect("b011".U) // or
    }
  }

  it should "produce correct control signals for beq" in {
    // beq x1, x2, 0
    //   imm[12|10:5]=0  rs2=2  rs1=1  funct3=000  imm[4:1|11]=0  opcode=1100011
    simulate(new ControlUnit) { dut =>
      dut.io.i_inst.poke("h00208063".U)
      dut.io.i_zero.poke(false.B)
      dut.clock.step()

      dut.io.control.mem_write.expect(false.B)
      dut.io.control.alu_src.expect(false.B)
      dut.io.control.imm_src.expect(3.U)
      dut.io.control.reg_write.expect(false.B)
      dut.io.control.alu_op.expect("b001".U) // sub (for compare)
    }
  }
}
