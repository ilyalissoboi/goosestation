add_executable(duckstation-regtest
  regtest_host.cpp
)

target_link_libraries(duckstation-regtest PRIVATE core common scmversion)
set_target_properties(duckstation-regtest PROPERTIES OUTPUT_NAME goosestation-regtest)

add_core_resources(duckstation-regtest)