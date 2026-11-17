// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include <cstdlib>
#include <string>

/// The tests' data folder (src/common-tests/data): GOOSESTATION_TEST_DATA_DIR from the environment when set (the
/// Android runner pushes it beside the binary), else the source tree's.
inline std::string GetTestDataDir()
{
  const char* dir = std::getenv("GOOSESTATION_TEST_DATA_DIR");
  return (dir && *dir) ? std::string(dir) : std::string(GOOSESTATION_TEST_DATA_DIR);
}
