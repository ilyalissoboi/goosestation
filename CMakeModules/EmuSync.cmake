# Memory card sync (src/core/memcard_sync.h) with emusync (https://github.com/ilyalissoboi/emusync).
# The core loads the library at run time and builds against the vendored header (dep/emusync), so
# this only puts the library next to the program, from a pinned release (no Rust needed):
#   - Linux x86_64 and macOS: downloads emusync-<version>-<platform>.tar.gz into the build folder and
#     checks its SHA-256 (from the release's SHA256SUMS); emusync_deploy_runtime(<target>) copies the
#     library next to the program, or into the app bundle's Frameworks on macOS.
#   - Windows: dep/emusync/emusync.props (MSBuild); a CMake build on Windows runs without sync.
# Anything else, -DUSE_EMUSYNC=OFF, or a download that fails: the program runs without sync.
# -DEMUSYNC_LIBRARY=<file> uses a library built from emusync's source instead.
# Keep EMUSYNC_VERSION in step with dep/emusync/emusync.props and dep/emusync/README.md.

option(USE_EMUSYNC "Memory card sync with emusync" ON)
set(EMUSYNC_VERSION 0.3.0)
set(EMUSYNC_SHA256_LINUX_X86_64 "395f0098c8f622941fd4cea6592fa873902df3b46fafd06e58269786322b5e6a")
set(EMUSYNC_SHA256_MACOS "313f75515f8175418779f49c48082783d0187ce6dbb93c19748498beef1b846b")
set(EMUSYNC_RUNTIME "")

if(USE_EMUSYNC AND EMUSYNC_LIBRARY)
  set(EMUSYNC_RUNTIME "${EMUSYNC_LIBRARY}")
elseif(USE_EMUSYNC AND NOT WIN32)
  set(_emusync_package "")
  if(APPLE)
    set(_emusync_package macos-universal)
    set(_emusync_sha256 ${EMUSYNC_SHA256_MACOS})
    set(_emusync_library libemusync.dylib)
  elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|AMD64|amd64)$")
    set(_emusync_package linux-x86_64)
    set(_emusync_sha256 ${EMUSYNC_SHA256_LINUX_X86_64})
    set(_emusync_library libemusync.so)
  endif()
  if(_emusync_package)
    set(_emusync_name emusync-${EMUSYNC_VERSION}-${_emusync_package})
    set(_emusync_dir ${CMAKE_BINARY_DIR}/${_emusync_name})
    if(NOT EXISTS ${_emusync_dir}/lib/${_emusync_library})
      message(STATUS "emusync: downloading ${_emusync_name}.tar.gz")
      file(DOWNLOAD
        https://github.com/ilyalissoboi/emusync/releases/download/v${EMUSYNC_VERSION}/${_emusync_name}.tar.gz
        ${_emusync_dir}.tar.gz
        EXPECTED_HASH SHA256=${_emusync_sha256}
        STATUS _emusync_status)
      list(GET _emusync_status 0 _emusync_code)
      if(_emusync_code EQUAL 0)
        # cmake -E tar: file(ARCHIVE_EXTRACT) needs CMake 3.18, the project 3.16.
        file(MAKE_DIRECTORY ${_emusync_dir})
        execute_process(COMMAND ${CMAKE_COMMAND} -E tar xzf ${_emusync_dir}.tar.gz
          WORKING_DIRECTORY ${_emusync_dir})
      else()
        message(WARNING "emusync: download failed (${_emusync_status}); building without memory card sync")
      endif()
    endif()
    if(EXISTS ${_emusync_dir}/lib/${_emusync_library})
      set(EMUSYNC_RUNTIME ${_emusync_dir}/lib/${_emusync_library})
    endif()
  endif()
endif()
message(STATUS "emusync (memory card sync) library: ${EMUSYNC_RUNTIME}")

# Copies the library next to the program, where the core looks for it (the app bundle's Frameworks
# folder on macOS), and installs it with the program.
function(emusync_deploy_runtime target)
  if(NOT EMUSYNC_RUNTIME)
    return()
  endif()
  get_target_property(_bundle ${target} MACOSX_BUNDLE)
  if(APPLE AND _bundle)
    set(_dest "$<TARGET_BUNDLE_CONTENT_DIR:${target}>/Frameworks")
  else()
    set(_dest "$<TARGET_FILE_DIR:${target}>")
    if(ALLOW_INSTALL)
      install(FILES "${EMUSYNC_RUNTIME}" DESTINATION "${CMAKE_INSTALL_PREFIX}")
    endif()
  endif()
  add_custom_command(TARGET ${target} POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${_dest}"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different "${EMUSYNC_RUNTIME}" "${_dest}"
    VERBATIM)
endfunction()
