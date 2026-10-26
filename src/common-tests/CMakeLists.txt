add_executable(common-tests
  android_input_keys_tests.cpp
  ../util/android_input_keys.cpp
  bitutils_tests.cpp
  fallback_fonts_tests.cpp
  ../util/fallback_fonts.cpp
  file_system_tests.cpp
  gsvector_yuvtorgb_test.cpp
  memcard_sync_ui_tests.cpp
  ../core/memcard_sync_ui.cpp
  memory_card_helpers_tests.cpp
  ../core/memory_card_helpers.cpp
  path_tests.cpp
  rectangle_tests.cpp
  shiftjis_tests.cpp
  ../util/shiftjis.cpp
  string_tests.cpp
  text_input_tests.cpp
  ../util/text_input.cpp
)

# SDL isn't an input source on Android (InputSourceType has no SDL there).
if(NOT ANDROID)
  target_sources(common-tests PRIVATE
    sdl_input_bindings_tests.cpp
    ../util/sdl_input_bindings.cpp
  )
endif()

target_link_libraries(common-tests PRIVATE common gtest gtest_main)
