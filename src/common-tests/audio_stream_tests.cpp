// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "util/audio_stream.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <atomic>
#include <mutex>
#include <chrono>
#include <random>
#include <thread>
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
  void SetLatency(u32 frames) { SetDeviceLatencyFrames(frames); }
  void Hold(bool held) { SetWorkerHeld(held); }
  u32 ClearedGeneration() const { return GetClearedGeneration(); }

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

// Hands frames to the stream as the emulation thread does, without waiting for the audio thread.
void WriteNoSync(AudioStream& stream, const Frames& frames)
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

// The same, then waits until the audio thread has stretched them into the output ring.
void Write(AudioStream& stream, const Frames& frames)
{
  WriteNoSync(stream, frames);
  stream.SyncWorker();
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

constexpr u32 NOT_FOUND = ~0u;

// Where `expected` starts in `out`, found by its frames after a fade-in (which are exact); the frames before are
// checked to be no louder than expected's, as a fade-in's are.
u32 Locate(const Frames& out, const Frames& expected)
{
  const size_t frames = out.size() / 2;
  constexpr u32 PROBE = 64;
  for (size_t at = FADE; at + PROBE <= frames; at++)
  {
    if (std::equal(&out[at * 2], &out[(at + PROBE) * 2], &expected[FADE * 2]))
    {
      const u32 start = static_cast<u32>(at - FADE);
      for (u32 i = 0; i < FADE; i++)
      {
        if (std::abs(out[(start + i) * 2]) > std::abs(expected[i * 2]) + 1)
          return NOT_FOUND;
      }
      return start;
    }
  }
  return NOT_FOUND;
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

namespace {
// Plays until full gain, so a clear has something audible to fade (256 frames left, room to write more).
void PlayUntilFull(FakeAudioStream& stream, Sine& sine, Listener& ear)
{
  Write(stream, sine.Next(1280));
  ear.Read(stream, 1024);
  stream.GetAndResetCounters();
}
} // namespace

TEST(AudioStream, EmptyBufferFadesOutAndDropsStaleAudio)
{
  FakeAudioStream stream(Params());
  Sine old_sine;
  Listener ear;
  PlayUntilFull(stream, old_sine, ear);

  stream.EmptyBuffer();
  Sine new_sine;
  new_sine.freq = 300.0;
  new_sine.amplitude = 7000.0;
  const Frames fresh = new_sine.Next(1280);
  Write(stream, fresh);

  const Frames out = ear.Read(stream, 1536);
  // The old sine fades out within a fade, then silence, then the new one from its first frame, fading in.
  const u32 start = Locate(out, fresh);
  ASSERT_NE(start, NOT_FOUND);
  EXPECT_TRUE(AllZero(out, FADE, start));
  for (u32 i = FADE; start + i < 1536; i++)
    ASSERT_EQ(out[(start + i) * 2], fresh[i * 2]) << i;
  EXPECT_LE(MaxStep(ear.heard), MAX_STEP);

  const AudioStream::Counters c = stream.GetAndResetCounters();
  EXPECT_EQ(c.fades, 1u);
  EXPECT_EQ(c.clears, 1u);
  EXPECT_EQ(c.underruns, 0u);
}

TEST(AudioStream, EmptyBufferWhileFillingIsImmediate)
{
  FakeAudioStream stream(Params());
  Sine sine;
  Listener ear;
  Write(stream, sine.Next(512));

  stream.EmptyBuffer();
  stream.SyncWorker(); // the audio thread passes the clear on to the callback
  EXPECT_TRUE(AllZero(ear.Read(stream, 256)));
  EXPECT_EQ(stream.GetBufferedFramesRelaxed(), 0u);

  const AudioStream::Counters c = stream.GetAndResetCounters();
  EXPECT_EQ(c.clears, 1u);
  EXPECT_EQ(c.fades, 0u);
}

TEST(AudioStream, RepeatedClearsRecover)
{
  FakeAudioStream stream(Params());
  Sine sine;
  Listener ear;
  PlayUntilFull(stream, sine, ear);

  Frames tail;
  for (int i = 0; i < 10; i++)
  {
    stream.EmptyBuffer();
    tail = sine.Next(64);
    Write(stream, tail);
    ear.Read(stream, 64);
  }

  // What came after the last clear plays, from its first frame.
  Frames after = sine.Next(1280);
  Write(stream, after);
  after.insert(after.begin(), tail.begin(), tail.end());
  const Frames out = ear.Read(stream, 1024);
  const u32 start = Locate(out, after);
  ASSERT_NE(start, NOT_FOUND);
  EXPECT_TRUE(AllZero(out, 0, start));
  EXPECT_LE(MaxStep(ear.heard), MAX_STEP);
  EXPECT_EQ(stream.GetAndResetCounters().fades, 1u);
}

TEST(AudioStream, NullStreamDiscards)
{
  std::unique_ptr<AudioStream> stream = AudioStream::CreateNullStream(RATE, 50);
  Sine sine;
  Write(*stream, sine.Next(4096));
  EXPECT_EQ(stream->GetBufferedFramesRelaxed(), 0u);
  const AudioStream::Counters c = stream->GetAndResetCounters();
  EXPECT_EQ(c.overflows + c.underruns + c.fades + c.clears + c.cuts, 0u);
}

TEST(AudioStream, UnderrunFadeAcrossSmallCallbacksKeepsHolding)
{
  FakeAudioStream stream(Params());
  Sine sine;
  Listener ear;
  Write(stream, sine.Next(1280));
  ear.Read(stream, 1240);

  // 64-frame callbacks: the fade (176 frames) spans three. New audio arriving mid-fade waits for the restart.
  Frames out = ear.Read(stream, 64);
  Write(stream, sine.Next(1280));
  for (int i = 0; i < 3; i++)
  {
    const Frames more = ear.Read(stream, 64);
    out.insert(out.end(), more.begin(), more.end());
  }
  for (u32 i = 41; i < FADE; i++)
    EXPECT_LE(std::abs(out[i * 2]), std::abs(out[(i - 1) * 2])) << i;
  EXPECT_TRUE(AllZero(out, FADE, 192)); // the new audio fades in from the next callback
  EXPECT_LE(MaxStep(ear.heard), MAX_STEP);
}

TEST(AudioStream, ConcurrentClearsNeverReplayOldAudio)
{
  // The left channel counts frames (mod 30000), the right is 30000: their ratio gives the frame through any fade.
  FakeAudioStream stream(Params());
  std::atomic<bool> stop{false};

  std::thread producer([&]() {
    std::mt19937 rng(1234);
    u32 index = 0;
    while (!stop.load())
    {
      Frames f(64 * 2);
      for (u32 i = 0; i < 64; i++, index++)
      {
        f[i * 2] = static_cast<s16>(index % 30000);
        f[i * 2 + 1] = 30000;
      }
      // Kept topped up, with a clear every few chunks, so clears land while the callback is reading.
      while (!stop.load() && stream.GetBufferedFramesRelaxed() > 1600)
        std::this_thread::yield();
      Write(stream, f);
      if (rng() % 30 == 0)
        stream.EmptyBuffer();
    }
  });

  Frames heard;
  const auto end = std::chrono::steady_clock::now() + std::chrono::seconds(2);
  while (std::chrono::steady_clock::now() < end)
  {
    Frames out(256 * 2);
    stream.Read(out.data(), 256);
    heard.insert(heard.end(), out.begin(), out.end());
    std::this_thread::sleep_for(std::chrono::microseconds(200));
  }
  stop.store(true);
  producer.join();

  int last = -1;
  u32 loud = 0;
  for (size_t i = 0; i < heard.size(); i += 2)
  {
    if (heard[i + 1] <= 8000) // below that, rounding blurs the ratio by more than a few frames
      continue;
    loud++;
    const int index = static_cast<int>(std::lround(30000.0 * heard[i] / heard[i + 1]));
    // A replay goes back by at most the ring's length; a bigger step back is the wrap at 30000 after a skip forward.
    if (last >= 0 && index < last - 8 && last - index < 2400)
      FAIL() << "frame " << i / 2 << " went back from " << last << " to " << index;
    last = index;
  }
  EXPECT_GT(loud, 10000u);
}

TEST(AudioStream, OverflowFadesOutDropsAndFadesIn)
{
  FakeAudioStream stream(Params());
  Sine sine;
  Listener ear;

  // No reads: past full, the producer fades out what it writes, then drops.
  Write(stream, sine.Next(2560));
  EXPECT_LT(stream.GetBufferedFramesRelaxed(), 2240u);
  AudioStream::Counters c = stream.GetAndResetCounters();
  EXPECT_EQ(c.overflows, 1u);
  EXPECT_EQ(c.fades, 1u);

  // Drained below half, the producer fades back in.
  ear.Read(stream, 1024);
  Write(stream, sine.Next(640));
  ear.Read(stream, 1536);
  EXPECT_LE(MaxStep(ear.heard), MAX_STEP);
  c = stream.GetAndResetCounters();
  EXPECT_EQ(c.overflows, 0u);
  EXPECT_EQ(c.underruns, 0u);

  // Somewhere in what was heard, the gap: a fade-out to silence and a fade-in.
  bool quiet = false;
  for (size_t i = 600 * 2; i < ear.heard.size() - 600 * 2; i += 2)
    quiet |= (std::abs(ear.heard[i]) < 200 && std::abs(ear.heard[i + 2]) < 200 && std::abs(ear.heard[i + 4]) < 200);
  EXPECT_TRUE(quiet);
}

TEST(AudioStream, OverflowResumesInEveryMode)
{
  for (const AudioStretchMode mode : {AudioStretchMode::Off, AudioStretchMode::Resample, AudioStretchMode::TimeStretch})
  {
    for (const u16 buffer_ms : {u16(10), u16(100)})
    {
      FakeAudioStream stream(Params(mode, buffer_ms));
      Sine sine;
      Listener ear;
      // Three times faster than it's read, for three seconds of output.
      for (u32 second = 0; second < 3; second++)
      {
        int loudest = 0;
        for (u32 i = 0; i < RATE / 128; i++)
        {
          Write(stream, sine.Next(384));
          for (const s16 v : ear.Read(stream, 128))
            loudest = std::max(loudest, std::abs(static_cast<int>(v)));
        }
        EXPECT_GE(loudest, 8000) << AudioStream::GetStretchModeName(mode) << " " << buffer_ms << " ms, second "
                                 << second;
      }
      // Time stretching keeps up at 3x without overflowing.
      if (mode != AudioStretchMode::TimeStretch)
      {
        EXPECT_GT(stream.GetAndResetCounters().overflows, 0u)
          << AudioStream::GetStretchModeName(mode) << " " << buffer_ms << " ms";
      }
    }
  }
}

TEST(AudioStream, TimeStretchUnderConcurrencyStaysConsistent)
{
  FakeAudioStream stream(Params(AudioStretchMode::TimeStretch, 50));
  std::atomic<bool> stop{false};

  // The producer runs at 4x, then 0.5x, in turn; the callback at 1x.
  std::thread producer([&]() {
    Sine sine;
    const auto start = std::chrono::steady_clock::now();
    while (!stop.load())
    {
      const bool fast = ((std::chrono::steady_clock::now() - start) / std::chrono::milliseconds(500)) % 2 == 0;
      Write(stream, sine.Next(64));
      std::this_thread::sleep_for(std::chrono::microseconds(fast ? 363 : 2902));
    }
  });

  int loudest = 0;
  const auto end = std::chrono::steady_clock::now() + std::chrono::seconds(3);
  while (std::chrono::steady_clock::now() < end)
  {
    Frames out(256 * 2);
    stream.Read(out.data(), 256);
    for (const s16 v : out)
      loudest = std::max(loudest, std::abs(static_cast<int>(v)));
    std::this_thread::sleep_for(std::chrono::microseconds(5805));
  }
  stop.store(true);
  producer.join();

  EXPECT_GE(loudest, 8000);
  EXPECT_LE(stream.GetBufferedFramesRelaxed(), stream.GetBufferSize());
}

namespace {
// A device callback on its own thread, every 5.8 ms (256 frames at 44.1 kHz), until stopped.
class CallbackThread
{
public:
  explicit CallbackThread(FakeAudioStream& stream) : m_stream(stream), m_thread([this]() { Run(); }) {}
  ~CallbackThread()
  {
    m_stop.store(true);
    m_thread.join();
  }

  Frames Heard()
  {
    std::lock_guard lock(m_mutex);
    return m_heard;
  }

private:
  void Run()
  {
    while (!m_stop.load())
    {
      if (m_running.load())
      {
        Frames out(256 * 2);
        m_stream.Read(out.data(), 256);
        std::lock_guard lock(m_mutex);
        m_heard.insert(m_heard.end(), out.begin(), out.end());
      }
      std::this_thread::sleep_for(std::chrono::microseconds(5805));
    }
  }

  FakeAudioStream& m_stream;
  std::mutex m_mutex;
  Frames m_heard;
  std::atomic<bool> m_stop{false};

public:
  std::atomic<bool> m_running{true};

private:
  std::thread m_thread;
};
} // namespace

TEST(AudioStream, PauseFadesOutWaitsAndClears)
{
  FakeAudioStream stream(Params());
  Sine old_sine;
  Write(stream, old_sine.Next(1792));
  CallbackThread device(stream);
  std::this_thread::sleep_for(std::chrono::milliseconds(20)); // playing at full gain

  const auto start = std::chrono::steady_clock::now();
  stream.SetPaused(true);
  EXPECT_LT(std::chrono::steady_clock::now() - start, std::chrono::milliseconds(100));
  ASSERT_EQ(stream.device_pauses, std::vector<bool>{true});
  EXPECT_EQ(stream.GetAndResetCounters().cuts, 0u);

  // The device stops, as a real one would; nothing queued before the pause plays after it.
  device.m_running.store(false);
  std::this_thread::sleep_for(std::chrono::milliseconds(10));
  const size_t heard_before = device.Heard().size();
  stream.SetPaused(false);
  Sine new_sine;
  new_sine.freq = 300.0;
  new_sine.amplitude = 7000.0;
  const Frames fresh = new_sine.Next(1280);
  Write(stream, fresh);
  device.m_running.store(true);
  std::this_thread::sleep_for(std::chrono::milliseconds(40));
  device.m_running.store(false);
  std::this_thread::sleep_for(std::chrono::milliseconds(10));

  const Frames all = device.Heard();
  const Frames after(all.begin() + static_cast<std::ptrdiff_t>(heard_before), all.end());
  const u32 at = Locate(after, fresh);
  ASSERT_NE(at, NOT_FOUND);
  EXPECT_TRUE(AllZero(after, 0, at));
  EXPECT_LE(MaxStep(all), MAX_STEP);
}

TEST(AudioStream, PauseWithNoConsumerReturnsAfterTimeout)
{
  FakeAudioStream stream(Params());
  Sine sine;
  Listener ear;
  PlayUntilFull(stream, sine, ear);
  Write(stream, sine.Next(512));

  // No callbacks: the fade can't run, so the wait gives up and counts a cut.
  const auto start = std::chrono::steady_clock::now();
  stream.SetPaused(true);
  EXPECT_LT(std::chrono::steady_clock::now() - start, std::chrono::milliseconds(150));
  EXPECT_EQ(stream.GetAndResetCounters().cuts, 1u);

  stream.SetPaused(false);
  const Frames fresh = sine.Next(1280);
  Write(stream, fresh);
  const Frames out = ear.Read(stream, 1536);
  EXPECT_NE(Locate(out, fresh), NOT_FOUND);
}

TEST(AudioStream, PauseWhilePausedIsANoOp)
{
  FakeAudioStream stream(Params());
  stream.SetPaused(true);
  const auto start = std::chrono::steady_clock::now();
  stream.SetPaused(true);
  EXPECT_LT(std::chrono::steady_clock::now() - start, std::chrono::milliseconds(20));
  EXPECT_EQ(stream.device_pauses, std::vector<bool>{true});
}

TEST(AudioStream, NullStreamPausesAtOnce)
{
  std::unique_ptr<AudioStream> stream = AudioStream::CreateNullStream(RATE, 50);
  Sine sine;
  Write(*stream, sine.Next(1024));
  const auto start = std::chrono::steady_clock::now();
  stream->SetPaused(true);
  EXPECT_LT(std::chrono::steady_clock::now() - start, std::chrono::milliseconds(20));
  EXPECT_EQ(stream->GetAndResetCounters().cuts, 0u);
}

TEST(AudioStream, PauseWithNothingWrittenIsImmediate)
{
  // A stream made while the system is paused (a settings change in the pause menu) has nothing to fade.
  FakeAudioStream stream(Params());
  const auto start = std::chrono::steady_clock::now();
  stream.SetPaused(true);
  EXPECT_LT(std::chrono::steady_clock::now() - start, std::chrono::milliseconds(20));
  EXPECT_EQ(stream.GetAndResetCounters().cuts, 0u);
  EXPECT_EQ(stream.device_pauses, std::vector<bool>{true});
}

TEST(AudioStream, PauseWaitsForTheDeviceToPlayTheFade)
{
  // The callback writes the fade into the device's buffer; the device plays it up to its latency later.
  FakeAudioStream stream(Params());
  stream.SetLatency(RATE / 20); // 50 ms
  Sine sine;
  Write(stream, sine.Next(1792));
  CallbackThread device(stream);
  std::this_thread::sleep_for(std::chrono::milliseconds(20));

  const auto start = std::chrono::steady_clock::now();
  stream.SetPaused(true);
  const auto took = std::chrono::steady_clock::now() - start;
  EXPECT_GE(took, std::chrono::milliseconds(50));
  EXPECT_LT(took, std::chrono::milliseconds(250));
  EXPECT_EQ(stream.GetAndResetCounters().cuts, 0u);
}

TEST(AudioStream, TimeStretchRefillsAtNormalTempoAfterAClear)
{
  // Produced and played at the same rate: after a clear, the fill comes back to about the target rather than
  // overshooting from a tempo average restarted on an empty ring.
  FakeAudioStream stream(Params(AudioStretchMode::TimeStretch, 50));
  Sine sine;
  Listener ear;
  for (u32 i = 0; i < 2000; i++) // about 3 s
  {
    Write(stream, sine.Next(64));
    ear.Read(stream, 64);
  }
  const u32 settled = stream.GetBufferedFramesRelaxed();

  stream.EmptyBuffer();
  u32 highest = 0;
  for (u32 i = 0; i < 1400; i++) // about 2 s
  {
    Write(stream, sine.Next(64));
    ear.Read(stream, 64);
    highest = std::max(highest, stream.GetBufferedFramesRelaxed());
  }
  EXPECT_LE(highest, std::max(settled, stream.GetTargetBufferSize()) * 3 / 2) << "settled at " << settled;
  EXPECT_EQ(stream.GetAndResetCounters().underruns, 0u);
}

TEST(AudioStream, EmulationThreadOnlyCopies)
{
  FakeAudioStream stream(Params(AudioStretchMode::TimeStretch, 50));
  Sine sine;
  stream.Hold(true);
  WriteNoSync(stream, sine.Next(4096)); // more than SoundTouch's own latency
  EXPECT_EQ(stream.GetBufferedFramesRelaxed(), 0u);
  EXPECT_EQ(stream.GetBufferedInputFramesRelaxed(), 4096u);

  stream.Hold(false);
  stream.SyncWorker();
  EXPECT_EQ(stream.GetBufferedInputFramesRelaxed(), 0u);
  EXPECT_GT(stream.GetBufferedFramesRelaxed(), 0u);
}

TEST(AudioStream, DestroyWhileWorkingIsClean)
{
  for (int i = 0; i < 20; i++)
  {
    auto stream = std::make_unique<FakeAudioStream>(Params(AudioStretchMode::TimeStretch, 100));
    Sine sine;
    WriteNoSync(*stream, sine.Next(64 * 300)); // a backlog the thread is still working through
    const auto start = std::chrono::steady_clock::now();
    stream.reset();
    EXPECT_LT(std::chrono::steady_clock::now() - start, std::chrono::seconds(1));
  }
}

TEST(AudioStream, PauseWithABacklogDropsIt)
{
  FakeAudioStream stream(Params(AudioStretchMode::TimeStretch, 100));
  Sine old_sine;
  Write(stream, old_sine.Next(1792));
  CallbackThread device(stream);
  std::this_thread::sleep_for(std::chrono::milliseconds(40));

  // The thread is busy (held) with 300 ms queued when the pause comes.
  stream.Hold(true);
  WriteNoSync(stream, old_sine.Next(13248));
  stream.SetPaused(true);
  device.m_running.store(false);
  stream.Hold(false);
  stream.SyncWorker();

  std::this_thread::sleep_for(std::chrono::milliseconds(10));
  const size_t heard_before = device.Heard().size();
  stream.SetPaused(false);
  Sine new_sine;
  new_sine.freq = 300.0;
  new_sine.amplitude = 7000.0;
  Write(stream, new_sine.Next(4096));
  device.m_running.store(true);
  std::this_thread::sleep_for(std::chrono::milliseconds(100));
  device.m_running.store(false);
  std::this_thread::sleep_for(std::chrono::milliseconds(10));

  // After the resume, nothing loud enough to be the old sine before the new one fades in, and the old one's
  // 100 Hz never comes back: the new sine is 7000 at most.
  const Frames all = device.Heard();
  const Frames after(all.begin() + static_cast<std::ptrdiff_t>(heard_before), all.end());
  int loudest = 0;
  for (const s16 v : after)
    loudest = std::max(loudest, std::abs(static_cast<int>(v)));
  EXPECT_LE(loudest, 7100);
  EXPECT_GT(loudest, 3000);
}

TEST(AudioStream, InputOverflowClears)
{
  FakeAudioStream stream(Params());
  Sine sine;
  Listener ear;
  stream.Hold(true);
  WriteNoSync(stream, sine.Next(26496)); // 600 ms, past the 500 ms input ring
  stream.Hold(false);
  stream.SyncWorker();
  AudioStream::Counters c = stream.GetAndResetCounters();
  EXPECT_EQ(c.overflows, 1u);
  EXPECT_EQ(stream.GetBufferedFramesRelaxed(), 0u); // the queued input was dropped by the clear

  const Frames newest = sine.Next(1280);
  Write(stream, newest);
  const Frames out = ear.Read(stream, 1024);
  EXPECT_NE(Locate(out, newest), NOT_FOUND);
  EXPECT_LE(MaxStep(ear.heard), MAX_STEP);
}

TEST(AudioStream, ClearGenerationsMatchAcrossRings)
{
  FakeAudioStream stream(Params());
  Sine sine;
  Listener ear;
  for (u32 i = 1; i <= 10; i++)
  {
    Write(stream, sine.Next(256));
    stream.EmptyBuffer();
    stream.SyncWorker();
    ear.Read(stream, 64);
    EXPECT_EQ(stream.ClearedGeneration(), i);
  }
}

namespace {
// One second at 1x, then a stall of `stall_ms` with nothing produced while the device keeps reading.
void SecondThenStall(FakeAudioStream& stream, Sine& sine, Listener& ear, u32 stall_ms)
{
  for (u32 i = 0; i < RATE / 512; i++)
  {
    Write(stream, sine.Next(512));
    ear.Read(stream, 512);
  }
  for (u32 i = 0; i < (RATE * stall_ms / 1000) / 512; i++)
    ear.Read(stream, 512);
}
} // namespace

TEST(AudioStream, BufferGrowsWhileUnderrunsRepeat)
{
  FakeAudioStream stream(Params(AudioStretchMode::TimeStretch, 50));
  Sine sine;
  Listener ear;
  u32 highest = 0;
  for (int i = 0; i < 8; i++)
  {
    SecondThenStall(stream, sine, ear, 300);
    highest = std::max(highest, stream.GetAndResetCounters().extra_ms);
  }
  EXPECT_EQ(highest, 100u);
}

TEST(AudioStream, GrowthIsCappedAndShrinks)
{
  FakeAudioStream stream(Params(AudioStretchMode::TimeStretch, 50));
  Sine sine;
  Listener ear;
  for (int i = 0; i < 6; i++)
    SecondThenStall(stream, sine, ear, 300);
  u32 last = stream.GetAndResetCounters().extra_ms;
  EXPECT_EQ(last, 100u);

  // Steady at 1x: back to nothing within about 50 s, never more than 10 ms at a time.
  for (int second = 0; second < 60; second++)
  {
    SecondThenStall(stream, sine, ear, 0);
    const u32 now = stream.GetAndResetCounters().extra_ms;
    EXPECT_LE(now, last);
    EXPECT_LE(last - now, 10u) << "second " << second;
    last = now;
  }
  EXPECT_EQ(last, 0u);
}

TEST(AudioStream, NoGrowthWithoutTimeStretch)
{
  for (const AudioStretchMode mode : {AudioStretchMode::Off, AudioStretchMode::Resample})
  {
    FakeAudioStream stream(Params(mode, 50));
    Sine sine;
    Listener ear;
    u32 highest = 0;
    for (int i = 0; i < 6; i++)
    {
      SecondThenStall(stream, sine, ear, 300);
      highest = std::max(highest, stream.GetAndResetCounters().extra_ms);
    }
    EXPECT_EQ(highest, 0u) << AudioStream::GetStretchModeName(mode);
  }
}

namespace {
// `seconds` of callbacks with `speed` times as much input written as the device reads; returns the loudest sample.
int RunAtSpeed(FakeAudioStream& stream, Sine& sine, Listener& ear, double speed, double seconds)
{
  int loudest = 0;
  double owed = 0.0;
  for (u32 i = 0; i < static_cast<u32>(seconds * RATE / 256); i++)
  {
    owed += speed * 256;
    const u32 chunks = static_cast<u32>(owed / 64);
    owed -= chunks * 64.0;
    if (chunks > 0)
      Write(stream, sine.Next(chunks * 64));
    for (const s16 v : ear.Read(stream, 256))
      loudest = std::max(loudest, std::abs(static_cast<int>(v)));
  }
  return loudest;
}
} // namespace

TEST(AudioStream, VeryFastInputFadesToSilence)
{
  FakeAudioStream stream(Params(AudioStretchMode::TimeStretch, 50));
  Sine sine;
  Listener ear;
  EXPECT_GT(RunAtSpeed(stream, sine, ear, 1.0, 1.0), 8000);
  stream.GetAndResetCounters();

  RunAtSpeed(stream, sine, ear, 6.0, 1.5);
  const size_t muted_from = ear.heard.size();
  const int loudest_muted = RunAtSpeed(stream, sine, ear, 6.0, 1.0);
  AudioStream::Counters c = stream.GetAndResetCounters();
  EXPECT_EQ(c.fast_forward_mutes, 1u);
  EXPECT_EQ(c.underruns, 0u);
  EXPECT_EQ(loudest_muted, 0) << "still sounding after " << muted_from / 2 << " frames";

  // Back at 1x, it fades back in within a second.
  EXPECT_GT(RunAtSpeed(stream, sine, ear, 1.0, 1.0), 8000);
  EXPECT_LE(MaxStep(ear.heard), MAX_STEP);
}

TEST(AudioStream, ModerateFastForwardKeepsPlaying)
{
  FakeAudioStream stream(Params(AudioStretchMode::TimeStretch, 50));
  Sine sine;
  Listener ear;
  RunAtSpeed(stream, sine, ear, 1.0, 1.0);
  for (int second = 0; second < 3; second++)
    EXPECT_GT(RunAtSpeed(stream, sine, ear, 3.0, 1.0), 8000) << "second " << second;
  EXPECT_EQ(stream.GetAndResetCounters().fast_forward_mutes, 0u);
}

TEST(AudioStream, MutedStreamNeverFastForwardMutes)
{
  FakeAudioStream stream(Params(AudioStretchMode::TimeStretch, 50));
  Sine sine;

  // No callbacks at all (the device paused), with input at any rate: no speed to judge.
  for (int i = 0; i < 100; i++)
    Write(stream, sine.Next(1024));
  EXPECT_EQ(stream.GetAndResetCounters().fast_forward_mutes, 0u);

  // Volume 0 at 6x: the emulation thread writes nothing.
  Listener ear;
  stream.SetOutputVolume(0);
  RunAtSpeed(stream, sine, ear, 6.0, 2.0);
  EXPECT_EQ(stream.GetAndResetCounters().fast_forward_mutes, 0u);
}

namespace {
// As RunAtSpeed, without waiting for the audio thread: it keeps up only as far as it can.
void RunAtSpeedNoSync(FakeAudioStream& stream, Sine& sine, Listener& ear, double speed, double seconds)
{
  double owed = 0.0;
  for (u32 i = 0; i < static_cast<u32>(seconds * RATE / 256); i++)
  {
    owed += speed * 256;
    const u32 chunks = static_cast<u32>(owed / 64);
    owed -= chunks * 64.0;
    if (chunks > 0)
      WriteNoSync(stream, sine.Next(chunks * 64));
    ear.Read(stream, 256);
  }
}
} // namespace

TEST(AudioStream, FastForwardMutesEvenWhenTheThreadLags)
{
  // The audio thread can't keep up (held), so the input ring overflows and clears: the speed is what the emulation
  // wrote, and those clears don't start the judging over, so it mutes as soon as the thread looks.
  FakeAudioStream stream(Params(AudioStretchMode::TimeStretch, 50));
  Sine sine;
  Listener ear;
  RunAtSpeed(stream, sine, ear, 1.0, 1.0);
  stream.GetAndResetCounters();

  stream.Hold(true);
  RunAtSpeedNoSync(stream, sine, ear, 6.0, 1.0);
  stream.Hold(false);
  RunAtSpeed(stream, sine, ear, 6.0, 0.3);
  EXPECT_EQ(stream.GetAndResetCounters().fast_forward_mutes, 1u);
}

TEST(AudioStream, CallbackStallDoesNotMute)
{
  // The device stops calling for 2 s (a Bluetooth switch) while the game runs at 1x: that input isn't speed.
  FakeAudioStream stream(Params(AudioStretchMode::TimeStretch, 50));
  Sine sine;
  Listener ear;
  RunAtSpeed(stream, sine, ear, 1.0, 1.0);
  for (u32 i = 0; i < 2 * RATE / 256; i++)
    Write(stream, sine.Next(256));
  RunAtSpeed(stream, sine, ear, 1.0, 1.5);
  EXPECT_EQ(stream.GetAndResetCounters().fast_forward_mutes, 0u);
}

TEST(AudioStream, OneDropoutDoesNotGrowTheBuffer)
{
  // A single stall (a boot's CD read, say) isn't dropouts that keep happening.
  FakeAudioStream stream(Params(AudioStretchMode::TimeStretch, 50));
  Sine sine;
  Listener ear;
  SecondThenStall(stream, sine, ear, 300);
  for (int i = 0; i < 3; i++)
    SecondThenStall(stream, sine, ear, 0);
  EXPECT_EQ(stream.GetAndResetCounters().extra_ms, 0u);
}

TEST(AudioStream, LoadStallsDoNotGrowTheBuffer)
{
  // A state load stalls the emulation, then jumps: its gap is the load's, not the game falling behind.
  FakeAudioStream stream(Params(AudioStretchMode::TimeStretch, 50));
  Sine sine;
  Listener ear;
  for (int i = 0; i < 6; i++)
  {
    SecondThenStall(stream, sine, ear, 300);
    stream.EmptyBuffer();
  }
  SecondThenStall(stream, sine, ear, 0);
  EXPECT_EQ(stream.GetAndResetCounters().extra_ms, 0u);
}
