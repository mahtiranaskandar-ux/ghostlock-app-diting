#!/bin/sh
# Build the DirtyFrag SELinux switch module for one kernel Image.
#
#   sh build.sh <ack-tree> <image-config> <Module.symvers> <localversion> <outdir>
#
# <ack-tree>       a clean ACK/common tree at the Image's version
# <image-config>   the Image's own config text: its IKCONFIG blob for a 5.4
#                  Image, /proc/config.gz for a GKI build. The script rewrites
#                  CONFIG_LOCALVERSION, forces CONFIG_LOCALVERSION_AUTO off
#                  (so a dirty tree cannot change the vermagic), and, when
#                  KSYMS_WHITELIST is set, repoints
#                  CONFIG_UNUSED_KSYMS_WHITELIST.
# <Module.symvers> the Image's export CRCs, from five-four-tools'
#                  symvers-from-image.py. A module must carry the CRC of every
#                  symbol it imports; a wrong table is refused by the loader
#                  (`disagrees about version of symbol`). The 5.4 Images import
#                  module_layout, printk, register_kprobe, unregister_kprobe and
#                  __ubsan_handle_cfi_check_fail_abort; the diting 5.10 imports
#                  the first four only (the ubsan symbol is trimmed there).
# <localversion>   the release suffix: -qgki-g<commit> for a 5.4 Image, or
#                  -android12-9-00003-g<commit> for GKI (BUILD_NUMBER then
#                  appends -ab<number>). For a GKI tree prefer the
#                  LOCALVERSION environment (below) and pass an empty value:
#                  the common tree's setlocalversion appends a "+" unless the
#                  environment variable is set, and concatenates the two.
# <outdir>         receives dfswitch.ko (debug-stripped)
#
# Optional environment:
#   LOCALVERSION     the release suffix as the kernel's own env variable. When
#                    set it wins: CONFIG_LOCALVERSION is forced empty and
#                    setlocalversion prints no "+" (the diting build uses
#                    -android12-9-00003-gfb24cf99ad97).
#   CLANG_DIR        a clang install to use instead of ONDK; the diting build
#                    uses external/diting-mcast/clang-r416183b, the stock
#                    kernel's own compiler (12.0.5).
#   BUILD_NUMBER     GKI build number; the common tree appends -ab$BUILD_NUMBER
#                    to UTS_RELEASE (e.g. 14313284).
#   KSYMS_WHITELIST  the stock KMI whitelist. The diting config carries an
#                    absolute Google build-host path in
#                    CONFIG_UNUSED_KSYMS_WHITELIST and has
#                    CONFIG_TRIM_UNUSED_KSYMS=y, so it must be repointed.
#
# ONDK r30.1's clang (21.0.0) is the 5.4 Images' vendor compiler; source
# env.sh or set ANDROID_NDK_HOME when CLANG_DIR is unset.
#
# The output is functionally deterministic but not bit-identical: ld.lld's
# --build-id is path-dependent, so a rebuild from another directory has a
# different hash. That is why the profile's asset_sha256 must be updated when
# regenerating a bundled pair.
set -eu

if [ $# -ne 5 ]; then
    echo "usage: $0 <ack-tree> <image-config> <Module.symvers> <localversion> <outdir>" >&2
    exit 2
fi

TREE=$1
CONFIG=$2
SYMVERS=$3
LOCALVERSION_VALUE=$4
OUT=$5

if [ -n "${CLANG_DIR:-}" ]; then
    [ -x "$CLANG_DIR/bin/clang" ] || {
        echo "no clang under $CLANG_DIR/bin" >&2
        exit 2
    }
    NDK_BIN=$CLANG_DIR/bin
else
    : "${ANDROID_NDK_HOME:?set CLANG_DIR, or source env.sh / set ANDROID_NDK_HOME}"
    NDK_BIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin
fi
PATH=$NDK_BIN:$PATH
export PATH
[ -n "${BUILD_NUMBER:-}" ] && export BUILD_NUMBER

HERE=$(cd "$(dirname "$0")" && pwd)
BUILD=$OUT/build
SRC=$OUT/src
mkdir -p "$BUILD" "$SRC" "$OUT"
cp "$HERE/dfswitch.c" "$HERE/Makefile" "$SRC/"

cp "$CONFIG" "$BUILD/.config"
if [ -n "${LOCALVERSION:-}" ]; then
    # GKI: the env variable is the release suffix (and suppresses the "+").
    export LOCALVERSION
    sed -i 's|^CONFIG_LOCALVERSION=.*|CONFIG_LOCALVERSION=""|' "$BUILD/.config"
else
    sed -i "s|^CONFIG_LOCALVERSION=.*|CONFIG_LOCALVERSION=\"$LOCALVERSION_VALUE\"|" \
        "$BUILD/.config"
fi
sed -i 's|^CONFIG_LOCALVERSION_AUTO=.*|# CONFIG_LOCALVERSION_AUTO is not set|' "$BUILD/.config"
grep -q '^# CONFIG_LOCALVERSION_AUTO is not set' "$BUILD/.config" ||
    echo '# CONFIG_LOCALVERSION_AUTO is not set' >>"$BUILD/.config"
if [ -n "${KSYMS_WHITELIST:-}" ]; then
    sed -i "s|^CONFIG_UNUSED_KSYMS_WHITELIST=.*|CONFIG_UNUSED_KSYMS_WHITELIST=\"$KSYMS_WHITELIST\"|" \
        "$BUILD/.config"
    grep -q '^CONFIG_UNUSED_KSYMS_WHITELIST=' "$BUILD/.config" ||
        echo "warning: the config has no CONFIG_UNUSED_KSYMS_WHITELIST to repoint" >&2
fi

make -C "$TREE" O="$BUILD" ARCH=arm64 LLVM=1 LLVM_IAS=1 \
    CROSS_COMPILE=aarch64-linux-gnu- olddefconfig modules_prepare
make -C "$TREE" O="$BUILD" ARCH=arm64 LLVM=1 LLVM_IAS=1 \
    CROSS_COMPILE=aarch64-linux-gnu- M="$SRC" \
    KBUILD_EXTRA_SYMBOLS="$SYMVERS" modules

llvm-strip --strip-debug -o "$OUT/dfswitch.ko" "$SRC/dfswitch.ko"
# The loader does not need the build id; the hash binds the exact bytes.
modinfo "$OUT/dfswitch.ko" | grep -E '^(filename|vermagic|name):'
