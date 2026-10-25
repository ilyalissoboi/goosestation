// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "core/memcard_sync_ui.h"
#include <gtest/gtest.h>

#include <limits>

using MemcardSync::Conflict;
using MemcardSync::ConflictVersion;

static ConflictVersion Version(s64 modified_unix_ms)
{
  ConflictVersion v;
  v.device = "Device";
  v.modified_unix_ms = modified_unix_ms;
  return v;
}

TEST(MemcardSyncUI, StatusKind)
{
  using MemcardSync::StatusKind;
  ASSERT_EQ(MemcardSync::GetStatusKind(false, true, true, true, true, true), StatusKind::Unavailable);
  ASSERT_EQ(MemcardSync::GetStatusKind(true, true, true, true, true, true), StatusKind::Applying);
  ASSERT_EQ(MemcardSync::GetStatusKind(true, false, true, true, true, true), StatusKind::StartError);
  ASSERT_EQ(MemcardSync::GetStatusKind(true, false, false, false, true, true), StatusKind::Off);
  ASSERT_EQ(MemcardSync::GetStatusKind(true, false, false, true, false, true), StatusKind::Incomplete);
  ASSERT_EQ(MemcardSync::GetStatusKind(true, false, false, true, true, true), StatusKind::Status);
  ASSERT_EQ(MemcardSync::GetStatusKind(true, false, false, true, true, false), StatusKind::Idle);
}

TEST(MemcardSyncUI, ConflictChoices)
{
  Conflict c;
  c.local = Version(100);
  c.remote = {Version(200), Version(300)};
  const std::vector<MemcardSync::ConflictChoice> expected = {
    {true, 0, false}, {false, 0, false}, {false, 1, false}, {false, 0, true}};
  ASSERT_EQ(MemcardSync::GetConflictChoices(c), expected);
  ASSERT_EQ(MemcardSync::GetDefaultConflictChoice(c), 2u);

  c.local = Version(300);
  c.remote = {Version(200)};
  ASSERT_EQ(MemcardSync::GetDefaultConflictChoice(c), 0u);

  // A tie keeps this device's version.
  c.local = Version(200);
  ASSERT_EQ(MemcardSync::GetDefaultConflictChoice(c), 0u);

  c.local.reset();
  c.remote = {Version(50), Version(40)};
  ASSERT_EQ(MemcardSync::GetDefaultConflictChoice(c), 1u);

  c.remote.clear();
  const std::vector<MemcardSync::ConflictChoice> only_local = {{true, 0, false}, {false, 0, true}};
  ASSERT_EQ(MemcardSync::GetConflictChoices(c), only_local);
  ASSERT_EQ(MemcardSync::GetDefaultConflictChoice(c), 0u);
}

TEST(MemcardSyncUI, SaveBlockCount)
{
  ASSERT_EQ(MemcardSync::GetSaveBlockCount(3 * 8192 + 100), 3u);
  ASSERT_EQ(MemcardSync::GetSaveBlockCount(100), 1u);
  ASSERT_EQ(MemcardSync::GetSaveBlockCount(0), 1u);
}

TEST(MemcardSyncUI, LocalTime)
{
  // Versions come from other devices, so their times can be anything: out of range is no time, not a crash.
  ASSERT_TRUE(MemcardSync::GetLocalTime(1'700'000'000'000).has_value());
  ASSERT_FALSE(MemcardSync::GetLocalTime(std::numeric_limits<s64>::max()).has_value());
  ASSERT_FALSE(MemcardSync::GetLocalTime(std::numeric_limits<s64>::min()).has_value());
  ASSERT_FALSE(MemcardSync::GetLocalTime(-1).has_value());
}
