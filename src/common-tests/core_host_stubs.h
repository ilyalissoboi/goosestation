// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include <string>

// Test controls for the no-op host in core_host_stubs.cpp.
namespace CoreHostStubs {

// Where Host::ReadResourceFile and friends read from. Empty (the default): no resources are found.
void SetResourceDirectory(std::string directory);

} // namespace CoreHostStubs
