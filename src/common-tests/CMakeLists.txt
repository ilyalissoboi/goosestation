add_executable(common-tests
  bitutils_tests.cpp
  file_system_tests.cpp
  gsvector_yuvtorgb_test.cpp
  memcard_sync_ui_tests.cpp
  ../core/memcard_sync_ui.cpp
  memory_card_helpers_tests.cpp
  ../core/memory_card_helpers.cpp
  path_tests.cpp
  rectangle_tests.cpp
  sdl_input_bindings_tests.cpp
  ../util/sdl_input_bindings.cpp
  shiftjis_tests.cpp
  ../util/shiftjis.cpp
  string_tests.cpp
  text_input_tests.cpp
  ../util/text_input.cpp
)

target_link_libraries(common-tests PRIVATE common gtest gtest_main)
