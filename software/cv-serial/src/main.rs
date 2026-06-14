use std::io::{Read, Write};
use std::thread::sleep;
use std::time::Duration;

// Control byte: 0b100X_XXXY  (X = write mask, Y = 0 read / 1 write)
// Address/data are sent little-endian (LSB first), 4 bytes each.

/// Encode a read: control byte + 4 address bytes (LSB first).
fn encode_read(addr: u32) -> Vec<u8> {
    let mut out = vec![0b1000_0000]; // mask=0, we=0
    out.extend_from_slice(&addr.to_le_bytes());
    out
}

/// Encode a write: control byte + 4 address bytes + 4 data bytes (LSB first).
fn encode_write(addr: u32, data: u32, mask: u8) -> Vec<u8> {
    let ctrl = 0b1000_0000 | ((mask & 0xF) << 1) | 1; // we=1
    let mut out = vec![ctrl];
    out.extend_from_slice(&addr.to_le_bytes());
    out.extend_from_slice(&data.to_le_bytes());
    out
}

/// Parse a hex string, with or without a leading "0x".
fn parse_hex(s: &str) -> Result<u32, std::num::ParseIntError> {
    let s = s.trim_start_matches("0x").trim_start_matches("0X");
    u32::from_str_radix(s, 16)
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 || args.len() > 3 {
        eprintln!("usage: {} <addr_hex> [data_hex]", args[0]);
        eprintln!("  no data => read op, data given => write op (mask always 0xf)");
        std::process::exit(1);
    }

    let addr = parse_hex(&args[1]).expect("invalid address hex");
    let data = args.get(2).map(|s| parse_hex(s).expect("invalid data hex"));

    let mut port = serialport::new("/dev/ttyUSB1", 9600)
        .timeout(Duration::from_secs(2))
        .open()
        .expect("failed to open serial port");

    let request = match data {
        Some(data) => encode_write(addr, data, 0xF),
        None => encode_read(addr),
    };
    port.write_all(&request).expect("write failed");

    // Response: status byte (0b0000_00XY) then 4 data bytes (LSB first) on success.
    let mut status = [0u8; 1];
    port.read_exact(&mut status).expect("read status failed");
    println!("status: {:#010b}", status[0]);

    if status[0] & 1 == 0 {
        if (status[0] >> 1) & 1 == 0 {
            let mut data = [0u8; 4];
            port.read_exact(&mut data).expect("read data failed");
            println!("data: {:#010x}", u32::from_le_bytes(data));
        }
    } else {
        println!("error response");
    }
}
