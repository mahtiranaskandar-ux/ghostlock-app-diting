#!/bin/sh
# Build the DirtyFrag payloads into the app's assets: two init stubs and the
# crash_dump bridge.
#
# The two stubs come from one source, split by -DDIRTYFRAG_MODULE:
#   dirtyfrag_stub.bin         plain (socket + shell) — the DirtyFrag pipeline
#   dirtyfrag_stub_module.bin  the plain path plus the vendor_file insmod
#                              prologue — the module chain only
# Each plan writes only the stub it will run, and the cave preflight sizes that
# same blob, so a device is measured against the code it actually gets.
#
# The stub is assembled and then *linked at address 0*: its adr instructions
# are PC-relative relocations, and extracting .text from the object (without
# linking) leaves them unresolved (each adr points at itself). The linker
# resolves them; the section content is base-invariant, so the blob works at
# any cave address. The bridge is a freestanding C binary; the clang driver
# links it, so its relocations are applied.
#
# The Kotlin side patches three places by offset: the socket name (shared), the
# stub's entry (per blob) and the ko target (module blob only). The entry offset
# is the one hand-kept number; this script compares it with the linked symbol
# and fails on drift, and checks that the two patched tail words really are the
# blob's last eight bytes (the app derives them from the size).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ASSETS=$HERE/../../app/src/main/assets
PLAN=$HERE/../../app/src/main/kotlin/com/sesame/app/data/DirtyfragPlan.kt
NDK=${NDK:-/home/haoyu/Android/ondk/r30.1}
BIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
OUT=${TMPDIR:-/tmp}/dirtyfrag-payload
mkdir -p "$OUT"

plain_code=$(sed -n 's/.*PLAIN_STUB_CODE_OFFSET = 0x\([0-9a-fA-F]*\).*/\1/p' "$PLAN")
module_code=$(sed -n 's/.*MODULE_STUB_CODE_OFFSET = 0x\([0-9a-fA-F]*\).*/\1/p' "$PLAN")
if [ -z "$plain_code" ] || [ -z "$module_code" ]; then
    echo "build.sh: cannot read PLAIN/MODULE_STUB_CODE_OFFSET from $PLAN" >&2
    exit 1
fi

build_stub() { # <label> <extra cflags> <asset> <expected entry hex>
    label=$1
    cflags=$2
    asset=$3
    want=$4
    # shellcheck disable=SC2086
    "$BIN/aarch64-linux-android35-clang" $cflags -c "$HERE/stub.S" -o "$OUT/stub-$label.o"
    "$BIN/ld.lld" -Ttext=0 -e stub_data "$OUT/stub-$label.o" -o "$OUT/stub-$label.elf"
    "$BIN/llvm-objcopy" -O binary --only-section=.text "$OUT/stub-$label.elf" "$ASSETS/$asset"
    code=$("$BIN/llvm-nm" "$OUT/stub-$label.elf" | awk '$3=="stub_code"{print $1}')
    first=$("$BIN/llvm-nm" "$OUT/stub-$label.elf" | awk '$3=="stub_first_inst"{print $1}')
    end=$("$BIN/llvm-nm" "$OUT/stub-$label.elf" | awk '$3=="stub_end"{print $1}')
    size=$(stat -c%s "$ASSETS/$asset")
    if [ $((0x$code)) -ne $((0x$want)) ]; then
        echo "build.sh: $label stub_code is 0x$code, Kotlin expects 0x$want" >&2
        exit 1
    fi
    if [ $((0x$first)) -ne $((size - 8)) ] || [ $((0x$end)) -ne "$size" ]; then
        echo "build.sh: $label tail is not the blob's last 8 bytes" \
            "(first=0x$first end=0x$end size=$size)" >&2
        exit 1
    fi
    printf '%s: size=%s entry=0x%s first_inst=0x%s\n' "$label" "$size" "$code" "$first"
}

build_stub plain "" dirtyfrag_stub.bin "$plain_code"
build_stub module "-DDIRTYFRAG_MODULE" dirtyfrag_stub_module.bin "$module_code"

"$BIN/aarch64-linux-android35-clang" --target=aarch64-linux-android24 \
    -nodefaultlibs -nostartfiles -ffreestanding -static -O2 -fno-stack-protector \
    -o "$ASSETS/dirtyfrag_bridge.bin" "$HERE/bridge.c"

ls -l "$ASSETS/dirtyfrag_stub.bin" "$ASSETS/dirtyfrag_stub_module.bin" \
    "$ASSETS/dirtyfrag_bridge.bin"
