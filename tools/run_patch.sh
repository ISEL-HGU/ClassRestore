#!/bin/bash
# Usage: ./run_patch.sh <OriginalClass> <PatchedHexFile> <OutputClassName> <OriginalHexFile>

ORIGINAL_CLASS=$1
PATCHED_HEX=$2
OUTPUT_FILENAME=$3
ORIGINAL_HEX=$4

# Check arguments
if [ -z "$ORIGINAL_CLASS" ] || [ -z "$PATCHED_HEX" ] || [ -z "$OUTPUT_FILENAME" ] || [ -z "$ORIGINAL_HEX" ]; then
    echo "Usage: $0 <OriginalClass> <PatchedHexFile> <OutputClassName> <OriginalHexFile>"
    echo "  OutputClassName: The filename of the patched class (will be saved to ../output/)"
    exit 1
fi

# Define and create output directory
PROJECT_ROOT="$(dirname "$(pwd)")"
OUTPUT_DIR="../output"
mkdir -p "$OUTPUT_DIR"
OUTPUT_PATH="$OUTPUT_DIR/$OUTPUT_FILENAME"

# Map original variables to new ones for clarity in the new section
TARGET_CLASS="$ORIGINAL_CLASS"
PATCH_HEX="$PATCHED_HEX"
OUT_CLASS="$OUTPUT_PATH" # Note: OUTPUT_PATH is the full path, not just the filename
CP=".:javassist.jar:asm.jar:libs/javassist.jar:libs/asm.jar" # Renamed CLASSPATH to CP

# 1. 컴파일 (필요시)
if [ ! -f "ClassRestore.class" ] || [ "ClassRestore.java" -nt "ClassRestore.class" ]; then
    echo "Compiling ClassRestore..."
    javac -cp "$CP" ClassRestore.java
fi

# 2. 패치 주입 (차분 패치 모드 고정)
java -cp "$CP" ClassRestore "$TARGET_CLASS" "$PATCH_HEX" "$OUT_CLASS" "$ORIGINAL_HEX"
EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
    # Verify the output class integrity using javap
    echo "Verifying output class integrity..."
    javap -p "$OUTPUT_PATH" > /dev/null 2>&1
    JAVAP_EXIT_CODE=$?
    
    if [ $JAVAP_EXIT_CODE -eq 0 ]; then
        echo "[VERIFIED] Class file is valid and structurally correct."
        exit 0
    else
        echo "[ERROR] Class file created but is CORRUPTED or INVALID (javap failed)."
        exit 1
    fi
else
    echo "[ABORTED] Patch tool failed."
    exit $EXIT_CODE
fi
