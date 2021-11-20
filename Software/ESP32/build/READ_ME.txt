For ESP32 the flash must be written as follows:
erase range: 
0x8000 to 0x8FFF
0x1000 to 0x7FFF
0x10000 to 0xABFFF

0x8000: partition-table.bin
0x1000: bootloader.bin
0x10000: bt_remote.bin
