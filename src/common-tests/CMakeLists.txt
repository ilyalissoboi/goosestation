add_executable(common-tests
  bitutils_tests.cpp
  file_system_tests.cpp
  gsvector_yuvtorgb_test.cpp
  memcard_sync_ui_tests.cpp
  ../core/memcard_sync_ui.cpp
  path_tests.cpp
  rectangle_tests.cpp
  string_tests.cpp
  text_input_tests.cpp
  ../util/text_input.cpp
)

target_link_libraries(common-tests PRIVATE common gtest gtest_main)
