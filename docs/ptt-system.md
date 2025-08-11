# PTT (Push-to-Talk) System Architecture

## Overview

Gromozeka implements an advanced PTT system with sophisticated gesture recognition and dual input methods. The system is designed for seamless voice interaction with minimal interruption to the user's workflow.

## Key Components

### Input Methods

1. **On-screen PTT Button** - Always visible in the UI, responds to mouse/touch interactions
2. **§ (paragraph) Key** - Global hotkey that works system-wide, even when Gromozeka is not focused

### Gesture Recognition

The system recognizes 5 distinct gesture patterns:

#### 1. Single Click
- **Trigger**: Quick press and release
- **Action**: Stop TTS playback
- **Use case**: Simple interruption of AI speech

#### 2. Double Click  
- **Trigger**: Two quick clicks within 400ms window
- **Action**: Stop TTS + Interrupt Claude generation
- **Use case**: Stop both speech and response generation

#### 3. Single Hold
- **Trigger**: Press and hold for >150ms
- **Action**: Record voice message, send to Claude on release
- **Use case**: Standard voice input

#### 4. Double Hold
- **Trigger**: Quick click followed by hold
- **Action**: Interrupt Claude + Record voice message
- **Use case**: Interrupt ongoing response and provide new input

#### 5. Button Down (Optimistic Start)
- **Trigger**: Moment of initial press
- **Action**: Start recording + Enable audio mute
- **Purpose**: Instant response for user feedback

## Technical Implementation

### Global Hotkey: § Key Magic

**Problem**: Standard hotkeys conflict with system shortcuts (Ctrl+Tab, Ctrl+C, etc.)

**Solution**: Remap § key to F13 using macOS hidutil:
```bash
# Remap § to F13 (on startup)
hidutil property --set '{"UserKeyMapping":[{"HIDKeyboardModifierMappingSrc":0x700000064,"HIDKeyboardModifierMappingDst":0x700000068}]}'

# Restore defaults (on shutdown)  
hidutil property --set '{"UserKeyMapping":[]}'
```

**Benefits**:
- Zero conflicts (F13 has no default system function)
- Excellent ergonomics (§ key is perfectly positioned)
- No symbol output (§ character doesn't print in text fields)
- Automatic restoration on app shutdown

### Architecture Components

```
User Input (§ key or UI button)
    ↓
GlobalHotkeyService / UI Gesture Handler
    ↓  
UnifiedGestureDetector (timing analysis)
    ↓
PTTEventRouter (gesture → action mapping)
    ↓
PTTService (audio recording)
    ↓
AudioRecorder → STTService → Session.sendMessage()
```

#### Core Services

- **GlobalHotkeyService**: JNativeHook integration for § key detection
- **UnifiedGestureDetector**: Analyzes press/release timings to identify gestures
- **PTTEventRouter**: Routes gesture events to appropriate handlers (TTS stop, Claude interrupt, recording)
- **PTTService**: Manages audio recording lifecycle and state
- **AudioMuteManager**: System audio muting during recording for clean voice capture
- **STTService**: Speech-to-text transcription using OpenAI Whisper API

### Timing Parameters

```kotlin
private const val shortClickThreshold = 150L      // Click vs Hold boundary
private const val doubleClickWindow = 400L        // Double click detection window  
private const val minRecordingDurationMs = 150L   // Minimum recording length
private const val maxRecordingTimeout = 5 * 60 * 1000L // 5 minute max recording
```

### Audio Management

**Optimistic Recording Strategy**:
1. **Button Down**: Immediately start recording (for instant feedback)
2. **Click Detection**: Cancel recording if gesture was actually a click
3. **Hold Confirmation**: Continue recording if gesture is confirmed as hold
4. **Audio Mute**: Enable during recording to eliminate background noise

**Recovery Mechanisms**:
- Auto-restore audio mute if recording fails
- Graceful handling of microphone permission issues  
- Timeout protection for stuck recordings
- Proper cleanup on app shutdown

## UI Integration

### Recording State Indicator

```kotlin
val recordingState: StateFlow<Boolean> // Exposed by PTTService
```

**Visual Feedback**:
- Red dot appears during active recording
- Button visual state changes based on recording status
- Real-time gesture feedback for user confirmation

### Compose Integration

The PTT button uses `AdvancedPTTGestureModifier` which integrates with the same `UnifiedGestureDetector` used by the global hotkey system, ensuring consistent behavior across input methods.

## Error Handling & Edge Cases

### Race Conditions
- **Session not ready**: Messages buffered until Claude session initialized
- **Recording conflicts**: Prevent multiple simultaneous recordings
- **Process cleanup**: Proper resource cleanup on app shutdown

### Permission Issues  
- **Microphone access**: Graceful fallback if STT unavailable
- **Accessibility permissions**: Required for global hotkey on macOS
- **Audio device conflicts**: Handle multiple audio applications

### Recovery Scenarios
- **Stuck mute state**: Auto-restore after timeout
- **Recording failures**: Clear visual indicators and retry options
- **Claude process crashes**: PTT system remains functional, session restart supported

## Development Notes

### Testing Strategy
- **Manual testing required**: Timing-dependent behavior cannot be unit tested reliably
- **Real device testing**: Microphone and audio behavior varies by hardware
- **Platform-specific testing**: macOS-specific hidutil integration

### Future Enhancements
- **Configurable gestures**: Allow user customization of gesture mappings
- **Multiple hotkeys**: Support additional global hotkeys beyond §
- **Voice commands**: Direct command recognition without full STT pipeline
- **Push-to-talk indicators**: System-wide recording indicator overlay

## Historical Context

The PTT system evolved through multiple iterations to reach its current sophisticated state:

1. **v1.0**: Basic on-screen button only
2. **v2.0**: Added Ctrl key global hotkey (caused conflicts)
3. **v3.0**: Complex conflict resolution system (unreliable)
4. **v3.2** (Current): § key remapping solution (stable and conflict-free)

The current architecture represents lessons learned from extensive debugging and user testing to create a robust, conflict-free voice input system.