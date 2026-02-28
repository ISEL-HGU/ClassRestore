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

# 2. 패치 주입 (하위 호환성을 위해 MODE 플래그 감지)
# The original script passed "${@:4}" which includes all arguments from the 4th onwards.
# The new snippet explicitly checks for 5 arguments and passes $4 and $5.
# If less than 5, it assumes the 4th argument is REF_CLASS.
# We need to ensure $4 is correctly passed as REF_CLASS if it exists.
# Let's align with the new snippet's logic.
if [ "$#" -ge 5 ]; then
    java -cp "$CP" ClassRestore "$TARGET_CLASS" "$PATCH_HEX" "$OUT_CLASS" "$4" "$5"
else
    # 기존 방식 (Ref Class)
    # If $4 is not empty, it's the REF_CLASS
    REF_CLASS="${@:4}" # This will be empty if no 4th arg, or contain the 4th arg.
    java -cp "$CP" ClassRestore "$TARGET_CLASS" "$PATCH_HEX" "$OUT_CLASS" "$REF_CLASS"
fi
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
