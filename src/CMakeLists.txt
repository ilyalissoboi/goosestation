add_subdirectory(common)
add_subdirectory(util)
add_subdirectory(core)
add_subdirectory(scmversion)

if(WIN32 OR APPLE)
  add_subdirectory(updater)
endif()

if(BUILD_NOGUI_FRONTEND)
  add_subdirectory(duckstation-nogui)
endif()

if(BUILD_QT_FRONTEND)
  add_subdirectory(duckstation-qt)
endif()

if(ANDROID)
  add_subdirectory(${CMAKE_SOURCE_DIR}/android/app/src/cpp ${CMAKE_CURRENT_BINARY_DIR}/android)
endif()

if(BUILD_REGTEST)
  add_subdirectory(duckstation-regtest)
endif()

if(BUILD_TESTS)
  add_subdirectory(common-tests EXCLUDE_FROM_ALL)
endif()
