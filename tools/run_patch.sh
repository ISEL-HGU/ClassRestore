#!/bin/bash
# Usage: ./run_patch.sh <OriginalClass> <PatchedHexFile> <OutputClassName> [--ref ReferenceClass | --diff OriginalHexFile]

ORIGINAL_CLASS=$1
PATCHED_HEX=$2
OUTPUT_FILENAME=$3

# Check arguments
if [ -z "$ORIGINAL_CLASS" ] || [ -z "$PATCHED_HEX" ] || [ -z "$OUTPUT_FILENAME" ]; then
    echo "Usage: $0 <OriginalClass> <PatchedHexFile> <OutputClassName> [--ref ReferenceClass | --diff OriginalHexFile]"
    echo "  OutputClassName: The filename of the patched class (will be saved to ../output/)"
    exit 1
fi

# Define and create output directory
PROJECT_ROOT="$(dirname "$(pwd)")"
OUTPUT_DIR="../output"
mkdir -p "$OUTPUT_DIR"
OUTPUT_PATH="$OUTPUT_DIR/$OUTPUT_FILENAME"

# Run the patch tool
# Assuming libs are in ./libs or current directory
CLASSPATH=".:javassist.jar:asm.jar:libs/javassist.jar:libs/asm.jar"

java -cp "$CLASSPATH" PatchWithJavassist "$ORIGINAL_CLASS" "$PATCHED_HEX" "$OUTPUT_PATH" "${@:4}"
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
