// SPDX-FileCopyrightText: 2019-2024 Connor McLaughlin <stenzek@gmail.com>
// SPDX-License-Identifier: (GPL-3.0 OR CC-BY-NC-ND-4.0)

#include "audio_stream.h"
#include "host.h"

#include "common/align.h"
#include "common/assert.h"
#include "common/error.h"
#include "common/gsvector.h"
#include "common/log.h"
#include "common/settings_interface.h"
#include "common/timer.h"

#include "soundtouch/SoundTouch.h"
#include "soundtouch/SoundTouchDLL.h"

#ifndef __ANDROID__
#include "freesurround_decoder.h"
#endif

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <thread>

Log_SetChannel(AudioStream);

static constexpr bool LOG_TIMESTRETCH_STATS = false;

static constexpr const std::array<std::pair<u8, u8>, static_cast<size_t>(AudioExpansionMode::Count)>
  s_expansion_channel_count = {{
    {u8(2), u8(2)}, // Disabled
    {u8(3), u8(3)}, // StereoLFE
    {u8(5), u8(4)}, // Quadraphonic
    {u8(5), u8(5)}, // QuadraphonicLFE
    {u8(6), u8(6)}, // Surround51
    {u8(8), u8(8)}, // Surround71
  }};

AudioStream::DeviceInfo::DeviceInfo(std::string name_, std::string display_name_, u32 minimum_latency_)
  : name(std::move(name_)), display_name(std::move(display_name_)), minimum_latency_frames(minimum_latency_)
{
}

AudioStream::DeviceInfo::~DeviceInfo() = default;

AudioStream::AudioStream(u32 sample_rate, const AudioStreamParameters& parameters)
  : m_sample_rate(sample_rate), m_parameters(parameters),
    m_internal_channels(s_expansion_channel_count[static_cast<size_t>(parameters.expansion_mode)].first),
    m_output_channels(s_expansion_channel_count[static_cast<size_t>(parameters.expansion_mode)].second)
{
}

AudioStream::~AudioStream()
{
  StretchDestroy();
  DestroyBuffer();
}

std::unique_ptr<AudioStream> AudioStream::CreateNullStream(u32 sample_rate, u32 buffer_ms)
{
  // no point stretching with no output
  AudioStreamParameters params;
  params.expansion_mode = AudioExpansionMode::Disabled;
  params.stretch_mode = AudioStretchMode::Off;
  params.buffer_ms = static_cast<u16>(buffer_ms);

  std::unique_ptr<AudioStream> stream(new AudioStream(sample_rate, params));
  stream->BaseInitialize(&StereoSampleReaderImpl);
  stream->m_discard_writes = true;
  return stream;
}

#ifdef __ANDROID__

std::unique_ptr<AudioStream>
AudioStream::CreateOpenSLESAudioStream(u32 sample_rate, const AudioStreamParameters& parameters, Error* error)
{
  Error::SetStringView(error, "The OpenSL ES audio backend is not supported, use AAudio.");
  return nullptr;
}

#endif

std::vector<std::pair<std::string, std::string>> AudioStream::GetDriverNames(AudioBackend backend)
{
  std::vector<std::pair<std::string, std::string>> ret;
  switch (backend)
  {
#ifndef __ANDROID__
    case AudioBackend::Cubeb:
      ret = GetCubebDriverNames();
      break;
#endif

    default:
      break;
  }

  return ret;
}

std::vector<AudioStream::DeviceInfo> AudioStream::GetOutputDevices(AudioBackend backend, const char* driver,
                                                                   u32 sample_rate)
{
  std::vector<AudioStream::DeviceInfo> ret;
  switch (backend)
  {
#ifndef __ANDROID__
    case AudioBackend::Cubeb:
      ret = GetCubebOutputDevices(driver, sample_rate);
      break;
#endif

    default:
      break;
  }

  return ret;
}

std::unique_ptr<AudioStream> AudioStream::CreateStream(AudioBackend backend, u32 sample_rate,
                                                       const AudioStreamParameters& parameters, const char* driver_name,
                                                       const char* device_name, Error* error /* = nullptr */)
{
  switch (backend)
  {
#ifndef __ANDROID__
    case AudioBackend::Cubeb:
      return CreateCubebAudioStream(sample_rate, parameters, driver_name, device_name, error);

    case AudioBackend::SDL:
      return CreateSDLAudioStream(sample_rate, parameters, error);
#else
    case AudioBackend::AAudio:
      return CreateAAudioAudioStream(sample_rate, parameters, error);

    case AudioBackend::OpenSLES:
      return CreateOpenSLESAudioStream(sample_rate, parameters, error);
#endif

    case AudioBackend::Null:
      return CreateNullStream(sample_rate, parameters.buffer_ms);

    default:
      Error::SetStringView(error, "Unknown audio backend.");
      return nullptr;
  }
}

u32 AudioStream::GetAlignedBufferSize(u32 size)
{
  static_assert(Common::IsPow2(CHUNK_SIZE));
  return Common::AlignUpPow2(size, CHUNK_SIZE);
}

u32 AudioStream::GetBufferSizeForMS(u32 sample_rate, u32 ms)
{
  return GetAlignedBufferSize((ms * sample_rate) / 1000u);
}

u32 AudioStream::GetMSForBufferSize(u32 sample_rate, u32 buffer_size)
{
  buffer_size = GetAlignedBufferSize(buffer_size);
  return (buffer_size * 1000u) / sample_rate;
}

static constexpr const std::array s_backend_names = {
  "Null",
#ifndef __ANDROID__
  "Cubeb",
  "SDL",
#else
  "AAudio",
  "OpenSLES",
#endif
};
static constexpr const std::array s_backend_display_names = {
  TRANSLATE_NOOP("AudioStream", "Null (No Output)"),
#ifndef __ANDROID__
  TRANSLATE_NOOP("AudioStream", "Cubeb"),
  TRANSLATE_NOOP("AudioStream", "SDL"),
#else
  "AAudio",
  "OpenSL ES",
#endif
};

std::optional<AudioBackend> AudioStream::ParseBackendName(const char* str)
{
  int index = 0;
  for (const char* name : s_backend_names)
  {
    if (std::strcmp(name, str) == 0)
      return static_cast<AudioBackend>(index);

    index++;
  }

  return std::nullopt;
}

const char* AudioStream::GetBackendName(AudioBackend backend)
{
  return s_backend_names[static_cast<int>(backend)];
}

const char* AudioStream::GetBackendDisplayName(AudioBackend backend)
{
  return Host::TranslateToCString("AudioStream", s_backend_display_names[static_cast<int>(backend)]);
}

static constexpr const std::array s_expansion_mode_names = {
  "Disabled", "StereoLFE", "Quadraphonic", "QuadraphonicLFE", "Surround51", "Surround71",
};
static constexpr const std::array s_expansion_mode_display_names = {
  TRANSLATE_NOOP("AudioStream", "Disabled (Stereo)"), TRANSLATE_NOOP("AudioStream", "Stereo with LFE"),
  TRANSLATE_NOOP("AudioStream", "Quadraphonic"),      TRANSLATE_NOOP("AudioStream", "Quadraphonic with LFE"),
  TRANSLATE_NOOP("AudioStream", "5.1 Surround"),      TRANSLATE_NOOP("AudioStream", "7.1 Surround"),
};

const char* AudioStream::GetExpansionModeName(AudioExpansionMode mode)
{
  return (static_cast<u32>(mode) < s_expansion_mode_names.size()) ? s_expansion_mode_names[static_cast<u32>(mode)] : "";
}

const char* AudioStream::GetExpansionModeDisplayName(AudioExpansionMode mode)
{
  return (static_cast<u32>(mode) < s_expansion_mode_display_names.size()) ?
           Host::TranslateToCString("AudioStream", s_expansion_mode_display_names[static_cast<u32>(mode)]) :
           "";
}

std::optional<AudioExpansionMode> AudioStream::ParseExpansionMode(const char* name)
{
  for (u8 i = 0; i < static_cast<u8>(AudioExpansionMode::Count); i++)
  {
    if (std::strcmp(name, s_expansion_mode_names[i]) == 0)
      return static_cast<AudioExpansionMode>(i);
  }

  return std::nullopt;
}

static constexpr const std::array s_stretch_mode_names = {
  "None",
  "Resample",
  "TimeStretch",
};
static constexpr const std::array s_stretch_mode_display_names = {
  TRANSLATE_NOOP("AudioStream", "Off (Noisy)"),
  TRANSLATE_NOOP("AudioStream", "Resampling (Pitch Shift)"),
  TRANSLATE_NOOP("AudioStream", "Time Stretch (Tempo Change, Best Sound)"),
};

const char* AudioStream::GetStretchModeName(AudioStretchMode mode)
{
  return (static_cast<u32>(mode) < s_stretch_mode_names.size()) ? s_stretch_mode_names[static_cast<u32>(mode)] : "";
}

const char* AudioStream::GetStretchModeDisplayName(AudioStretchMode mode)
{
  return (static_cast<u32>(mode) < s_stretch_mode_display_names.size()) ?
           Host::TranslateToCString("AudioStream", s_stretch_mode_display_names[static_cast<u32>(mode)]) :
           "";
}

std::optional<AudioStretchMode> AudioStream::ParseStretchMode(const char* name)
{
  for (u8 i = 0; i < static_cast<u8>(AudioStretchMode::Count); i++)
  {
    if (std::strcmp(name, s_stretch_mode_names[i]) == 0)
      return static_cast<AudioStretchMode>(i);
  }

  return std::nullopt;
}

u32 AudioStream::GetBufferedFramesRelaxed() const
{
  const u32 rpos = m_rpos.load(std::memory_order_relaxed);
  const u32 wpos = m_wpos.load(std::memory_order_relaxed);
  return (wpos + m_buffer_size - rpos) % m_buffer_size;
}

AudioStream::Counters AudioStream::GetAndResetCounters()
{
  Counters c;
  c.underruns = m_underrun_count.exchange(0, std::memory_order_relaxed);
  c.overflows = m_overflow_count.exchange(0, std::memory_order_relaxed);
  c.fades = m_fade_count.exchange(0, std::memory_order_relaxed);
  c.clears = m_clear_count.exchange(0, std::memory_order_relaxed);
  c.cuts = m_cut_count.exchange(0, std::memory_order_relaxed);
  return c;
}

void AudioStream::ReadFromRing(SampleType* samples, u32 num_frames)
{
  if (num_frames == 0)
    return;

  u32 rpos = m_rpos.load(std::memory_order_relaxed);

  // towards the end of the buffer
  const u32 end = std::min(m_buffer_size - rpos, num_frames);
  if (end > 0)
  {
    m_sample_reader(samples, &m_buffer[rpos * m_internal_channels], end);
    rpos += end;
    rpos = (rpos == m_buffer_size) ? 0 : rpos;
  }

  // after wrapping around
  const u32 start = num_frames - end;
  if (start > 0)
  {
    m_sample_reader(&samples[end * m_output_channels], &m_buffer[0], start);
    rpos = start;
  }

  m_rpos.store(rpos, std::memory_order_release);
}

void AudioStream::ApplyClear(u32 wpos)
{
  // The callback may already have read past the clear's position (written after it, and read before the request was
  // seen): then there's nothing stale left, and the read position never moves back.
  const u32 rpos = m_rpos.load(std::memory_order_relaxed);
  const u32 stale = (m_clear_pos + m_buffer_size - rpos) % m_buffer_size;
  const u32 available = (wpos + m_buffer_size - rpos) % m_buffer_size;
  if (stale <= available)
    m_rpos.store(m_clear_pos, std::memory_order_release);

  m_clear_pending = false;
  m_cleared_generation.store(m_seen_clear_generation, std::memory_order_release);
}

void AudioStream::ReadFrames(SampleType* samples, u32 num_frames)
{
  const u32 channels = m_output_channels;

  // The request first: the write position read after it is at or beyond the clear's.
  const u64 clear_request = m_clear_request.load(std::memory_order_acquire);
  if (static_cast<u32>(clear_request >> 32) != m_seen_clear_generation)
  {
    m_seen_clear_generation = static_cast<u32>(clear_request >> 32);
    m_clear_pos = static_cast<u32>(clear_request);
    m_clear_pending = true;
    m_clear_count.fetch_add(1, std::memory_order_relaxed);
    if (m_play_state == PlayState::Playing)
    {
      m_play_state = PlayState::FadingOut;
      if (m_volume.load(std::memory_order_relaxed) != 0)
        m_fade_count.fetch_add(1, std::memory_order_relaxed);
    }
  }

  const u32 wpos = m_wpos.load(std::memory_order_acquire);
  if (m_play_state == PlayState::Filling && m_clear_pending)
    ApplyClear(wpos);

  const u32 rpos = m_rpos.load(std::memory_order_relaxed);
  const u32 available = (wpos + m_buffer_size - rpos) % m_buffer_size;

  // Silent, consuming nothing, until enough is buffered that this callback and a fade-out fit.
  if (m_play_state == PlayState::Filling)
  {
    const u32 restart = std::min(std::max(m_restart_frames, num_frames + m_fade_frames), m_restart_cap);
    if (available < restart)
    {
      // Nothing is heard, so a volume change needs no ramp.
      m_current_volume = static_cast<float>(m_volume.load(std::memory_order_relaxed)) / 100.0f;
      std::memset(samples, 0, num_frames * channels * sizeof(SampleType));
      return;
    }

    m_play_state = PlayState::Playing;
    m_fade_pos = 0;
  }

  // The frames consumed always come first: all of them while playing (a fade-out that runs out of data starts when
  // what's left equals the frames it takes), or what the rest of a fade-out needs.
  bool running_out = false;
  u32 consume;
  if (m_play_state == PlayState::FadingOut)
  {
    // A clear's fade-out plays only what's before the clear.
    u32 limit = m_fade_holding ? 0 : available;
    if (m_clear_pending && !m_fade_holding)
    {
      const u32 stale = (m_clear_pos + m_buffer_size - rpos) % m_buffer_size;
      limit = (stale <= available) ? stale : 0;
    }
    consume = std::min({limit, m_fade_pos, num_frames});
  }
  else if (available < num_frames)
  {
    consume = available;
    running_out = true;
  }
  else
  {
    consume = num_frames;
  }

  ReadFromRing(samples, consume);
  if (consume > 0)
    std::memcpy(m_last_frame.data(), &samples[(consume - 1) * channels], channels * sizeof(SampleType));

  if (m_play_state != PlayState::Playing || m_fade_pos != m_fade_frames || running_out)
  {
    for (u32 i = 0; i < num_frames; i++)
    {
      SampleType* const frame = &samples[i * channels];
      if (m_play_state == PlayState::Playing)
      {
        if (!running_out || (consume - std::min(i, consume)) > m_fade_pos)
        {
          m_fade_pos += (m_fade_pos < m_fade_frames) ? 1u : 0u;
          const float gain = m_fade_curve[m_fade_pos];
          for (u32 c = 0; c < channels; c++)
            frame[c] = static_cast<SampleType>(std::lrint(static_cast<float>(frame[c]) * gain));
          continue;
        }

        // What's left only just covers a fade-out. Muted, the producer writes nothing, which isn't an underrun.
        m_play_state = PlayState::FadingOut;
        if (m_volume.load(std::memory_order_relaxed) != 0)
        {
          m_underrun_count.fetch_add(1, std::memory_order_relaxed);
          m_fade_count.fetch_add(1, std::memory_order_relaxed);
        }
        if (m_parameters.stretch_mode == AudioStretchMode::TimeStretch)
          StretchUnderrun();
      }

      if (m_play_state == PlayState::FadingOut && m_fade_pos > 0)
      {
        // Past the data, the last frame played is held as it fades, so the fade always has its full length.
        m_fade_holding |= (i >= consume);
        const SampleType* const src = m_fade_holding ? m_last_frame.data() : frame;
        const float gain = m_fade_curve[m_fade_pos];
        for (u32 c = 0; c < channels; c++)
          frame[c] = static_cast<SampleType>(std::lrint(static_cast<float>(src[c]) * gain));
        if (--m_fade_pos == 0)
        {
          m_play_state = PlayState::Filling;
          m_fade_holding = false;
        }
        continue;
      }

      m_play_state = PlayState::Filling;
      std::memset(frame, 0, channels * sizeof(SampleType));
    }
  }

  if (m_play_state == PlayState::Filling && m_clear_pending)
    ApplyClear(wpos);

  ApplyOutputVolume(samples, num_frames);
}

void AudioStream::ApplyOutputVolume(SampleType* samples, u32 num_frames)
{
  const u32 target_volume = m_volume.load(std::memory_order_relaxed);
  const float target = static_cast<float>(target_volume) / 100.0f;
  if (m_current_volume != target)
  {
    // A step a callback would click: ramp, frame by frame.
    const float step = 100.0f / static_cast<float>(m_sample_rate * VOLUME_RAMP_MS / 10u);
    const u32 channels = m_output_channels;
    u32 i = 0;
    for (; i < num_frames && m_current_volume != target; i++)
    {
      m_current_volume = (m_current_volume < target) ? std::min(m_current_volume + step, target) :
                                                       std::max(m_current_volume - step, target);
      for (u32 c = 0; c < channels; c++)
      {
        SampleType& v = samples[i * channels + c];
        v = static_cast<SampleType>(std::clamp(std::lrint(static_cast<float>(v) * m_current_volume), -32768l, 32767l));
      }
    }

    samples += i * channels;
    num_frames -= i;
  }

  if (target_volume != 100)
  {
    const float volume_mult = target;
    u32 num_samples = num_frames * m_output_channels;

    const u32 aligned_samples = Common::AlignDownPow2(num_samples, 8);
    num_samples -= aligned_samples;

    const GSVector4 volume_multv = GSVector4(volume_mult);
    const SampleType* const aligned_samples_end = samples + aligned_samples;
    for (; samples != aligned_samples_end; samples += 8)
    {
      GSVector4i iv = GSVector4i::load<false>(samples); // [0, 1, 2, 3, 4, 5, 6, 7]
      GSVector4i iv1 = iv.upl16(iv);                    // [0, 0, 1, 1, 2, 2, 3, 3]
      GSVector4i iv2 = iv.uph16(iv);                    // [4, 4, 5, 5, 6, 6, 7, 7]
      iv1 = iv1.sra32<16>();                            // [0, 1, 2, 3]
      iv2 = iv2.sra32<16>();                            // [4, 5, 6, 7]
      GSVector4 fv1 = GSVector4(iv1);                   // [f0, f1, f2, f3]
      GSVector4 fv2 = GSVector4(iv2);                   // [f4, f5, f6, f7]
      fv1 = fv1 * volume_multv;                         // [f0, f1, f2, f3]
      fv2 = fv2 * volume_multv;                         // [f4, f5, f6, f7]
      iv1 = GSVector4i(fv1);                            // [0, 1, 2, 3]
      iv2 = GSVector4i(fv2);                            // [4, 5, 6, 7]
      iv = iv1.ps32(iv2);                               // [0, 1, 2, 3, 4, 5, 6, 7]
      GSVector4i::store<false>(samples, iv);
    }

    while (num_samples > 0)
    {
      *samples = static_cast<s16>(std::clamp(static_cast<float>(*samples) * volume_mult, -32768.0f, 32767.0f));
      samples++;
      num_samples--;
    }
  }
}

void AudioStream::StereoSampleReaderImpl(SampleType* dest, const SampleType* src, u32 num_frames)
{
  std::memcpy(dest, src, num_frames * 2 * sizeof(SampleType));
}

void AudioStream::InternalWriteFrames(s16* data, u32 num_frames)
{
  // Acquire: the callback is done with the frames it has released.
  const u32 rpos = m_rpos.load(std::memory_order_acquire);
  u32 wpos = m_wpos.load(std::memory_order_relaxed);
  const u32 buffered = (wpos + m_buffer_size - rpos) % m_buffer_size;
  const u32 free = m_buffer_size - 1 - buffered;

  if (m_write_state == WriteState::Normal && free < num_frames + m_overflow_high_water)
  {
    // Nearly full: fade out, drop, and give the stretcher time to slow down. The read position is the callback's.
    m_write_state = WriteState::FadingOut;
    m_write_fade_pos = m_write_fade_frames;
    m_overflow_count.fetch_add(1, std::memory_order_relaxed);
    m_fade_count.fetch_add(1, std::memory_order_relaxed);
    m_stretch_reset.fetch_add(1, std::memory_order_relaxed);
    DEBUG_LOG("Buffer overrun, fading out");
  }
  if (m_write_state == WriteState::Dropping)
  {
    if (buffered > m_buffer_size / 2)
      return;

    m_write_state = WriteState::FadingIn;
    m_write_fade_pos = 0;
  }

  const u32 channels = m_internal_channels;
  if (m_write_state != WriteState::Normal)
  {
    const auto scale = [this, data, channels](u32 frame) {
      const float gain = m_fade_curve[(m_write_fade_pos * m_fade_frames) / m_write_fade_frames];
      for (u32 c = 0; c < channels; c++)
        data[frame * channels + c] = static_cast<s16>(std::lrint(static_cast<float>(data[frame * channels + c]) * gain));
    };

    if (m_write_state == WriteState::FadingOut)
    {
      u32 i = 0;
      for (; i < num_frames && m_write_fade_pos > 0; i++, m_write_fade_pos--)
        scale(i);
      if (m_write_fade_pos == 0)
      {
        m_write_state = WriteState::Dropping;
        num_frames = i;
      }
    }
    else
    {
      for (u32 i = 0; i < num_frames && m_write_state == WriteState::FadingIn; i++)
      {
        m_write_fade_pos++;
        scale(i);
        if (m_write_fade_pos == m_write_fade_frames)
          m_write_state = WriteState::Normal;
      }
    }
  }

  // The high-water mark leaves room for a fade-out; never write over what the callback hasn't read.
  num_frames = std::min(num_frames, free);
  if (num_frames == 0)
    return;

  // wrapping around the end of the buffer?
  if ((m_buffer_size - wpos) <= num_frames)
  {
    // needs to be written in two parts
    const u32 end = m_buffer_size - wpos;
    const u32 start = num_frames - end;

    // start is zero when this chunk reaches exactly the end
    std::memcpy(&m_buffer[wpos * channels], data, end * channels * sizeof(SampleType));
    if (start > 0)
      std::memcpy(&m_buffer[0], data + end * channels, start * channels * sizeof(SampleType));

    wpos = start;
  }
  else
  {
    // no split
    std::memcpy(&m_buffer[wpos * channels], data, num_frames * channels * sizeof(SampleType));
    wpos += num_frames;
  }

  m_wpos.store(wpos, std::memory_order_release);
  m_written_since_clear = true;
}

void AudioStream::BaseInitialize(SampleReader sample_reader)
{
  m_sample_reader = sample_reader;

  AllocateBuffer();
#ifndef __ANDROID__
  ExpandAllocate();
#else
  if (IsExpansionEnabled())
    Panic("Attempting to use expansion on Android.");
#endif
  StretchAllocate();
}

void AudioStream::AllocateBuffer()
{
  // use a larger buffer when time stretching, since we need more input
  // TODO: do we really? it's more the output...
  const u32 multiplier = (m_parameters.stretch_mode == AudioStretchMode::TimeStretch) ?
                           16 :
                           ((m_parameters.stretch_mode == AudioStretchMode::Off) ? 1 : 2);
  m_buffer_size = GetAlignedBufferSize(((m_parameters.buffer_ms * multiplier) * m_sample_rate) / 1000);
  m_target_buffer_size = GetAlignedBufferSize((m_sample_rate * m_parameters.buffer_ms) / 1000u);

  // A raised cosine, from silent (0) to full (m_fade_frames).
  m_fade_frames = std::max<u32>((m_sample_rate * FADE_MS) / 1000u, 1u);
  m_fade_curve = std::make_unique<float[]>(m_fade_frames + 1);
  for (u32 i = 0; i <= m_fade_frames; i++)
    m_fade_curve[i] = 0.5f - 0.5f * std::cos(3.14159265358979f * static_cast<float>(i) / static_cast<float>(m_fade_frames));

  // An overflow's fade-out needs room above the high-water mark; on a tiny ring it's shorter, to fit.
  m_write_fade_frames = std::clamp<u32>(m_buffer_size / 8, 1u, m_fade_frames);
  m_overflow_high_water = m_write_fade_frames + 2 * CHUNK_SIZE;
  m_write_state = WriteState::Normal;

  // Restarting after a gap waits for half the target: a restart on a few milliseconds stops again at once. The cap
  // keeps it below the overflow's high-water mark, so a small buffer can still restart.
  m_restart_cap = (m_buffer_size > m_overflow_high_water + 3 * CHUNK_SIZE) ?
                    (m_buffer_size - m_overflow_high_water - 2 * CHUNK_SIZE) :
                    CHUNK_SIZE;
  m_restart_frames = std::clamp(m_target_buffer_size / 2, CHUNK_SIZE, m_restart_cap);
  m_play_state = PlayState::Filling;
  m_fade_pos = 0;

  m_buffer = std::make_unique<s16[]>(m_buffer_size * m_internal_channels);
  m_staging_buffer = std::make_unique<s16[]>(CHUNK_SIZE * m_internal_channels);
  m_float_buffer = std::make_unique<float[]>(CHUNK_SIZE * m_internal_channels);

#ifndef __ANDROID__
  if (IsExpansionEnabled())
    m_expand_buffer = std::make_unique<float[]>(m_parameters.expand_block_size * NUM_INPUT_CHANNELS);
#endif

  DEV_LOG(
    "Allocated buffer of {} frames for buffer of {} ms [expansion {} (block size {}), stretch {}, target size {}].",
    m_buffer_size, m_parameters.buffer_ms, GetExpansionModeName(m_parameters.expansion_mode),
    m_parameters.expand_block_size, GetStretchModeName(m_parameters.stretch_mode), m_target_buffer_size);
}

void AudioStream::DestroyBuffer()
{
#ifndef __ANDROID__
  m_expand_buffer.reset();
#endif
  m_staging_buffer.reset();
  m_float_buffer.reset();
  m_buffer.reset();
  m_buffer_size = 0;
  m_wpos.store(0, std::memory_order_release);
  m_rpos.store(0, std::memory_order_release);
}

void AudioStream::EmptyBuffer()
{
#ifndef __ANDROID__
  if (IsExpansionEnabled())
  {
    m_expander->Flush();
    m_expand_output_buffer = nullptr;
    m_expand_buffer_pos = 0;
  }
#endif

  if (IsStretchEnabled())
  {
    soundtouch_clear(m_soundtouch);
    if (m_parameters.stretch_mode == AudioStretchMode::TimeStretch)
      soundtouch_setTempo(m_soundtouch, m_nominal_rate);

    // The tempo's averaging starts again from the new audio.
    m_stretch_reset.store(STRETCH_RESET_THRESHOLD, std::memory_order_relaxed);
  }

  m_staging_buffer_pos = 0;
  m_write_state = WriteState::Normal;
  m_written_since_clear = false;
  m_tempo_hold = (m_parameters.stretch_mode == AudioStretchMode::TimeStretch);

  // The consumer moves its own read position; this thread never writes it.
  m_clear_generation++;
  m_clear_request.store((static_cast<u64>(m_clear_generation) << 32) | m_wpos.load(std::memory_order_relaxed),
                        std::memory_order_release);
}

void AudioStream::SetNominalRate(float tempo)
{
  m_nominal_rate = tempo;
  if (m_parameters.stretch_mode == AudioStretchMode::Resample)
    soundtouch_setRate(m_soundtouch, tempo);
  else if (m_parameters.stretch_mode == AudioStretchMode::TimeStretch && m_stretch_inactive)
    soundtouch_setTempo(m_soundtouch, tempo);
}

void AudioStream::SetPaused(bool paused)
{
  if (paused == m_paused)
    return;

  // Nothing to fade: no output, or nothing written since the last clear was applied (a stream made while paused).
  const bool silent = m_discard_writes || (!m_written_since_clear && m_cleared_generation.load(std::memory_order_acquire) ==
                                                                          m_clear_generation);
  if (paused && !silent)
  {
    // Stopping the device mid-sound clicks: fade out first. Without callbacks (a device gone, or one that never
    // started), the wait gives up, and the stop is a cut.
    EmptyBuffer();
    const u32 generation = m_clear_generation;
    const Common::Timer::Value deadline =
      Common::Timer::GetCurrentValue() + Common::Timer::ConvertMillisecondsToValue(PAUSE_FADE_TIMEOUT_MS);
    while (m_cleared_generation.load(std::memory_order_acquire) != generation)
    {
      if (Common::Timer::GetCurrentValue() >= deadline)
      {
        m_cut_count.fetch_add(1, std::memory_order_relaxed);
        WARNING_LOG("Audio didn't fade out within {} ms, pausing anyway.", PAUSE_FADE_TIMEOUT_MS);
        break;
      }

      std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }

    // The fade is in the device's buffer, ahead of what's heard: let the device play it before stopping.
    if (m_cleared_generation.load(std::memory_order_acquire) == generation)
    {
      const u32 latency_ms = std::min(
        GetMSForBufferSize(m_sample_rate, m_device_latency_frames.load(std::memory_order_relaxed)) + 1u,
        PAUSE_FADE_TIMEOUT_MS);
      std::this_thread::sleep_for(std::chrono::milliseconds(latency_ms));
    }
  }

  PauseDevice(paused);
}

void AudioStream::SetDeviceLatencyFrames(u32 frames)
{
  m_device_latency_frames.store(frames, std::memory_order_relaxed);
}

void AudioStream::PauseDevice(bool paused)
{
  m_paused = paused;
}

void AudioStream::SetOutputVolume(u32 volume)
{
  m_volume.store(volume, std::memory_order_relaxed);
}

void AudioStream::BeginWrite(SampleType** buffer_ptr, u32* num_frames)
{
  // TODO: Write directly to buffer when not using stretching.
  *buffer_ptr = &m_staging_buffer[m_staging_buffer_pos];
  *num_frames = CHUNK_SIZE - (m_staging_buffer_pos / NUM_INPUT_CHANNELS);
}

static void S16ChunkToFloat(const s16* src, float* dst, u32 num_samples)
{
  constexpr GSVector4 S16_TO_FLOAT_V = GSVector4::cxpr(1.0f / 32767.0f);

  const u32 iterations = (num_samples + 7) / 8;
  for (u32 i = 0; i < iterations; i++)
  {
    const GSVector4i sv = GSVector4i::load<false>(src);
    src += 8;

    GSVector4i iv1 = sv.upl16(sv);  // [0, 0, 1, 1, 2, 2, 3, 3]
    GSVector4i iv2 = sv.uph16(sv);  // [4, 4, 5, 5, 6, 6, 7, 7]
    iv1 = iv1.sra32<16>();          // [0, 1, 2, 3]
    iv2 = iv2.sra32<16>();          // [4, 5, 6, 7]
    GSVector4 fv1 = GSVector4(iv1); // [f0, f1, f2, f3]
    GSVector4 fv2 = GSVector4(iv2); // [f4, f5, f6, f7]
    fv1 = fv1 * S16_TO_FLOAT_V;
    fv2 = fv2 * S16_TO_FLOAT_V;

    GSVector4::store<false>(dst + 0, fv1);
    GSVector4::store<false>(dst + 4, fv2);
    dst += 8;
  }
}

static void FloatChunkToS16(s16* dst, const float* src, u32 num_samples)
{
  const GSVector4 FLOAT_TO_S16_V = GSVector4::cxpr(32767.0f);

  const u32 iterations = (num_samples + 7) / 8;
  for (u32 i = 0; i < iterations; i++)
  {
    GSVector4 fv1 = GSVector4::load<false>(src + 0);
    GSVector4 fv2 = GSVector4::load<false>(src + 4);
    src += 8;

    fv1 = fv1 * FLOAT_TO_S16_V;
    fv2 = fv2 * FLOAT_TO_S16_V;
    GSVector4i iv1 = GSVector4i(fv1);
    GSVector4i iv2 = GSVector4i(fv2);

    const GSVector4i iv = iv1.ps32(iv2);
    GSVector4i::store<false>(dst, iv);
    dst += 8;
  }
}

#ifndef __ANDROID__

void AudioStream::ExpandAllocate()
{
  DebugAssert(!m_expander);
  if (m_parameters.expansion_mode == AudioExpansionMode::Disabled)
    return;

  static constexpr std::array<std::pair<FreeSurroundDecoder::ChannelSetup, bool>,
                              static_cast<size_t>(AudioExpansionMode::Count)>
    channel_setup_mapping = {{
      {FreeSurroundDecoder::ChannelSetup::Stereo, false},     // Disabled
      {FreeSurroundDecoder::ChannelSetup::Stereo, true},      // StereoLFE
      {FreeSurroundDecoder::ChannelSetup::Surround41, false}, // Quadraphonic
      {FreeSurroundDecoder::ChannelSetup::Surround41, true},  // QuadraphonicLFE
      {FreeSurroundDecoder::ChannelSetup::Surround51, true},  // Surround51
      {FreeSurroundDecoder::ChannelSetup::Surround71, true},  // Surround71
    }};

  const auto [fs_setup, fs_lfe] = channel_setup_mapping[static_cast<size_t>(m_parameters.expansion_mode)];

  m_expander = std::make_unique<FreeSurroundDecoder>(fs_setup, m_parameters.expand_block_size);
  m_expander->SetBassRedirection(fs_lfe);
  m_expander->SetCircularWrap(m_parameters.expand_circular_wrap);
  m_expander->SetShift(m_parameters.expand_shift);
  m_expander->SetDepth(m_parameters.expand_depth);
  m_expander->SetFocus(m_parameters.expand_focus);
  m_expander->SetCenterImage(m_parameters.expand_center_image);
  m_expander->SetFrontSeparation(m_parameters.expand_front_separation);
  m_expander->SetRearSeparation(m_parameters.expand_rear_separation);
  m_expander->SetLowCutoff(static_cast<float>(m_parameters.expand_low_cutoff) / m_sample_rate * 2);
  m_expander->SetHighCutoff(static_cast<float>(m_parameters.expand_high_cutoff) / m_sample_rate * 2);
}

#endif

void AudioStream::EndWrite(u32 num_frames)
{
  // don't bother committing anything when muted, or with no output
  if (m_discard_writes || m_volume.load(std::memory_order_relaxed) == 0)
    return;

  m_staging_buffer_pos += num_frames * NUM_INPUT_CHANNELS;
  DebugAssert(m_staging_buffer_pos <= (CHUNK_SIZE * NUM_INPUT_CHANNELS));
  if ((m_staging_buffer_pos / NUM_INPUT_CHANNELS) < CHUNK_SIZE)
    return;

  m_staging_buffer_pos = 0;

  if (!IsExpansionEnabled() && !IsStretchEnabled())
  {
    InternalWriteFrames(m_staging_buffer.get(), CHUNK_SIZE);
    return;
  }

#ifndef __ANDROID__
  if (IsExpansionEnabled())
  {
    // StretchWriteBlock() overwrites the staging buffer on output, so we need to copy into the expand buffer first.
    S16ChunkToFloat(m_staging_buffer.get(), m_expand_buffer.get() + m_expand_buffer_pos * NUM_INPUT_CHANNELS,
                    CHUNK_SIZE * NUM_INPUT_CHANNELS);

    // Output the corresponding block.
    if (m_expand_output_buffer)
      StretchWriteBlock(m_expand_output_buffer + m_expand_buffer_pos * m_internal_channels);

    // Decode the next block if we buffered enough.
    m_expand_buffer_pos += CHUNK_SIZE;
    if (m_expand_buffer_pos == m_parameters.expand_block_size)
    {
      m_expand_buffer_pos = 0;
      m_expand_output_buffer = m_expander->Decode(m_expand_buffer.get());
    }
  }
  else
#endif
  {
    S16ChunkToFloat(m_staging_buffer.get(), m_float_buffer.get(), CHUNK_SIZE * NUM_INPUT_CHANNELS);
    StretchWriteBlock(m_float_buffer.get());
  }
}

// Time stretching algorithm based on PCSX2 implementation.

template<class T>
ALWAYS_INLINE static bool IsInRange(const T& val, const T& min, const T& max)
{
  return (min <= val && val <= max);
}

void AudioStream::StretchAllocate()
{
  if (m_parameters.stretch_mode == AudioStretchMode::Off)
    return;

  m_soundtouch = soundtouch_createInstance();
  soundtouch_setSampleRate(m_soundtouch, m_sample_rate);
  soundtouch_setChannels(m_soundtouch, m_internal_channels);

  soundtouch_setSetting(m_soundtouch, SETTING_USE_QUICKSEEK, m_parameters.stretch_use_quickseek);
  soundtouch_setSetting(m_soundtouch, SETTING_USE_AA_FILTER, m_parameters.stretch_use_aa_filter);

  soundtouch_setSetting(m_soundtouch, SETTING_SEQUENCE_MS, m_parameters.stretch_sequence_length_ms);
  soundtouch_setSetting(m_soundtouch, SETTING_SEEKWINDOW_MS, m_parameters.stretch_seekwindow_ms);
  soundtouch_setSetting(m_soundtouch, SETTING_OVERLAP_MS, m_parameters.stretch_overlap_ms);

  if (m_parameters.stretch_mode == AudioStretchMode::Resample)
    soundtouch_setRate(m_soundtouch, m_nominal_rate);
  else
    soundtouch_setTempo(m_soundtouch, m_nominal_rate);

  m_stretch_reset.store(STRETCH_RESET_THRESHOLD, std::memory_order_relaxed);
  m_tempo_hold = (m_parameters.stretch_mode == AudioStretchMode::TimeStretch);
  m_stretch_inactive = false;
  m_stretch_ok_count = 0;
  m_dynamic_target_usage = 0.0f;
  m_average_position = 0;
  m_average_available = 0;

  m_staging_buffer_pos = 0;
}

void AudioStream::StretchDestroy()
{
  if (m_soundtouch)
  {
    soundtouch_destroyInstance(m_soundtouch);
    m_soundtouch = nullptr;
  }
}

void AudioStream::StretchWriteBlock(const float* block)
{
  if (IsStretchEnabled())
  {
    soundtouch_putSamples(m_soundtouch, block, CHUNK_SIZE);

    u32 tempProgress;
    while (tempProgress = soundtouch_receiveSamples(m_soundtouch, m_float_buffer.get(), CHUNK_SIZE), tempProgress != 0)
    {
      FloatChunkToS16(m_staging_buffer.get(), m_float_buffer.get(), tempProgress * m_internal_channels);
      InternalWriteFrames(m_staging_buffer.get(), tempProgress);
    }

    if (m_parameters.stretch_mode == AudioStretchMode::TimeStretch)
      UpdateStretchTempo();
  }
  else
  {
    FloatChunkToS16(m_staging_buffer.get(), block, CHUNK_SIZE * m_internal_channels);
    InternalWriteFrames(m_staging_buffer.get(), CHUNK_SIZE);
  }
}

float AudioStream::AddAndGetAverageTempo(float val, bool reset)
{
  if (reset)
    m_average_available = 0;
  if (m_average_available < AVERAGING_BUFFER_SIZE)
    m_average_available++;

  m_average_fullness[m_average_position] = val;
  m_average_position = (m_average_position + 1U) % AVERAGING_BUFFER_SIZE;

  const u32 actual_window = std::min<u32>(m_average_available, AVERAGING_WINDOW);
  const u32 first_index = (m_average_position - actual_window + AVERAGING_BUFFER_SIZE) % AVERAGING_BUFFER_SIZE;

  float sum = 0;
  for (u32 i = first_index; i < first_index + actual_window; i++)
    sum += m_average_fullness[i % AVERAGING_BUFFER_SIZE];
  sum = sum / actual_window;

  return (sum != 0.0f) ? sum : 1.0f;
}

void AudioStream::UpdateStretchTempo()
{
  static constexpr float MIN_TEMPO = 0.05f;
  static constexpr float MAX_TEMPO = 50.0f;

  // Which range we will run in 1:1 mode for.
  static constexpr float INACTIVE_GOOD_FACTOR = 1.04f;
  static constexpr float INACTIVE_BAD_FACTOR = 1.2f;
  static constexpr u32 INACTIVE_MIN_OK_COUNT = 50;
  static constexpr u32 COMPENSATION_DIVIDER = 100;

  // After a clear (or at the start), the tempo stays nominal until the clear has been applied and the ring has
  // refilled to the restart level: averaging the fill of an empty ring gives a tempo far below 1, which stretches the
  // new audio and overshoots the target.
  if (m_tempo_hold)
  {
    if (m_cleared_generation.load(std::memory_order_acquire) != m_clear_generation ||
        GetBufferedFramesRelaxed() < m_restart_frames)
    {
      soundtouch_setTempo(m_soundtouch, m_nominal_rate);
      return;
    }

    m_tempo_hold = false;
    m_stretch_reset.store(STRETCH_RESET_THRESHOLD, std::memory_order_relaxed);
  }

  float base_target_usage = static_cast<float>(m_target_buffer_size) * m_nominal_rate;

  // Read once: the callback adds its underruns to it.
  const bool reset = (m_stretch_reset.load(std::memory_order_relaxed) >= STRETCH_RESET_THRESHOLD);

  // state vars
  if (reset)
  {
    VERBOSE_LOG("___ Stretcher is being reset.");
    m_stretch_inactive = false;
    m_stretch_ok_count = 0;
    m_dynamic_target_usage = base_target_usage;
  }

  const u32 ibuffer_usage = GetBufferedFramesRelaxed();
  float buffer_usage = static_cast<float>(ibuffer_usage);
  float tempo = buffer_usage / m_dynamic_target_usage;
  tempo = AddAndGetAverageTempo(tempo, reset);

  // Dampening when we get close to target.
  if (tempo < 2.0f)
    tempo = std::sqrt(tempo);

  tempo = std::clamp(tempo, MIN_TEMPO, MAX_TEMPO);

  if (tempo < 1.0f)
    base_target_usage /= std::sqrt(tempo);

  m_dynamic_target_usage +=
    static_cast<float>(base_target_usage / tempo - m_dynamic_target_usage) / static_cast<float>(COMPENSATION_DIVIDER);
  if (IsInRange(tempo, 0.9f, 1.1f) &&
      IsInRange(m_dynamic_target_usage, base_target_usage * 0.9f, base_target_usage * 1.1f))
  {
    m_dynamic_target_usage = base_target_usage;
  }

  if (!m_stretch_inactive)
  {
    if (IsInRange(tempo, 1.0f / INACTIVE_GOOD_FACTOR, INACTIVE_GOOD_FACTOR))
      m_stretch_ok_count++;
    else
      m_stretch_ok_count = 0;

    if (m_stretch_ok_count >= INACTIVE_MIN_OK_COUNT)
    {
      VERBOSE_LOG("=== Stretcher is now inactive.");
      m_stretch_inactive = true;
    }
  }
  else if (!IsInRange(tempo, 1.0f / INACTIVE_BAD_FACTOR, INACTIVE_BAD_FACTOR))
  {
    VERBOSE_LOG("~~~ Stretcher is now active @ tempo {}.", tempo);
    m_stretch_inactive = false;
    m_stretch_ok_count = 0;
  }

  if (m_stretch_inactive)
    tempo = m_nominal_rate;

  if constexpr (LOG_TIMESTRETCH_STATS)
  {
    static int iterations = 0;
    static u64 last_log_time = 0;

    const u64 now = Common::Timer::GetCurrentValue();

    if (Common::Timer::ConvertValueToSeconds(now - last_log_time) > 1.0f)
    {
      VERBOSE_LOG("buffers: {:4d} ms ({:3.0f}%), tempo: {}, comp: {:2.3f}, iters: {}, reset:{}",
                  (ibuffer_usage * 1000u) / m_sample_rate, 100.0f * buffer_usage / base_target_usage, tempo,
                  m_dynamic_target_usage / base_target_usage, iterations,
                  m_stretch_reset.load(std::memory_order_relaxed));

      last_log_time = now;
      iterations = 0;
    }

    iterations++;
  }

  soundtouch_setTempo(m_soundtouch, tempo);

  if (reset)
    m_stretch_reset.store(0, std::memory_order_relaxed);
}

void AudioStream::StretchUnderrun()
{
  // Didn't produce enough frames in time.
  m_stretch_reset.fetch_add(1, std::memory_order_relaxed);
}

void AudioStreamParameters::Load(SettingsInterface& si, const char* section)
{
  stretch_mode =
    AudioStream::ParseStretchMode(
      si.GetStringValue(section, "StretchMode", AudioStream::GetStretchModeName(DEFAULT_STRETCH_MODE)).c_str())
      .value_or(DEFAULT_STRETCH_MODE);
#ifndef __ANDROID__
  expansion_mode =
    AudioStream::ParseExpansionMode(
      si.GetStringValue(section, "ExpansionMode", AudioStream::GetExpansionModeName(DEFAULT_EXPANSION_MODE)).c_str())
      .value_or(DEFAULT_EXPANSION_MODE);
#else
  expansion_mode = AudioExpansionMode::Disabled;
#endif
  output_latency_ms = static_cast<u16>(std::min<u32>(
    si.GetUIntValue(section, "OutputLatencyMS", DEFAULT_OUTPUT_LATENCY_MS), std::numeric_limits<u16>::max()));
  output_latency_minimal = si.GetBoolValue(section, "OutputLatencyMinimal", DEFAULT_OUTPUT_LATENCY_MINIMAL);
  buffer_ms = static_cast<u16>(
    std::min<u32>(si.GetUIntValue(section, "BufferMS", DEFAULT_BUFFER_MS), std::numeric_limits<u16>::max()));

  stretch_sequence_length_ms =
    static_cast<u16>(std::min<u32>(si.GetUIntValue(section, "StretchSequenceLengthMS", DEFAULT_STRETCH_SEQUENCE_LENGTH),
                                   std::numeric_limits<u16>::max()));
  stretch_seekwindow_ms = static_cast<u16>(std::min<u32>(
    si.GetUIntValue(section, "StretchSeekWindowMS", DEFAULT_STRETCH_SEEKWINDOW), std::numeric_limits<u16>::max()));
  stretch_overlap_ms = static_cast<u16>(std::min<u32>(
    si.GetUIntValue(section, "StretchOverlapMS", DEFAULT_STRETCH_OVERLAP), std::numeric_limits<u16>::max()));
  stretch_use_quickseek = si.GetBoolValue(section, "StretchUseQuickSeek", DEFAULT_STRETCH_USE_QUICKSEEK);
  stretch_use_aa_filter = si.GetBoolValue(section, "StretchUseAAFilter", DEFAULT_STRETCH_USE_AA_FILTER);

  expand_block_size = static_cast<u16>(std::min<u32>(
    si.GetUIntValue(section, "ExpandBlockSize", DEFAULT_EXPAND_BLOCK_SIZE), std::numeric_limits<u16>::max()));
  expand_block_size = std::clamp<u16>(
    Common::IsPow2(expand_block_size) ? expand_block_size : Common::NextPow2(expand_block_size), 128, 8192);
  expand_circular_wrap =
    std::clamp(si.GetFloatValue(section, "ExpandCircularWrap", DEFAULT_EXPAND_CIRCULAR_WRAP), 0.0f, 360.0f);
  expand_shift = std::clamp(si.GetFloatValue(section, "ExpandShift", DEFAULT_EXPAND_SHIFT), -1.0f, 1.0f);
  expand_depth = std::clamp(si.GetFloatValue(section, "ExpandDepth", DEFAULT_EXPAND_DEPTH), 0.0f, 5.0f);
  expand_focus = std::clamp(si.GetFloatValue(section, "ExpandFocus", DEFAULT_EXPAND_FOCUS), -1.0f, 1.0f);
  expand_center_image =
    std::clamp(si.GetFloatValue(section, "ExpandCenterImage", DEFAULT_EXPAND_CENTER_IMAGE), 0.0f, 1.0f);
  expand_front_separation =
    std::clamp(si.GetFloatValue(section, "ExpandFrontSeparation", DEFAULT_EXPAND_FRONT_SEPARATION), 0.0f, 10.0f);
  expand_rear_separation =
    std::clamp(si.GetFloatValue(section, "ExpandRearSeparation", DEFAULT_EXPAND_REAR_SEPARATION), 0.0f, 10.0f);
  expand_low_cutoff =
    static_cast<u8>(std::min<u32>(si.GetUIntValue(section, "ExpandLowCutoff", DEFAULT_EXPAND_LOW_CUTOFF), 100));
  expand_high_cutoff =
    static_cast<u8>(std::min<u32>(si.GetUIntValue(section, "ExpandHighCutoff", DEFAULT_EXPAND_HIGH_CUTOFF), 100));
}

void AudioStreamParameters::Save(SettingsInterface& si, const char* section) const
{
  si.SetStringValue(section, "StretchMode", AudioStream::GetStretchModeName(stretch_mode));
  si.SetStringValue(section, "ExpansionMode", AudioStream::GetExpansionModeName(expansion_mode));
  si.SetUIntValue(section, "BufferMS", buffer_ms);
  si.SetUIntValue(section, "OutputLatencyMS", output_latency_ms);
  si.SetBoolValue(section, "OutputLatencyMinimal", output_latency_minimal);

  si.SetUIntValue(section, "StretchSequenceLengthMS", stretch_sequence_length_ms);
  si.SetUIntValue(section, "StretchSeekWindowMS", stretch_seekwindow_ms);
  si.SetUIntValue(section, "StretchOverlapMS", stretch_overlap_ms);
  si.SetBoolValue(section, "StretchUseQuickSeek", stretch_use_quickseek);
  si.SetBoolValue(section, "StretchUseAAFilter", stretch_use_aa_filter);

  si.SetUIntValue(section, "ExpandBlockSize", expand_block_size);
  si.SetFloatValue(section, "ExpandCircularWrap", expand_circular_wrap);
  si.SetFloatValue(section, "ExpandShift", expand_shift);
  si.SetFloatValue(section, "ExpandDepth", expand_depth);
  si.SetFloatValue(section, "ExpandFocus", expand_focus);
  si.SetFloatValue(section, "ExpandCenterImage", expand_center_image);
  si.SetFloatValue(section, "ExpandFrontSeparation", expand_front_separation);
  si.SetFloatValue(section, "ExpandRearSeparation", expand_rear_separation);
  si.SetUIntValue(section, "ExpandLowCutoff", expand_low_cutoff);
  si.SetUIntValue(section, "ExpandHighCutoff", expand_high_cutoff);
}

void AudioStreamParameters::Clear(SettingsInterface& si, const char* section)
{
  si.DeleteValue(section, "StretchMode");
  si.DeleteValue(section, "ExpansionMode");
  si.DeleteValue(section, "BufferMS");
  si.DeleteValue(section, "OutputLatencyMS");
  si.DeleteValue(section, "OutputLatencyMinimal");

  si.DeleteValue(section, "StretchSequenceLengthMS");
  si.DeleteValue(section, "StretchSeekWindowMS");
  si.DeleteValue(section, "StretchOverlapMS");
  si.DeleteValue(section, "StretchUseQuickSeek");
  si.DeleteValue(section, "StretchUseAAFilter");

  si.DeleteValue(section, "ExpandBlockSize");
  si.DeleteValue(section, "ExpandCircularWrap");
  si.DeleteValue(section, "ExpandShift");
  si.DeleteValue(section, "ExpandDepth");
  si.DeleteValue(section, "ExpandFocus");
  si.DeleteValue(section, "ExpandCenterImage");
  si.DeleteValue(section, "ExpandFrontSeparation");
  si.DeleteValue(section, "ExpandRearSeparation");
  si.DeleteValue(section, "ExpandLowCutoff");
  si.DeleteValue(section, "ExpandHighCutoff");
}

bool AudioStreamParameters::operator!=(const AudioStreamParameters& rhs) const
{
  return (std::memcmp(this, &rhs, sizeof(*this)) != 0);
}

bool AudioStreamParameters::operator==(const AudioStreamParameters& rhs) const
{
  return (std::memcmp(this, &rhs, sizeof(*this)) == 0);
}
