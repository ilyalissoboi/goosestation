#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

# Builds the core's dependencies for Android (arm64-v8a, API 29) with the NDK. Versions and sources match
# build-dependencies-linux.sh. Libraries are static, except shaderc and SPIRV-Cross, which the core loads at run time,
# and SoundTouch's C wrapper, which is always shared. The APK packages them as unversioned shared libraries. See
# docs/android.md.

set -e
# CMake 4 refuses projects that declare compatibility below 3.5 (soundtouch, ...).
export CMAKE_POLICY_VERSION_MINIMUM=3.5

if [ "$1" == "-h" ] || [ "$1" == "--help" ]; then
	echo "Syntax: $0 [-skip-download] [-skip-cleanup] [<output directory>]"
	echo "The output directory defaults to ~/work/goosestation-deps-android. ANDROID_NDK_HOME must be set."
	exit 1
fi

for arg in "$@"; do
	if [ "$arg" == "-skip-download" ]; then
		echo "Not downloading sources."
		SKIP_DOWNLOAD=true
		shift
	elif [ "$arg" == "-skip-cleanup" ]; then
		echo "Not removing build directory."
		SKIP_CLEANUP=true
		shift
	fi
done

if [ -z "$ANDROID_NDK_HOME" ] || [ ! -f "$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" ]; then
	echo "ANDROID_NDK_HOME must point at an Android NDK (see docs/android.md)."
	exit 1
fi

INSTALLDIR="${1:-$HOME/work/goosestation-deps-android}"
if [ "${INSTALLDIR:0:1}" != "/" ]; then
	INSTALLDIR="$PWD/$INSTALLDIR"
fi

FREETYPE=2.13.3
HARFBUZZ=9.0.0
LIBJPEGTURBO=3.0.3
LIBPNG=1.6.43
LIBWEBP=1.4.0
ZSTD=1.5.6

CPUINFO=7524ad504fdcfcf75a18a133da6abd75c5d48053
LUNASVG=9af1ac7b90658a279b372add52d6f77a4ebb482c
SHADERC=f60bb80e255144e71776e2ad570d89b78ea2ab4f
SOUNDTOUCH=463ade388f3a51da078dc9ed062bf28e4ba29da7
SPIRV_CROSS=vulkan-sdk-1.3.290.0

# Keep pkg-config to the prefix, so no project picks up the host's (Homebrew's) libraries.
export PKG_CONFIG_LIBDIR="$INSTALLDIR/lib/pkgconfig"
unset PKG_CONFIG_PATH

# The NDK toolchain only searches its sysroot. Adding the prefix to the find root lets later libraries find earlier
# ones. PIC because the static libraries are linked into libgoosestation.so.
CMAKE_COMMON=(
	-DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
	-DANDROID_ABI=arm64-v8a
	-DANDROID_PLATFORM=29
	-DCMAKE_BUILD_TYPE=Release
	-DCMAKE_PREFIX_PATH="$INSTALLDIR"
	-DCMAKE_FIND_ROOT_PATH="$INSTALLDIR"
	-DCMAKE_INSTALL_PREFIX="$INSTALLDIR"
	-DCMAKE_POSITION_INDEPENDENT_CODE=ON
	-G Ninja
)

mkdir -p deps-build
cd deps-build

if [ "$SKIP_DOWNLOAD" != true ]; then
	curl -C - -L \
		-o "freetype-$FREETYPE.tar.xz" "https://sourceforge.net/projects/freetype/files/freetype2/$FREETYPE/freetype-$FREETYPE.tar.xz/download" \
		-o "harfbuzz-$HARFBUZZ.tar.gz" "https://github.com/harfbuzz/harfbuzz/archive/refs/tags/$HARFBUZZ.tar.gz" \
		-O "https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/$LIBJPEGTURBO/libjpeg-turbo-$LIBJPEGTURBO.tar.gz" \
		-O "https://downloads.sourceforge.net/project/libpng/libpng16/$LIBPNG/libpng-$LIBPNG.tar.xz" \
		-O "https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-$LIBWEBP.tar.gz" \
		-O "https://github.com/facebook/zstd/releases/download/v$ZSTD/zstd-$ZSTD.tar.gz" \
		-o "cpuinfo-$CPUINFO.tar.gz" "https://github.com/stenzek/cpuinfo/archive/$CPUINFO.tar.gz" \
		-o "lunasvg-$LUNASVG.tar.gz" "https://github.com/sammycage/lunasvg/archive/$LUNASVG.tar.gz" \
		-o "shaderc-$SHADERC.tar.gz" "https://github.com/stenzek/shaderc/archive/$SHADERC.tar.gz" \
		-o "soundtouch-$SOUNDTOUCH.tar.gz" "https://github.com/stenzek/soundtouch/archive/$SOUNDTOUCH.tar.gz"
fi

cat > SHASUMS <<EOF
0550350666d427c74daeb85d5ac7bb353acba5f76956395995311a9c6f063289  freetype-$FREETYPE.tar.xz
b7e481b109d19aefdba31e9f5888aa0cdfbe7608fed9a43494c060ce1f8a34d2  harfbuzz-$HARFBUZZ.tar.gz
343e789069fc7afbcdfe44dbba7dbbf45afa98a15150e079a38e60e44578865d  libjpeg-turbo-$LIBJPEGTURBO.tar.gz
6a5ca0652392a2d7c9db2ae5b40210843c0bbc081cbd410825ab00cc59f14a6c  libpng-$LIBPNG.tar.xz
61f873ec69e3be1b99535634340d5bde750b2e4447caa1db9f61be3fd49ab1e5  libwebp-$LIBWEBP.tar.gz
8c29e06cf42aacc1eafc4077ae2ec6c6fcb96a626157e0593d5e82a34fd403c1  zstd-$ZSTD.tar.gz
e1351218d270db49c3dddcba04fb2153b09731ea3fa6830e423f5952f44585be  cpuinfo-$CPUINFO.tar.gz
3998b024b0d442614a9ee270e76e018bb37a17b8c6941212171731123cbbcac7  lunasvg-$LUNASVG.tar.gz
4c1780b6c65c27c4dcb109f08ab632241c98b77fe2e22be726c151ff514482bf  shaderc-$SHADERC.tar.gz
fe45c2af99f6102d2704277d392c1c83b55180a70bfd17fb888cc84a54b70573  soundtouch-$SOUNDTOUCH.tar.gz
EOF

shasum -a 256 --check SHASUMS

# Have to clone with git, because it does version detection.
if [ "$SKIP_DOWNLOAD" != true ]; then
	if [ ! -d "SPIRV-Cross" ]; then
		git clone https://github.com/KhronosGroup/SPIRV-Cross/ -b $SPIRV_CROSS --depth 1
	fi
fi

# zlib comes from the NDK sysroot.
echo "Building libpng..."
rm -fr "libpng-$LIBPNG"
tar xf "libpng-$LIBPNG.tar.xz"
cd "libpng-$LIBPNG"
cmake "${CMAKE_COMMON[@]}" -DBUILD_SHARED_LIBS=OFF -DPNG_TESTS=OFF -DPNG_STATIC=ON -DPNG_SHARED=OFF -DPNG_TOOLS=OFF -B build
cmake --build build --parallel
ninja -C build install
cd ..

echo "Building libjpeg..."
rm -fr "libjpeg-turbo-$LIBJPEGTURBO"
tar xf "libjpeg-turbo-$LIBJPEGTURBO.tar.gz"
cd "libjpeg-turbo-$LIBJPEGTURBO"
cmake "${CMAKE_COMMON[@]}" -DENABLE_STATIC=ON -DENABLE_SHARED=OFF -B build
cmake --build build --parallel
ninja -C build install
cd ..

echo "Building Zstandard..."
rm -fr "zstd-$ZSTD"
tar xf "zstd-$ZSTD.tar.gz"
cd "zstd-$ZSTD"
cmake "${CMAKE_COMMON[@]}" -DBUILD_SHARED_LIBS=OFF -DZSTD_BUILD_SHARED=OFF -DZSTD_BUILD_STATIC=ON -DZSTD_BUILD_PROGRAMS=OFF -B build build/cmake
cmake --build build --parallel
ninja -C build install
cd ..

echo "Building WebP..."
rm -fr "libwebp-$LIBWEBP"
tar xf "libwebp-$LIBWEBP.tar.gz"
cd "libwebp-$LIBWEBP"
cmake "${CMAKE_COMMON[@]}" -B build \
	-DWEBP_BUILD_ANIM_UTILS=OFF -DWEBP_BUILD_CWEBP=OFF -DWEBP_BUILD_DWEBP=OFF -DWEBP_BUILD_GIF2WEBP=OFF -DWEBP_BUILD_IMG2WEBP=OFF \
	-DWEBP_BUILD_VWEBP=OFF -DWEBP_BUILD_WEBPINFO=OFF -DWEBP_BUILD_WEBPMUX=OFF -DWEBP_BUILD_EXTRAS=OFF -DBUILD_SHARED_LIBS=OFF
cmake --build build --parallel
ninja -C build install
cd ..

echo "Building FreeType without HarfBuzz..."
rm -fr "freetype-$FREETYPE"
tar xf "freetype-$FREETYPE.tar.xz"
cd "freetype-$FREETYPE"
cmake "${CMAKE_COMMON[@]}" -DBUILD_SHARED_LIBS=OFF -DFT_REQUIRE_ZLIB=ON -DFT_REQUIRE_PNG=ON -DFT_DISABLE_BZIP2=TRUE -DFT_DISABLE_BROTLI=TRUE -DFT_DISABLE_HARFBUZZ=TRUE -B build
cmake --build build --parallel
ninja -C build install
cd ..

echo "Building HarfBuzz..."
rm -fr "harfbuzz-$HARFBUZZ"
tar xf "harfbuzz-$HARFBUZZ.tar.gz"
cd "harfbuzz-$HARFBUZZ"
cmake "${CMAKE_COMMON[@]}" -DBUILD_SHARED_LIBS=OFF -DHB_BUILD_UTILS=OFF -B build
cmake --build build --parallel
ninja -C build install
cd ..

echo "Building FreeType with HarfBuzz..."
rm -fr "freetype-$FREETYPE"
tar xf "freetype-$FREETYPE.tar.xz"
cd "freetype-$FREETYPE"
cmake "${CMAKE_COMMON[@]}" -DBUILD_SHARED_LIBS=OFF -DFT_REQUIRE_ZLIB=ON -DFT_REQUIRE_PNG=ON -DFT_DISABLE_BZIP2=TRUE -DFT_DISABLE_BROTLI=TRUE -DFT_REQUIRE_HARFBUZZ=TRUE -B build
cmake --build build --parallel
ninja -C build install
cd ..

# The NDK's CMake platform drops SOVERSION, so the shared libraries install unversioned, as APKs require.
echo "Building shaderc..."
rm -fr "shaderc-$SHADERC"
tar xf "shaderc-$SHADERC.tar.gz"
cd "shaderc-$SHADERC"
cmake "${CMAKE_COMMON[@]}" -DSHADERC_SKIP_TESTS=ON -DSHADERC_SKIP_EXAMPLES=ON -DSHADERC_SKIP_COPYRIGHT_CHECK=ON -B build
cmake --build build --parallel
ninja -C build install
cd ..

echo "Building SPIRV-Cross..."
cd SPIRV-Cross
rm -fr build
cmake "${CMAKE_COMMON[@]}" -DSPIRV_CROSS_SHARED=ON -DSPIRV_CROSS_STATIC=OFF -DSPIRV_CROSS_CLI=OFF -DSPIRV_CROSS_ENABLE_TESTS=OFF -DSPIRV_CROSS_ENABLE_GLSL=ON -DSPIRV_CROSS_ENABLE_HLSL=OFF -DSPIRV_CROSS_ENABLE_MSL=OFF -DSPIRV_CROSS_ENABLE_CPP=OFF -DSPIRV_CROSS_ENABLE_REFLECT=OFF -DSPIRV_CROSS_ENABLE_C_API=ON -DSPIRV_CROSS_ENABLE_UTIL=ON -B build
cmake --build build --parallel
ninja -C build install
cd ..

echo "Building cpuinfo..."
rm -fr "cpuinfo-$CPUINFO"
tar xf "cpuinfo-$CPUINFO.tar.gz"
cd "cpuinfo-$CPUINFO"
cmake "${CMAKE_COMMON[@]}" -DCPUINFO_LIBRARY_TYPE=static -DCPUINFO_RUNTIME_TYPE=shared -DCPUINFO_LOG_LEVEL=error -DCPUINFO_LOG_TO_STDIO=ON -DCPUINFO_BUILD_TOOLS=OFF -DCPUINFO_BUILD_UNIT_TESTS=OFF -DCPUINFO_BUILD_MOCK_TESTS=OFF -DCPUINFO_BUILD_BENCHMARKS=OFF -DUSE_SYSTEM_LIBS=ON -B build
cmake --build build --parallel
ninja -C build install
cd ..

echo "Building lunasvg..."
rm -fr "lunasvg-$LUNASVG"
tar xf "lunasvg-$LUNASVG.tar.gz"
cd "lunasvg-$LUNASVG"
cmake "${CMAKE_COMMON[@]}" -DBUILD_SHARED_LIBS=OFF -DLUNASVG_BUILD_EXAMPLES=OFF -B build
cmake --build build --parallel
ninja -C build install
cd ..

echo "Building soundtouch..."
rm -fr "soundtouch-$SOUNDTOUCH"
tar xf "soundtouch-$SOUNDTOUCH.tar.gz"
cd "soundtouch-$SOUNDTOUCH"
cmake "${CMAKE_COMMON[@]}" -DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON -B build
cmake --build build --parallel
ninja -C build install
cd ..

if [ "$SKIP_CLEANUP" != true ]; then
	echo "Cleaning up..."
	cd ..
	rm -fr deps-build
fi
