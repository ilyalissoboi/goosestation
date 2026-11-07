# Slang shader presets with librashader (dep/librashader/README.md). The core loads the library at run time and
# builds against the vendored header, so this only puts the library from the dependency prefix (built by
# scripts/deps/build-dependencies-*) next to the program, or into the app bundle's Frameworks on macOS:
# librashader_deploy_runtime(<target>). Without it, the program runs without slang presets.
#   - Windows: src/util/util.props copies librashader.dll (MSBuild).
#   - Android: the APK carries librashader.so (android/app/build.gradle.kts).
# -DLIBRASHADER_LIBRARY=<file> uses another build of the library.

set(LIBRASHADER_RUNTIME "")
if(NOT ANDROID AND NOT WIN32)
  if(APPLE)
    set(_librashader_name librashader.dylib)
  else()
    set(_librashader_name librashader.so)
  endif()
  find_file(LIBRASHADER_LIBRARY NAMES ${_librashader_name} PATH_SUFFIXES lib NO_CMAKE_SYSTEM_PATH)
  if(LIBRASHADER_LIBRARY)
    set(LIBRASHADER_RUNTIME "${LIBRASHADER_LIBRARY}")
    message(STATUS "Slang presets: librashader from ${LIBRASHADER_RUNTIME}")
  else()
    message(STATUS "Slang presets: librashader not found in the dependencies, so the program runs without them")
  endif()
  unset(_librashader_name)
endif()

function(librashader_deploy_runtime target)
  if(NOT LIBRASHADER_RUNTIME)
    return()
  endif()
  get_target_property(_bundle ${target} MACOSX_BUNDLE)
  if(APPLE AND _bundle)
    set(_dest "$<TARGET_BUNDLE_CONTENT_DIR:${target}>/Frameworks")
  else()
    set(_dest "$<TARGET_FILE_DIR:${target}>")
    if(ALLOW_INSTALL)
      install(FILES "${LIBRASHADER_RUNTIME}" DESTINATION "${CMAKE_INSTALL_PREFIX}")
    endif()
  endif()
  add_custom_command(TARGET ${target} POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${_dest}"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different "${LIBRASHADER_RUNTIME}" "${_dest}"
    VERBATIM)
endfunction()
