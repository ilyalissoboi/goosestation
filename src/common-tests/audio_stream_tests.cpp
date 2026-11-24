// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/audio_stream.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <vector>

namespace {
constexpr u32 RATE = 44100;
constexpr u32 FADE = 176;     // 4 ms at 44.1 kHz
constexpr u32 RESTART = 1120; // half of a 50 ms target (2240 frames, aligned)
constexpr int MAX_STEP = 300; // a 100 Hz sine at 10000 steps by about 142, a fade adds about 90

using Frames = std::vector<s16>; // interleaved stereo

// Stands in for a backend: the test is the device's callback.
class FakeAudioStream final : public AudioStream
{
public:
  FakeAudioStream(const AudioStreamParameters& parameters) : AudioStream(RATE, parameters)
  {
    BaseInitialize(&StereoSampleReaderImpl);
  }

  void Read(s16* out, u32 frames) { ReadFrames(out, frames); }

  std::vector<bool> device_pauses;

protected:
  void PauseDevice(bool paused) override
  {
    device_pauses.push_back(paused);
    AudioStream::PauseDevice(paused);
  }
};

AudioStreamParameters Params(AudioStretchMode mode = AudioStretchMode::Off, u16 buffer_ms = 50)
{
  AudioStreamParameters p;
  p.stretch_mode = mode;
  p.buffer_ms = buffer_ms;
  return p;
}

// A continuous sine, the same on both channels.
struct Sine
{
  double freq = 100.0;
  double amplitude = 10000.0;
  double phase = 0.0;

  Frames Next(u32 frames)
  {
    Frames out(frames * 2);
    for (u32 i = 0; i < frames; i++)
    {
      const s16 v = static_cast<s16>(std::lround(amplitude * std::sin(phase)));
      out[i * 2] = v;
      out[i * 2 + 1] = v;
      phase += 2.0 * 3.14159265358979323846 * freq / RATE;
    }
    return out;
  }
};

void Write(AudioStream& stream, const Frames& frames)
{
  u32 done = 0;
  const u32 total = static_cast<u32>(frames.size() / 2);
  while (done < total)
  {
    s16* dst;
    u32 space;
    stream.BeginWrite(&dst, &space);
    const u32 n = std::min(space, total - done);
    std::copy_n(&frames[done * 2], n * 2, dst);
    stream.EndWrite(n);
    done += n;
  }
}

// Everything the device was given, in order. Reads go in callbacks of at most 512 frames, as a device's do (the
// restart level waits for a callback and a fade-out on top of half the target).
struct Listener
{
  Frames heard;

  Frames Read(FakeAudioStream& stream, u32 frames)
  {
    Frames out(frames * 2, 12345);
    for (u32 done = 0; done < frames;)
    {
      const u32 n = std::min(frames - done, 512u);
      stream.Read(&out[done * 2], n);
      done += n;
    }
    heard.insert(heard.end(), out.begin(), out.end());
    return out;
  }
};

int MaxStep(const Frames& f)
{
  int step = 0;
  for (size_t i = 2; i < f.size(); i++)
    step = std::max(step, std::abs(f[i] - f[i - 2]));
  return step;
}

bool AllZero(const Frames& f, size_t from_frame = 0, size_t to_frame = SIZE_MAX)
{
  for (size_t i = from_frame * 2; i < std::min(f.size(), to_frame * 2); i++)
  {
    if (f[i] != 0)
      return false;
  }
  return true;
}
} // namespace

TEST(AudioStream, StartsSilentAndFadesIn)
{
  FakeAudioStream stream(Params());
  Sine sine;
  Listener ear;

  const Frames first = sine.Next(1024);
  Write(stream, first);
  EXPECT_TRUE(AllZero(ear.Read(stream, 256)));
  EXPECT_EQ(stream.GetBufferedFramesRelaxed(), 1024u);

  Write(stream, sine.Next(256));
  const Frames out = ear.Read(stream, 512);
  // Nothing was consumed while filling, so the output starts at the first frame written.
  for (u32 i = 0; i < FADE; i++)
    EXPECT_LE(std::abs(out[i * 2]), std::abs(first[i * 2])) << i;
  EXPECT_LT(std::abs(out[2]), 100);
  for (u32 i = FADE; i < 512; i++)
    ASSERT_EQ(out[i * 2], first[i * 2]) << i;
  EXPECT_LE(MaxStep(ear.heard), MAX_STEP);
}

TEST(AudioStream, SteadyPlayIsBitExact)
{
  FakeAudioStream stream(Params());
  Sine sine;
  Listener ear;

  const Frames in = sine.Next(1280);
  Write(stream, in);
  const Frames out = ear.Read(stream, 1024);
  for (u32 i = FADE * 2; i < 1024 * 2; i++)
    ASSERT_EQ(out[i], in[i]) << i;
}

TEST(AudioStream, UnderrunFadesOut)
{
  FakeAudioStream stream(Params());
  Sine sine;
  Listener ear;

  const Frames in = sine.Next(1280);
  Write(stream, in);
  ear.Read(stream, 1024);
  stream.GetAndResetCounters();

  // 256 left, 512 wanted: the last 176 of them fade to nothing, then silence.
  const Frames out = ear.Read(stream, 512);
  for (u32 i = 0; i < 256 - FADE; i++)
    ASSERT_EQ(out[i * 2], in[(1024 + i) * 2]) << i;
  for (u32 i = 256 - FADE; i < 256; i++)
    EXPECT_LE(std::abs(out[i * 2]), std::abs(in[(1024 + i) * 2])) << i;
  EXPECT_LT(std::abs(out[255 * 2]), 100);
  EXPECT_TRUE(AllZero(out, 256));
  EXPECT_LE(MaxStep(ear.heard), MAX_STEP);

  const AudioStream::Counters c = stream.GetAndResetCounters();
  EXPECT_EQ(c.underruns, 1u);
  EXPECT_EQ(c.fades, 1u);
}

TEST(AudioStream, UnderrunWithFewFramesLeftHoldsTheLastFrame)
{
  FakeAudioStream stream(Params());
  Sine sine;
  Listener ear;

  Write(stream, sine.Next(1280));
  ear.Read(stream, 1240);

  // 40 left: the fade still spans 176 frames, the last frame held after the data.
  const Frames out = ear.Read(stream, 512);
  EXPECT_NE(out[39 * 2], 0);
  for (u32 i = 41; i < FADE; i++)
    EXPECT_LE(std::abs(out[i * 2]), std::abs(out[(i - 1) * 2])) << i;
  EXPECT_NE(out[(FADE - 20) * 2], 0);
  EXPECT_TRUE(AllZero(out, FADE));
  EXPECT_LE(MaxStep(ear.heard), MAX_STEP);
}

TEST(AudioStream, RestartsOnlyOnceRefilled)
{
  FakeAudioStream stream(Params());
  Sine sine;
  Listener ear;

  Write(stream, sine.Next(1280));
  ear.Read(stream, 1024);
  ear.Read(stream, 512); // the underrun

  Write(stream, sine.Next(512));
  EXPECT_TRUE(AllZero(ear.Read(stream, 256)));
  EXPECT_EQ(stream.GetBufferedFramesRelaxed(), 512u);

  Write(stream, sine.Next(RESTART - 512 + 32)); // whole 64-frame chunks reach the ring
  const Frames out = ear.Read(stream, 512);
  EXPECT_FALSE(AllZero(out, FADE));
  EXPECT_LE(MaxStep(ear.heard), MAX_STEP);
}

TEST(AudioStream, TinyBufferStillPlays)
{
  for (const AudioStretchMode mode : {AudioStretchMode::Off, AudioStretchMode::Resample})
  {
    FakeAudioStream stream(Params(mode, 10));
    Sine sine;
    Listener ear;
    int loudest = 0;
    for (u32 i = 0; i < 200; i++)
    {
      Write(stream, sine.Next(64));
      const Frames out = ear.Read(stream, 64);
      if (i >= 100)
      {
        for (const s16 v : out)
          loudest = std::max(loudest, std::abs(static_cast<int>(v)));
      }
    }
    EXPECT_GE(loudest, 9000) << AudioStream::GetStretchModeName(mode);
  }
}

TEST(AudioStream, CountersResetWhenRead)
{
  FakeAudioStream stream(Params());
  Sine sine;
  Listener ear;

  Write(stream, sine.Next(1280));
  ear.Read(stream, 2048);
  EXPECT_EQ(stream.GetAndResetCounters().underruns, 1u);

  const AudioStream::Counters c = stream.GetAndResetCounters();
  EXPECT_EQ(c.underruns, 0u);
  EXPECT_EQ(c.overflows, 0u);
  EXPECT_EQ(c.fades, 0u);
  EXPECT_EQ(c.clears, 0u);
  EXPECT_EQ(c.cuts, 0u);
}

TEST(AudioStream, VolumeChangeRamps)
{
  FakeAudioStream stream(Params());
  Sine sine;
  sine.freq = 0.0; // a constant, so the ramp shows on its own
  sine.phase = 3.14159265358979323846 / 2.0;
  Listener ear;

  Write(stream, sine.Next(1792));
  ear.Read(stream, 512);
  stream.SetOutputVolume(50);

  // Half of a 10 ms full-scale ramp: 220 frames, then steady at half.
  const Frames out = ear.Read(stream, 512);
  EXPECT_GT(out[100 * 2], 5100);
  EXPECT_LT(out[100 * 2], 9900);
  for (u32 i = 230; i < 512; i++)
    ASSERT_EQ(out[i * 2], 5000) << i;
  EXPECT_LE(MaxStep(ear.heard), MAX_STEP);
}

TEST(AudioStream, MuteAndUnmuteFadesBackIn)
{
  FakeAudioStream stream(Params());
  Sine sine;
  Listener ear;

  Write(stream, sine.Next(1280));
  ear.Read(stream, 512);
  stream.GetAndResetCounters();

  stream.SetOutputVolume(0);
  Write(stream, sine.Next(1024)); // dropped while muted
  ear.Read(stream, 1536);
  EXPECT_EQ(stream.GetAndResetCounters().underruns, 0u);

  stream.SetOutputVolume(100);
  Write(stream, sine.Next(1280));
  const Frames out = ear.Read(stream, 1024);
  EXPECT_FALSE(AllZero(out, FADE));
  EXPECT_LE(MaxStep(ear.heard), MAX_STEP);
}
