import sys
import struct

def extract_method_hex(class_file, method_index, output_file):
    with open(class_file, 'rb') as f:
        data = f.read()

    # Minimal parser logic to find method boundaries
    offset = 8 # Magic + Versions
    
    # CP
    cp_count = struct.unpack('>H', data[offset:offset+2])[0]
    offset += 2
    
    i = 1
    while i < cp_count:
        tag = data[offset]
        offset += 1
        if tag == 1: # Utf8
            length = struct.unpack('>H', data[offset:offset+2])[0]
            offset += 2 + length
        elif tag in [3, 4]: # Int, Float
            offset += 4
        elif tag in [5, 6]: # Long, Double
            offset += 8
            i += 1
        elif tag in [7, 8, 16, 19, 20]: # Class, String, MType, Module, Package
            offset += 2
        elif tag in [9, 10, 11, 12, 17, 18]: # Ref, NameType, Dynamic
            offset += 4
        elif tag == 15: # MHandle
            offset += 3
        i += 1
        
    offset += 6 # Access, This, Super
    
    # Interfaces
    if_count = struct.unpack('>H', data[offset:offset+2])[0]
    offset += 2 + 2 * if_count
    
    # Fields
    field_count = struct.unpack('>H', data[offset:offset+2])[0]
    offset += 2
    for _ in range(field_count):
        offset += 6
        attr_count = struct.unpack('>H', data[offset:offset+2])[0]
        offset += 2
        for _ in range(attr_count):
            offset += 2
            attr_len = struct.unpack('>I', data[offset:offset+4])[0]
            offset += 4 + attr_len

    # Methods
    method_count = struct.unpack('>H', data[offset:offset+2])[0]
    offset += 2
    
    print(f"Found {method_count} methods")
    
    target_start = 0
    target_end = 0
    
    for i in range(method_count):
        start = offset
        offset += 6 # Access, Name, Desc
        attr_count = struct.unpack('>H', data[offset:offset+2])[0]
        offset += 2
        for _ in range(attr_count):
            offset += 2
            attr_len = struct.unpack('>I', data[offset:offset+4])[0]
            offset += 4 + attr_len
        end = offset
        
        if i == method_index:
            target_start = start
            target_end = end
            break
            
    if target_start == 0:
        print("Method not found")
        return

    method_bytes = data[target_start:target_end]
    hex_string = method_bytes.hex()
    
    with open(output_file, 'w') as f:
        f.write(hex_string)
    
    print(f"Extracted method {method_index} to {output_file}")

if __name__ == "__main__":
    extract_method_hex(sys.argv[1], int(sys.argv[2]), sys.argv[3])
