add_executable(common-tests
  achievements_offline_store_tests.cpp
  ../core/achievements_offline_store.cpp
  android_cover_downloads_tests.cpp
  ../util/android_cover_downloads.cpp
  android_disc_groups_tests.cpp
  ../util/android_disc_groups.cpp
  android_memcards_tests.cpp
  ../util/android_memcards.cpp
  android_game_settings_tests.cpp
  ../util/android_game_settings.cpp
  android_input_keys_tests.cpp
  ../util/android_input_keys.cpp
  android_patch_codes_tests.cpp
  ../util/android_patch_codes.cpp
  bitutils_tests.cpp
  content_uri_tests.cpp
  core_host_stubs.cpp
  fallback_fonts_tests.cpp
  ../util/fallback_fonts.cpp
  file_system_tests.cpp
  gsvector_yuvtorgb_test.cpp
  json_reader_tests.cpp
  librashader_loader_tests.cpp
  memcard_sync_ui_tests.cpp
  ../core/memcard_sync_ui.cpp
  memory_card_helpers_tests.cpp
  ../core/memory_card_helpers.cpp
  memory_card_image_tests.cpp
  ../core/memory_card_image.cpp
  path_tests.cpp
  postprocessing_browser_tests.cpp
  postprocessing_favourites_tests.cpp
  postprocessing_config_tests.cpp
  postprocessing_slang_gpu_tests.cpp
  postprocessing_slang_tests.cpp
  rectangle_tests.cpp
  settings_multitap_tests.cpp
  shader_pack_archive_tests.cpp
  shader_packs_tests.cpp
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

# Android's stand-in for the fullscreen UI, which replaces fullscreen_ui.cpp there, with the ImGuiFullscreen it drives.
if(ANDROID)
  target_sources(common-tests PRIVATE
    fullscreen_ui_stubs_tests.cpp
    ../../android/app/src/cpp/fullscreen_ui_stubs.cpp
    ../util/imgui_fullscreen.cpp
  )
  target_link_libraries(common-tests PRIVATE imgui)
endif()

target_link_libraries(common-tests PRIVATE core scmversion common rcheevos minizip gtest gtest_main)
