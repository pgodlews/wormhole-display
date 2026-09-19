cmake_minimum_required(VERSION 3.22.1)
project(wormhole C)

set(UX ${CMAKE_CURRENT_SOURCE_DIR}/uxplay/lib)
set(DEPS ${CMAKE_CURRENT_SOURCE_DIR}/deps)

# Keep absolute build-machine paths out of __FILE__ strings and debug info in shipped binaries.
add_compile_options(-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.)

file(GLOB UX_SOURCES CONFIGURE_DEPENDS ${UX}/*.c ${UX}/llhttp/*.c ${UX}/playfair/*.c ${UX}/mdnsd/*.c)

# UxPlay mirroring core (GPLv3/LGPLv2.1+ — see uxplay/LICENSE.GPLv3).
# Discovery uses the bundled internal mDNS responder (mdnsd); the dns_sd
# (Bonjour/Avahi) backend is intentionally absent.
add_library(mirror-core STATIC ${UX_SOURCES})
target_include_directories(mirror-core PUBLIC
    ${UX} ${UX}/llhttp ${UX}/playfair ${UX}/mdnsd
    ${DEPS}/openssl/include ${DEPS}/plist/include)
target_compile_definitions(mirror-core PRIVATE
    STANDALONE NOHOLD TARGET_POSIX _REENTRANT
    __STDC_CONSTANT_MACROS __STDC_LIMIT_MACROS
    _LARGEFILE64_SOURCE _FILE_OFFSET_BITS=64
    PLIST_210 PLIST_230
    OPENSSL_API_COMPAT=0x10101000L)
target_compile_options(mirror-core PRIVATE -w)

# Clean-room ALAC decoder (GPLv3, part of this project): Portal has no MediaCodec ALAC.
add_library(alac-decoder STATIC ${CMAKE_CURRENT_SOURCE_DIR}/alac/alac_decoder.c)
target_include_directories(alac-decoder PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/alac)

add_library(wormhole SHARED wormhole_jni.c)
target_link_libraries(wormhole
    alac-decoder
    mirror-core
    ${DEPS}/plist/lib/${ANDROID_ABI}/libplist-2.0.a
    ${DEPS}/openssl/lib/${ANDROID_ABI}/libcrypto.a
    log)
