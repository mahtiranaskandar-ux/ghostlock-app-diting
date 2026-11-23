#!/bin/sh
# Build the two DirtyFrag payloads into the app's assets.
#
# The stub is assembled and then *linked at address 0*: its adr instructions
# are PC-relative relocations, and extracting .text from the object (without
# linking) leaves them unresolved (each adr points at itself). The linker
# resolves them; the section content is base-invariant, so the blob works at
# any cave address. The bridge is a freestanding C binary; the clang driver
# links it, so its relocations are applied.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ASSETS=$HERE/../../app/src/main/assets
NDK=${NDK:-/home/haoyu/Android/ondk/r30.1}
BIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
OUT=${TMPDIR:-/tmp}/dirtyfrag-payload
mkdir -p "$OUT"

"$BIN/aarch64-linux-android35-clang" -c "$HERE/stub.S" -o "$OUT/stub.o"
"$BIN/ld.lld" -Ttext=0 -e stub_data "$OUT/stub.o" -o "$OUT/stub.elf"
"$BIN/llvm-objcopy" -O binary --only-section=.text "$OUT/stub.elf" "$ASSETS/dirtyfrag_stub.bin"

"$BIN/aarch64-linux-android35-clang" --target=aarch64-linux-android24 \
    -nodefaultlibs -nostartfiles -ffreestanding -static -O2 -fno-stack-protector \
    -o "$ASSETS/dirtyfrag_bridge.bin" "$HERE/bridge.c"

ls -l "$ASSETS/dirtyfrag_stub.bin" "$ASSETS/dirtyfrag_bridge.bin"
