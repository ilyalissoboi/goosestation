// SPDX-FileCopyrightText: 2019-2024 Connor McLaughlin <stenzek@gmail.com>
// SPDX-License-Identifier: (GPL-3.0 OR CC-BY-NC-ND-4.0)

#pragma once
#include "input_source.h"

#include <SDL3/SDL.h>

#include <array>
#include <functional>
#include <mutex>
#include <vector>

class SettingsInterface;

class SDLInputSource final : public InputSource
{
public:
  static constexpr u32 MAX_LED_COLORS = 4;

  SDLInputSource();
  ~SDLInputSource();

  bool Initialize(SettingsInterface& si, std::unique_lock<std::mutex>& settings_lock) override;
  void UpdateSettings(SettingsInterface& si, std::unique_lock<std::mutex>& settings_lock) override;
  bool ReloadDevices() override;
  void Shutdown() override;

  void PollEvents() override;
  std::vector<std::pair<std::string, std::string>> EnumerateDevices() override;
  std::vector<InputBindingKey> EnumerateMotors() override;
  bool GetGenericBindingMapping(std::string_view device, GenericInputBindingMapping* mapping) override;
  void UpdateMotorState(InputBindingKey key, float intensity) override;
  void UpdateMotorState(InputBindingKey large_key, InputBindingKey small_key, float large_intensity,
                        float small_intensity) override;

  std::optional<InputBindingKey> ParseKeyString(std::string_view device, std::string_view binding) override;
  TinyString ConvertKeyToString(InputBindingKey key) override;
  TinyString ConvertKeyToIcon(InputBindingKey key) override;

  bool ProcessSDLEvent(const SDL_Event* event);

  SDL_Joystick* GetJoystickForDevice(std::string_view device);

  static u32 GetRGBForPlayerId(SettingsInterface& si, u32 player_id);
  static u32 ParseRGBForPlayerId(std::string_view str, u32 player_id);

  static bool IsHandledInputEvent(const SDL_Event* ev);

  static bool ALLOW_EVENT_POLLING;

private:
  struct ControllerData
  {
    SDL_Haptic* haptic;
    SDL_Gamepad* gamepad;
    SDL_Joystick* joystick;
    u16 rumble_intensity[2];
    SDL_HapticEffectID haptic_left_right_effect;
    SDL_JoystickID joystick_id;
    int player_id;
    bool use_gamepad_rumble;

    // Set for wireless PS4/PS5 pads when enhanced mode is off: an LED or rumble command would switch them into
    // enhanced mode, which SDL2 left to the setting.
    bool no_effects;

    // SDL2 numbers of the south, east, west and north buttons; see SDLInputBindings::GetFaceButtonIndices().
    std::array<u8, 4> face_button_indices;

    // Used to disable Joystick controls that are used in Gamepad inputs so we don't get double events
    std::vector<bool> joy_button_used_in_gc;
    std::vector<bool> joy_axis_used_in_gc;

    // Track last hat state so we can send "unpressed" events.
    std::vector<u8> last_hat_state;
  };

  using ControllerDataVector = std::vector<ControllerData>;

  bool InitializeSubsystem();
  void ShutdownSubsystem();
  void LoadSettings(SettingsInterface& si);
  void SetHints();

  ControllerDataVector::iterator GetControllerDataForJoystickId(SDL_JoystickID id);
  ControllerDataVector::iterator GetControllerDataForPlayerId(int id);
  int GetFreePlayerId() const;

  bool OpenDevice(SDL_JoystickID id, bool is_gamepad);
  bool CloseDevice(SDL_JoystickID id);
  bool HandleGamepadAxisEvent(const SDL_GamepadAxisEvent* ev);
  bool HandleGamepadButtonEvent(const SDL_GamepadButtonEvent* ev);
  bool HandleJoystickAxisEvent(const SDL_JoyAxisEvent* ev);
  bool HandleJoystickButtonEvent(const SDL_JoyButtonEvent* ev);
  bool HandleJoystickHatEvent(const SDL_JoyHatEvent* ev);
  void SendRumbleUpdate(ControllerData* cd);

  ControllerDataVector m_controllers;

  std::array<u32, MAX_LED_COLORS> m_led_colors{};
  std::vector<std::pair<std::string, std::string>> m_sdl_hints;

  bool m_sdl_subsystem_initialized = false;
  bool m_controller_enhanced_mode = false;
  bool m_controller_ps5_player_led = false;

#ifdef __APPLE__
  bool m_enable_iokit_driver = false;
  bool m_enable_mfi_driver = false;
#endif
};
