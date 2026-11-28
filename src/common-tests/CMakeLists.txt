add_executable(common-tests
  achievements_offline_store_tests.cpp
  achievements_offline_tests.cpp
  ../core/achievements_offline.cpp
  achievements_offline_words_tests.cpp
  ../core/achievements_offline_words.cpp
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
  cdrom_timing_tests.cpp
  cheats_format_tests.cpp
  content_uri_tests.cpp
  core_host_stubs.cpp
  fallback_fonts_tests.cpp
  game_database_tests.cpp
  ../util/fallback_fonts.cpp
  file_system_tests.cpp
  gsvector_yuvtorgb_test.cpp
  gpu_pipeline_batch_gpu_tests.cpp
  gpu_pipeline_batch_tests.cpp
  gpu_shader_batch_tests.cpp
  job_runner_tests.cpp
  json_reader_tests.cpp
  librashader_loader_tests.cpp
  memcard_sync_ui_tests.cpp
  ../core/memcard_sync_ui.cpp
  memory_card_helpers_tests.cpp
  ../core/memory_card_helpers.cpp
  memory_card_image_tests.cpp
  ../core/memory_card_image.cpp
  path_tests.cpp
  patches_tests.cpp
  postprocessing_browser_tests.cpp
  postprocessing_favourites_tests.cpp
  postprocessing_config_tests.cpp
  postprocessing_gpu_tests.cpp
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
  texture_names_tests.cpp
  texture_pack_config_tests.cpp
  texture_replacements_tests.cpp
  gpu_texture_data_tests.cpp
  gpu_texture_cache_tests.cpp
  gpu_thread_queue_tests.cpp
  gpu_thread_tests.cpp
  imgui_draw_copy_tests.cpp
  gpu_presenter_tests.cpp
  texture_cache_reference_tests.cpp
  settings_texture_cache_tests.cpp
  settings_gpu_thread_tests.cpp
  shader_compile_threads_tests.cpp
  settings_cdrom_tests.cpp
  settings_vsync_tests.cpp
  settings_pgxp_tests.cpp
  performance_counters_math_tests.cpp
  pgxp_tests.cpp
  frame_pacing_tests.cpp
  audio_stream_tests.cpp
  ../util/text_input.cpp
)

# SDL isn't an input source on Android (InputSourceType has no SDL there).
if(NOT ANDROID)
  target_sources(common-tests PRIVATE
    sdl_input_bindings_tests.cpp
    ../util/sdl_input_bindings.cpp
  )
endif()

# The OpenGL ES shaders' tests set glad's flags and context strings themselves.
if(ENABLE_OPENGL)
  target_sources(common-tests PRIVATE shadergen_gles_tests.cpp)
  target_link_libraries(common-tests PRIVATE glad)
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

target_link_libraries(common-tests PRIVATE core scmversion common rcheevos minizip xxhash reshadefx gtest gtest_main)

# game_database_tests.cpp loads the shipped database from the source tree (and skips where it isn't there).
target_compile_definitions(common-tests PRIVATE
  GOOSESTATION_TEST_RESOURCES_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../../data/resources"
  GOOSESTATION_TEST_DATA_DIR="${CMAKE_CURRENT_SOURCE_DIR}/data")
