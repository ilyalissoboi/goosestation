// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "types.h"

/// The CD drive's timing, as functions of the drive's state, so it can be tested without a booted system.
namespace CDROMTiming {

/// `[CDROM] ReadSpeedup` 0: data comes as fast as the game takes it (see ReadPace::paced_by_game).
static constexpr u32 READ_SPEEDUP_MAXIMUM = 0;

/// The least time from the game taking a sector to the next one, at Maximum, which caps it at 4x: games need time
/// between sectors (at 8x and above, Breath of Fire III drops sectors and Breath of Fire IV loops on a load).
static constexpr TickCount MAX_READ_FLOOR_TICKS = static_cast<TickCount>((44100 * 0x300) / (150 * 4));

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

/// Reads count as streaming this long after the last sign: it spans a movie's gaps between XA sectors and MDEC decodes,
/// and lets a load right after a movie be sped up.
static constexpr float STREAM_HOLD_SECONDS = 0.5f;

/// Whether the drive is streaming audio or video, from what the drive and the console do: an XA-ADPCM sector, a data
/// sector flagged real-time or video, CD-DA playing, or the MDEC starting a decode (each a sign), and the CD-DA or XA
/// mode bits.
class StreamDetector
{
public:
  /// After a reset or a state load, streaming is assumed for the hold, so a movie can't run sped up.
  void Reset(GlobalTicks now);
  /// For a state load, whose timeline isn't loaded yet: the reset takes the time of the next query.
  void ResetLater() { m_reset_pending = true; }
  void NoteSign(GlobalTicks now);
  bool IsStreaming(GlobalTicks now, bool mode_bits, TickCount ticks_per_second);

private:
  GlobalTicks m_last_sign = 0;
  bool m_has_sign = false;
  bool m_reset_pending = false;
};

/// Whether the drive is loading, for fast-forward while loading: seeking or reading data, and nothing streamed within
/// the stream's hold (a movie read in bursts seeks between them).
inline bool IsLoadingData(bool seeking, bool reading_data, bool streaming)
{
  return !streaming && (seeking || reading_data);
}

/// Fast-forward while loading stays on this long (in emulated time) after the drive last loaded, across a load's bursts.
static constexpr float LOAD_FF_HOLD_SECONDS = 0.25f;

/// `[CDROM] FastForwardLoading`: on while the drive seeks or reads data that isn't streamed, off once it has been quiet
/// for the hold, and off at once when it isn't allowed (the setting off, or hardcore) or the drive streams.
class LoadFastForward
{
public:
  bool Update(bool allowed, bool cd_loading, bool cd_streaming, GlobalTicks now, TickCount ticks_per_second);
  void Reset() { m_active = false; }
  bool IsActive() const { return m_active; }

private:
  GlobalTicks m_last_loading = 0;
  bool m_active = false;
};

} // namespace CDROMTiming
