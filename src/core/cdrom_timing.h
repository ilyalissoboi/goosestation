// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "types.h"

/// The CD drive's timing, as functions of the drive's state, so it can be tested without a booted system.
namespace CDROMTiming {

/// `[CDROM] ReadSpeedup` 0: data comes as fast as the game takes it (see ReadPace::paced_by_game).
static constexpr u32 READ_SPEEDUP_MAXIMUM = 0;

/// The least time from the game taking a sector to the next one, at Maximum: room for the game's handler to return
/// before the next INT1. It also caps Maximum at 32x.
static constexpr TickCount MAX_READ_FLOOR_TICKS = static_cast<TickCount>((44100 * 0x300) / (150 * 32));

/// The shortest seek: the whole of an instant seek but a short forward one.
static constexpr TickCount SEEK_MIN_TICKS = 30000;

/// With instant seeks and `[CDROM] ShortDriveWaits`, the speed change, the TOC read, spin-up and stop take this many
/// times less than on the drive.
static constexpr TickCount SHORT_WAIT_DIVISOR = 10;

struct ReadPace
{
  TickCount interval;  // between sectors, as scheduled
  bool paced_by_game;  // the next sector is brought forward once the game has taken the last one
};

/// Reads are only sped up at double speed, and never while the drive streams audio or video.
ReadPace GetReadPace(u32 read_speedup, bool double_speed, bool streaming, TickCount ticks_per_second);

struct SeekParams
{
  u32 current_lba = 0;              // where the head is (ignored with the motor off: it starts from 0)
  u32 new_lba = 0;
  bool motor_on = true;
  TickCount spin_up_ticks = 0;      // added when the motor is off
  bool double_speed = true;
  u32 seek_speedup = 1;             // 0 is instant
  TickCount pending_change_ticks = 0; // what's left of a speed change or TOC read the seek waits for
};

/// The seek's time, before overclock scaling.
TickCount GetSeekTicks(const SeekParams& p);

/// A fixed wait (speed change, TOC read, spin-up, stop), shortened with instant seeks and short drive waits.
TickCount GetFixedWaitTicks(TickCount normal_ticks, u32 seek_speedup, bool short_drive_waits);

} // namespace CDROMTiming
