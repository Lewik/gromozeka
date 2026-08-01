# /// script
# requires-python = ">=3.12"
# dependencies = [
#   "numpy>=2.3.2",
#   "scipy>=1.16.1",
#   "soundfile>=0.13.1",
# ]
# ///

from __future__ import annotations

import argparse
import html
import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy.signal import butter, fftconvolve, resample_poly, sosfilt


WORK_RATE = 192_000
OUTPUT_RATE = 48_000
MASTER_PEAK = 10 ** (-1.2 / 20)


@dataclass(frozen=True)
class SoundFamily:
    slug: str
    title: str
    description: str
    scan_carrier_hz: tuple[float, float]
    scan_sub_hz: tuple[float, float]
    scan_spacing_seconds: float
    scan_duration_seconds: float
    scan_mod_hz: float
    scan_mod_depth: float
    result_hz: tuple[float, float]
    result_duration_seconds: float
    metallic_hz: tuple[float, ...]


@dataclass(frozen=True)
class SoundStudy:
    slug: str
    title: str
    description: str


FAMILIES = (
    SoundFamily(
        slug="01-recon",
        title="Recon",
        description="Twin low robotic chirps with a compact tonal verdict.",
        scan_carrier_hz=(318.0, 184.0),
        scan_sub_hz=(132.0, 96.0),
        scan_spacing_seconds=0.132,
        scan_duration_seconds=0.104,
        scan_mod_hz=71.0,
        scan_mod_depth=2.9,
        result_hz=(146.0, 108.0),
        result_duration_seconds=0.265,
        metallic_hz=(510.0, 773.0, 1189.0, 1783.0),
    ),
    SoundFamily(
        slug="02-servo",
        title="Servo",
        description="Faster mechanical interrogation with a dry, decisive result.",
        scan_carrier_hz=(402.0, 236.0),
        scan_sub_hz=(148.0, 104.0),
        scan_spacing_seconds=0.108,
        scan_duration_seconds=0.086,
        scan_mod_hz=83.0,
        scan_mod_depth=3.6,
        result_hz=(132.0, 92.0),
        result_duration_seconds=0.225,
        metallic_hz=(621.0, 917.0, 1369.0, 2053.0),
    ),
    SoundFamily(
        slug="03-deep",
        title="Deep Scan",
        description="Slower sub-heavy acquisition with a wider cinematic tail.",
        scan_carrier_hz=(238.0, 142.0),
        scan_sub_hz=(104.0, 68.0),
        scan_spacing_seconds=0.172,
        scan_duration_seconds=0.136,
        scan_mod_hz=53.0,
        scan_mod_depth=2.4,
        result_hz=(108.0, 66.0),
        result_duration_seconds=0.34,
        metallic_hz=(407.0, 659.0, 1019.0, 1613.0),
    ),
)


STUDIES = (
    SoundStudy(
        slug="04-evidence-marker",
        title="Evidence Marker",
        description="A compact concentric acquisition ping for one discovered object.",
    ),
    SoundStudy(
        slug="05-scene-reconstruction",
        title="Scene Reconstruction",
        description="A layered spatial sweep for a longer forensic reconstruction step.",
    ),
    SoundStudy(
        slug="06-target-track",
        title="Target Track",
        description="A restrained repeating cadence for an object under active tracking.",
    ),
    SoundStudy(
        slug="07-target-lock",
        title="Target Lock",
        description="An accelerating search sequence collapsing into a low lock verdict.",
    ),
    SoundStudy(
        slug="08-render-complete",
        title="Render Complete",
        description="A wide reconstruction pattern resolving into one centered result.",
    ),
    SoundStudy(
        slug="09-condition-critical",
        title="Condition Critical",
        description="A bass-first diagnostic warning without a conventional alarm siren.",
    ),
)


DEEP_LAYERED_PARTS = (
    (
        "mix",
        "Layered Mix",
        "The complete five-layer Deep Scan prototype.",
    ),
    (
        "mechanical",
        "Mechanical",
        "Solenoid strikes, inharmonic metal modes, and tiny ratchet movements.",
    ),
    (
        "tonal",
        "Tonal",
        "An FM scanner gesture carrying the recognizable electronic identity.",
    ),
    (
        "sub",
        "Mono Sub",
        "Centered low-frequency weight designed to survive stereo collapse.",
    ),
    (
        "texture",
        "Texture",
        "Decorrelated electrical crackle and non-periodic physical grit.",
    ),
    (
        "space",
        "Stereo Space",
        "Early reflections and a filtered diffuse tail, without low-frequency smear.",
    ),
)


DEEP_HUD_PARTS = (
    (
        "mix",
        "Pulse + Sub",
        "The HUD pulse over a restrained mono bass foundation.",
    ),
    (
        "mix-sub-forward",
        "Sub-forward",
        "The same two layers with the mono bass brought forward and the HUD pulse recessed.",
    ),
    (
        "mix-sub-body",
        "Sub-forward + Body",
        "The bass-forward balance with a quiet synthetic low-mid body underneath.",
    ),
    (
        "mix-reference-body",
        "Low-mid Body",
        "A reference-shaped balance with less sub-bass and a body shifted into the low-mid range.",
    ),
    (
        "mix-reference-data",
        "Low-mid + Data",
        "The low-mid balance with a very quiet layer of synthetic computation detail.",
    ),
    (
        "pulse",
        "HUD Pulse",
        "Soft phase-modulated acquisition pulses with a muted upper spectrum.",
    ),
    (
        "body",
        "Synthetic Body",
        "A low-mid harmonic cloud retained as a possible error sound.",
    ),
    (
        "body-mid",
        "Raised Body",
        "A higher version of Synthetic Body tuned toward the reference low-mid energy.",
    ),
    (
        "sub",
        "Mono Sub",
        "A centered low-frequency foundation beneath the interface gesture.",
    ),
    (
        "data",
        "Data Detail",
        "Quiet micro-tones creating internal computation detail instead of grit.",
    ),
)


APP_FEEDBACK_PARTS = (
    (
        "attention-sub-forward",
        "Attention / Sub-forward",
        "The current bass-forward candidate, mapped to an explicit request for user attention.",
    ),
    (
        "attention-sub-body",
        "Attention / Sub-forward + Body",
        "The current priority mix retained for direct comparison in its application role.",
    ),
    (
        "attention-complete-enriched",
        "Attention / Enriched Complete",
        "The Deep Scan Complete motif supported by the same synthetic sub and body vocabulary.",
    ),
    (
        "error-threat-enriched",
        "Error / Enriched Threat",
        "The Deep Scan Threat motif reinforced without data chatter or physical-world layers.",
    ),
    (
        "activity-original",
        "Activity / Original crack",
        "The original short progress crack, preserved without redesign.",
    ),
    (
        "activity-muted",
        "Activity / Muted crack",
        "The original crack with a softer upper spectrum for repeated low-level playback.",
    ),
    (
        "activity-hud",
        "Activity / HUD crack",
        "The muted crack with a tiny synthetic low body, still short enough to remain peripheral.",
    ),
)

APP_RUNTIME_SELECTION = {
    "attention.wav": "attention-sub-forward",
    "activity.wav": "activity-original",
    "error.wav": "error-threat-enriched",
}


def sweep_phase(duration_seconds: float, start_hz: float, end_hz: float) -> np.ndarray:
    sample_count = round(duration_seconds * WORK_RATE)
    position = np.linspace(0.0, 1.0, sample_count, endpoint=False)
    frequencies = start_hz * np.power(end_hz / start_hz, position)
    return 2.0 * math.pi * np.cumsum(frequencies) / WORK_RATE


def percussive_envelope(
    duration_seconds: float,
    attack_seconds: float,
    decay_seconds: float,
) -> np.ndarray:
    sample_count = round(duration_seconds * WORK_RATE)
    time = np.arange(sample_count) / WORK_RATE
    attack = 1.0 - np.exp(-time / attack_seconds)
    decay = np.exp(-time / decay_seconds)
    tail = np.sin(np.linspace(math.pi / 2.0, 0.0, sample_count)) ** 2
    return attack * decay * tail


def tonal_sweep(
    duration_seconds: float,
    start_hz: float,
    end_hz: float,
    decay_seconds: float,
    harmonic_mix: tuple[float, ...],
) -> np.ndarray:
    phase = sweep_phase(duration_seconds, start_hz, end_hz)
    signal = np.zeros_like(phase)
    for harmonic, amplitude in enumerate(harmonic_mix, start=1):
        signal += amplitude * np.sin(phase * harmonic + harmonic * 0.17)
    return signal * percussive_envelope(duration_seconds, 0.0035, decay_seconds)


def robot_cricket(family: SoundFamily, alternate: bool) -> np.ndarray:
    duration = family.scan_duration_seconds
    sample_count = round(duration * WORK_RATE)
    time = np.arange(sample_count) / WORK_RATE
    carrier_phase = sweep_phase(duration, *family.scan_carrier_hz)
    sub_phase = sweep_phase(duration, *family.scan_sub_hz)
    modulation = family.scan_mod_depth * np.sin(
        2.0 * math.pi * family.scan_mod_hz * time + (0.4 if alternate else 0.0)
    ) * np.exp(-time / (duration * 0.82))
    carrier = np.sin(carrier_phase + modulation)
    sub = np.sin(sub_phase + 0.23) + 0.22 * np.sin(sub_phase * 2.0 + 0.47)
    gate_rate = 92.0 if alternate else 84.0
    gate = 0.66 + 0.34 * np.sin(2.0 * math.pi * gate_rate * time) ** 6
    envelope = percussive_envelope(duration, 0.002, duration * 0.52)
    return (0.58 * carrier + 0.68 * sub) * gate * envelope


def metallic_tick(frequencies: tuple[float, ...], duration_seconds: float, seed: int) -> np.ndarray:
    sample_count = round(duration_seconds * WORK_RATE)
    time = np.arange(sample_count) / WORK_RATE
    signal = np.zeros(sample_count)
    for index, frequency in enumerate(frequencies):
        signal += (0.58 ** index) * np.sin(2.0 * math.pi * frequency * time + index * 0.71)
    rng = np.random.default_rng(seed)
    noise = rng.normal(0.0, 1.0, sample_count)
    smoothed = np.convolve(noise, np.ones(19) / 19.0, mode="same")
    bright_noise = noise - smoothed
    envelope = percussive_envelope(duration_seconds, 0.0008, duration_seconds * 0.19)
    return (0.54 * signal + 0.12 * bright_noise) * envelope


def scanner_pulse(
    duration_seconds: float,
    carrier_hz: tuple[float, float],
    sub_hz: tuple[float, float],
    modulation_hz: float,
    seed: int,
) -> np.ndarray:
    sample_count = round(duration_seconds * WORK_RATE)
    time = np.arange(sample_count) / WORK_RATE
    carrier_phase = sweep_phase(duration_seconds, *carrier_hz)
    sub_phase = sweep_phase(duration_seconds, *sub_hz)
    modulation = 2.8 * np.sin(2.0 * math.pi * modulation_hz * time) * np.exp(
        -time / (duration_seconds * 0.78)
    )
    carrier = np.sin(carrier_phase + modulation)
    body = np.sin(sub_phase + 0.31) + 0.24 * np.sin(sub_phase * 2.0 + 0.77)
    rng = np.random.default_rng(seed)
    noise = rng.normal(0.0, 1.0, sample_count)
    band_pass = butter(2, (680.0, 5_600.0), btype="bandpass", fs=WORK_RATE, output="sos")
    texture = sosfilt(band_pass, noise)
    texture /= max(float(np.max(np.abs(texture))), 1e-9)
    gate = 0.7 + 0.3 * np.sin(2.0 * math.pi * modulation_hz * 1.31 * time) ** 8
    envelope = percussive_envelope(duration_seconds, 0.0018, duration_seconds * 0.46)
    return (0.48 * carrier + 0.76 * body + 0.08 * texture) * gate * envelope


def rotary_scan(
    duration_seconds: float,
    carrier_hz: float,
    sub_hz: float,
    rotations: float,
) -> np.ndarray:
    sample_count = round(duration_seconds * WORK_RATE)
    time = np.arange(sample_count) / WORK_RATE
    angular_phase = 2.0 * math.pi * rotations * time / duration_seconds
    gate = np.maximum(np.sin(angular_phase), 0.0) ** 5
    carrier_phase = 2.0 * math.pi * np.cumsum(
        carrier_hz * (1.0 + 0.055 * np.sin(angular_phase * 0.5)) * np.ones(sample_count)
    ) / WORK_RATE
    sub_phase = 2.0 * math.pi * sub_hz * time
    edge = np.sin(carrier_phase + 1.9 * np.sin(angular_phase * 1.7))
    body = np.sin(sub_phase) + 0.18 * np.sin(sub_phase * 2.0 + 0.4)
    fade = np.sin(np.linspace(0.0, math.pi, sample_count)) ** 0.55
    return (0.42 * edge * gate + 0.58 * body * (0.28 + 0.72 * gate)) * fade


def new_track(duration_seconds: float) -> np.ndarray:
    return np.zeros((round(duration_seconds * WORK_RATE), 2))


def modal_impact(
    duration_seconds: float,
    frequencies: tuple[float, ...],
    seed: int,
) -> np.ndarray:
    sample_count = round(duration_seconds * WORK_RATE)
    time = np.arange(sample_count) / WORK_RATE
    signal = np.zeros(sample_count)
    for index, frequency in enumerate(frequencies):
        decay = 0.035 + index * 0.022
        amplitude = 0.76 ** index
        pitch_droop = 1.0 + 0.018 * np.exp(-time / 0.018)
        phase = 2.0 * math.pi * np.cumsum(frequency * pitch_droop) / WORK_RATE
        signal += amplitude * np.sin(phase + index * 0.43) * np.exp(-time / decay)
    rng = np.random.default_rng(seed)
    noise = rng.normal(0.0, 1.0, sample_count)
    impact_filter = butter(2, (420.0, 8_400.0), btype="bandpass", fs=WORK_RATE, output="sos")
    impact = sosfilt(impact_filter, noise) * np.exp(-time / 0.0045)
    impact /= max(float(np.max(np.abs(impact))), 1e-9)
    attack = 1.0 - np.exp(-time / 0.00045)
    return (0.68 * signal + 0.24 * impact) * attack


def fm_scanner_voice(
    duration_seconds: float,
    carrier_hz: tuple[float, float],
    modulator_hz: tuple[float, float],
    modulation_index: tuple[float, float],
) -> np.ndarray:
    sample_count = round(duration_seconds * WORK_RATE)
    time = np.arange(sample_count) / WORK_RATE
    position = np.linspace(0.0, 1.0, sample_count, endpoint=False)
    carrier_phase = sweep_phase(duration_seconds, *carrier_hz)
    modulator_phase = sweep_phase(duration_seconds, *modulator_hz)
    index = modulation_index[0] * np.power(
        modulation_index[1] / modulation_index[0],
        position,
    )
    voice = np.sin(carrier_phase + index * np.sin(modulator_phase))
    edge = np.sin(carrier_phase * 2.013 + 0.27 * np.sin(modulator_phase * 0.51))
    gate = 0.72 + 0.28 * np.sin(2.0 * math.pi * 67.0 * time) ** 8
    envelope = percussive_envelope(duration_seconds, 0.0025, duration_seconds * 0.58)
    return (0.82 * voice + 0.18 * edge) * gate * envelope


def sub_bloom(
    duration_seconds: float,
    start_hz: float,
    end_hz: float,
) -> np.ndarray:
    phase = sweep_phase(duration_seconds, start_hz, end_hz)
    body = np.sin(phase) + 0.24 * np.sin(phase * 2.0 + 0.19) + 0.08 * np.sin(phase * 3.0 + 0.51)
    envelope = percussive_envelope(duration_seconds, 0.006, duration_seconds * 0.62)
    return body * envelope


def electrical_texture(duration_seconds: float, seed: int) -> np.ndarray:
    sample_count = round(duration_seconds * WORK_RATE)
    time = np.arange(sample_count) / WORK_RATE
    rng = np.random.default_rng(seed)
    impulses = np.zeros(sample_count)
    locations = rng.integers(0, max(1, round(sample_count * 0.72)), size=38)
    impulses[locations] = rng.uniform(-1.0, 1.0, size=len(locations))
    kernel_time = np.arange(round(0.019 * WORK_RATE)) / WORK_RATE
    kernel = np.exp(-kernel_time / 0.0038) * np.sin(2.0 * math.pi * 2_700.0 * kernel_time)
    crackle = fftconvolve(impulses, kernel, mode="full")[:sample_count]
    noise = rng.normal(0.0, 1.0, sample_count)
    texture_filter = butter(3, (760.0, 11_800.0), btype="bandpass", fs=WORK_RATE, output="sos")
    brush = sosfilt(texture_filter, noise)
    brush /= max(float(np.max(np.abs(brush))), 1e-9)
    envelope = percussive_envelope(duration_seconds, 0.0012, duration_seconds * 0.54)
    return (0.62 * crackle + 0.27 * brush) * envelope


def stereo_space(track: np.ndarray, seed: int) -> np.ndarray:
    ir_length = round(0.54 * WORK_RATE)
    time = np.arange(ir_length) / WORK_RATE
    rng = np.random.default_rng(seed)
    impulse_responses = np.zeros((ir_length, 2))
    for channel, delays in enumerate(
        (
            ((0.013, 0.46), (0.029, 0.32), (0.051, 0.24), (0.087, 0.16), (0.131, 0.1)),
            ((0.017, 0.43), (0.037, 0.3), (0.061, 0.22), (0.079, 0.17), (0.149, 0.09)),
        )
    ):
        for delay_seconds, gain in delays:
            impulse_responses[round(delay_seconds * WORK_RATE), channel] += gain
        diffuse = rng.normal(0.0, 1.0, ir_length) * np.exp(-time / 0.145)
        low_pass = butter(2, 6_200.0, btype="lowpass", fs=WORK_RATE, output="sos")
        diffuse = sosfilt(low_pass, diffuse)
        diffuse /= max(float(np.max(np.abs(diffuse))), 1e-9)
        impulse_responses[:, channel] += diffuse * 0.055
    wet = np.zeros_like(track)
    wet[:, 0] = (
        fftconvolve(track[:, 0], impulse_responses[:, 0], mode="full")[: len(track)]
        + 0.22 * fftconvolve(track[:, 1], impulse_responses[:, 1], mode="full")[: len(track)]
    )
    wet[:, 1] = (
        fftconvolve(track[:, 1], impulse_responses[:, 1], mode="full")[: len(track)]
        + 0.22 * fftconvolve(track[:, 0], impulse_responses[:, 0], mode="full")[: len(track)]
    )
    high_pass = butter(3, 118.0, btype="highpass", fs=WORK_RATE, output="sos")
    wet = sosfilt(high_pass, wet, axis=0)
    dry_rms = float(np.sqrt(np.mean(track * track)))
    wet_rms = float(np.sqrt(np.mean(wet * wet)))
    if wet_rms > 0.0:
        wet *= dry_rms / wet_rms
    return wet


def finish_stem(track: np.ndarray) -> np.ndarray:
    track = track - np.mean(track, axis=0, keepdims=True)
    track = resample_poly(track, up=1, down=WORK_RATE // OUTPUT_RATE, axis=0)
    high_pass = butter(3, 24.0, btype="highpass", fs=OUTPUT_RATE, output="sos")
    return sosfilt(high_pass, track, axis=0)


def soft_hud_pulse(
    duration_seconds: float,
    carrier_hz: tuple[float, float],
    low_hz: tuple[float, float],
    phase_offset: float,
) -> np.ndarray:
    sample_count = round(duration_seconds * WORK_RATE)
    time = np.arange(sample_count) / WORK_RATE
    position = np.linspace(0.0, 1.0, sample_count, endpoint=False)
    carrier_phase = sweep_phase(duration_seconds, *carrier_hz)
    low_phase = sweep_phase(duration_seconds, *low_hz)
    modulator_phase = sweep_phase(duration_seconds, 71.0, 38.0)
    modulation_index = 2.35 * np.power(0.22, position)
    carrier = np.sin(carrier_phase + modulation_index * np.sin(modulator_phase + phase_offset))
    low = np.sin(low_phase + 0.34 * np.sin(modulator_phase * 0.51 + phase_offset))
    color = np.sin(carrier_phase * 1.503 + 0.21 * np.sin(modulator_phase * 0.73))
    gate = 0.81 + 0.19 * np.sin(2.0 * math.pi * 57.0 * time + phase_offset) ** 6
    envelope = percussive_envelope(duration_seconds, 0.0055, duration_seconds * 0.55)
    signal = (0.54 * carrier + 0.52 * low + 0.12 * color) * gate * envelope
    low_pass = butter(3, 4_200.0, btype="lowpass", fs=WORK_RATE, output="sos")
    return sosfilt(low_pass, signal)


def synthetic_hud_body(
    duration_seconds: float,
    root_hz: tuple[float, float],
    phase_offset: float,
) -> np.ndarray:
    root_phase = sweep_phase(duration_seconds, *root_hz)
    sample_count = len(root_phase)
    time = np.arange(sample_count) / WORK_RATE
    signal = np.zeros(sample_count)
    for index, (ratio, level) in enumerate(
        ((1.0, 0.72), (1.49, 0.34), (2.03, 0.2), (2.97, 0.11), (4.11, 0.055))
    ):
        drift = 0.08 * np.sin(2.0 * math.pi * (3.1 + index * 0.73) * time + phase_offset)
        signal += level * np.sin(root_phase * ratio + drift + index * 0.29)
    envelope = percussive_envelope(duration_seconds, 0.012, duration_seconds * 0.72)
    low_pass = butter(3, 2_700.0, btype="lowpass", fs=WORK_RATE, output="sos")
    return sosfilt(low_pass, signal * envelope)


def hud_data_detail(duration_seconds: float, seed: int) -> np.ndarray:
    sample_count = round(duration_seconds * WORK_RATE)
    signal = np.zeros(sample_count)
    rng = np.random.default_rng(seed)
    offsets = np.linspace(0.0, duration_seconds * 0.68, 6)
    offsets += rng.uniform(-0.006, 0.006, size=len(offsets))
    for index, offset in enumerate(offsets):
        frequency = 520.0 + index * 73.0 + rng.uniform(-18.0, 18.0)
        detail = tonal_sweep(
            0.058,
            frequency,
            frequency * (0.82 + index * 0.013),
            0.029,
            (1.0, 0.12, 0.025),
        )
        start = max(0, round(offset * WORK_RATE))
        end = min(start + len(detail), sample_count)
        signal[start:end] += detail[: end - start] * (0.86 - index * 0.075)
    low_pass = butter(3, 3_600.0, btype="lowpass", fs=WORK_RATE, output="sos")
    return sosfilt(low_pass, signal)


def build_deep_hud_prototype() -> dict[str, np.ndarray]:
    duration = 0.88
    pulse = new_track(duration)
    body = new_track(duration)
    body_mid = new_track(duration)
    sub = new_track(duration)
    data = new_track(duration)
    for index, offset in enumerate((0.04, 0.212)):
        pan = -0.08 if index == 0 else 0.08
        acquisition = soft_hud_pulse(
            0.205,
            (246.0 + index * 11.0, 143.0 + index * 6.0),
            (108.0 - index * 3.0, 69.0 - index * 2.0),
            0.37 * index,
        )
        place(pulse, acquisition, offset, level=0.76, pan=pan)
        cloud = synthetic_hud_body(
            0.39,
            (126.0 + index * 4.0, 82.0 + index * 3.0),
            0.41 * index,
        )
        place(body, cloud, offset + 0.004, level=0.55, pan=-pan * 0.8)
        raised_cloud = synthetic_hud_body(
            0.39,
            (218.0 + index * 7.0, 156.0 + index * 5.0),
            0.29 + 0.41 * index,
        )
        place(body_mid, raised_cloud, offset + 0.006, level=0.52, pan=-pan * 0.6)
        low = sub_bloom(0.43, 72.0 - index * 3.0, 44.0 - index * 2.0)
        place(sub, low, offset, level=0.62, pan=0.0)
        left_detail = hud_data_detail(0.245, 941 + index * 2)
        right_detail = hud_data_detail(0.245, 942 + index * 2)
        place(data, left_detail, offset + 0.018, level=0.1, pan=-0.62)
        place(data, right_detail, offset + 0.024, level=0.1, pan=0.62)
    work_stems = {
        "pulse": pulse * 0.82,
        "body": body * 0.7,
        "body-mid": body_mid * 0.7,
        "sub": sub * 0.72,
        "data": data * 0.9,
    }
    mix = pulse * 0.88 + sub * 0.38
    sub_forward_mix = pulse * 0.38 + sub * 0.88
    sub_body_mix = pulse * 0.38 + sub * 0.88 + body * 0.34
    reference_body_mix = pulse * 0.56 + sub * 0.42 + body_mid * 0.72
    reference_data_mix = reference_body_mix + data * 0.28
    rendered = {name: finish_stem(track) for name, track in work_stems.items()}
    rendered["mix"] = master(mix, 0.0)
    rendered["mix-sub-forward"] = master(sub_forward_mix, 0.0)
    rendered["mix-sub-body"] = master(sub_body_mix, 0.0)
    rendered["mix-reference-body"] = master(reference_body_mix, 0.0)
    rendered["mix-reference-data"] = master(reference_data_mix, 0.0)
    return rendered


def build_enriched_core_result(event: str) -> np.ndarray:
    family = FAMILIES[2]
    result_duration = family.result_duration_seconds * (1.18 if event == "threat" else 1.0)
    duration = result_duration + 0.28
    core = new_track(duration)
    body = new_track(duration)
    sub = new_track(duration)
    if event == "complete":
        complete_result(family, core, 0.026)
        body_root = (138.0, 86.0)
        sub_root = (74.0, 43.0)
        seed = 1_121
    elif event == "threat":
        threat_result(family, core, 0.026)
        body_root = (122.0, 68.0)
        sub_root = (67.0, 36.0)
        seed = 1_127
    else:
        raise ValueError(f"Unsupported enriched result event: {event}")
    synthetic_body = synthetic_hud_body(duration * 0.84, body_root, 0.23)
    low_foundation = sub_bloom(duration * 0.9, *sub_root)
    place(body, synthetic_body, 0.018, level=0.48, pan=0.0)
    place(sub, low_foundation, 0.012, level=0.58, pan=0.0)
    dry = core * 0.68 + sub * 0.62 + body * 0.3
    space = stereo_space(core * 0.72 + body * 0.18, seed)
    return master(dry + space * 0.12, 0.08)


def normalize_peak(track: np.ndarray, peak_db: float = -1.2) -> np.ndarray:
    peak = float(np.max(np.abs(track)))
    if peak <= 0.0:
        return track
    return track * (10 ** (peak_db / 20)) / peak


def load_activity_tick_source(path: Path) -> np.ndarray:
    track, sample_rate = sf.read(path, always_2d=True, dtype="float64")
    if track.shape[1] == 1:
        track = np.repeat(track, 2, axis=1)
    elif track.shape[1] > 2:
        track = track[:, :2]
    divisor = math.gcd(sample_rate, OUTPUT_RATE)
    return resample_poly(
        track,
        up=OUTPUT_RATE // divisor,
        down=sample_rate // divisor,
        axis=0,
    )


def build_activity_candidates(activity_tick_source: Path) -> dict[str, np.ndarray]:
    original = normalize_peak(load_activity_tick_source(activity_tick_source))
    low_pass = butter(3, 4_800.0, btype="lowpass", fs=OUTPUT_RATE, output="sos")
    muted = normalize_peak(sosfilt(low_pass, original, axis=0))

    duration = 0.12
    sample_count = round(duration * OUTPUT_RATE)
    hud = np.zeros((sample_count, 2))
    copy_count = min(len(muted), sample_count)
    hud[:copy_count] = muted[:copy_count] * 0.72
    body_work = synthetic_hud_body(0.105, (164.0, 102.0), 0.17)
    body = finish_stem(np.column_stack((body_work, body_work)))
    body_count = min(len(body), sample_count)
    hud[:body_count] += body[:body_count] * 0.18
    hud = normalize_peak(hud)
    return {
        "activity-original": original,
        "activity-muted": muted,
        "activity-hud": hud,
    }


def build_app_feedback_sounds(
    hud_prototype: dict[str, np.ndarray],
    activity_tick_source: Path,
) -> dict[str, np.ndarray]:
    return {
        "attention-sub-forward": hud_prototype["mix-sub-forward"],
        "attention-sub-body": hud_prototype["mix-sub-body"],
        "attention-complete-enriched": build_enriched_core_result("complete"),
        "error-threat-enriched": build_enriched_core_result("threat"),
        **build_activity_candidates(activity_tick_source),
    }


def build_deep_layered_prototype() -> dict[str, np.ndarray]:
    duration = 1.24
    mechanical = new_track(duration)
    tonal = new_track(duration)
    sub = new_track(duration)
    texture = new_track(duration)
    pulse_offsets = (0.055, 0.265)
    for index, offset in enumerate(pulse_offsets):
        pan = -0.22 if index == 0 else 0.22
        modes = tuple(
            frequency * (1.0 + index * 0.027)
            for frequency in (154.0, 247.0, 391.0, 628.0, 1_017.0, 1_649.0)
        )
        impact = modal_impact(0.39, modes, 811 + index)
        place(mechanical, impact, offset, level=0.62, pan=pan)
        for ratchet_index, ratchet_offset in enumerate((0.019, 0.046)):
            ratchet = modal_impact(
                0.12,
                tuple(value * (1.28 + ratchet_index * 0.17) for value in modes[2:]),
                821 + index * 4 + ratchet_index,
            )
            place(
                mechanical,
                ratchet,
                offset + ratchet_offset,
                level=0.14,
                pan=-pan * (0.8 + ratchet_index * 0.2),
            )
        voice = fm_scanner_voice(
            0.31,
            (284.0 + index * 19.0, 132.0 + index * 7.0),
            (96.0 + index * 5.0, 43.0 + index * 3.0),
            (5.8, 1.35),
        )
        place(tonal, voice, offset + 0.008, level=0.68, pan=-pan * 0.42)
        low = sub_bloom(0.5, 78.0 - index * 4.0, 41.0 - index * 2.0)
        place(sub, low, offset, level=0.78, pan=0.0)
        left_texture = electrical_texture(0.28, 853 + index * 2)
        right_texture = electrical_texture(0.28, 854 + index * 2)
        place(texture, left_texture, offset + 0.004, level=0.22, pan=-0.76)
        place(texture, right_texture, offset + 0.011, level=0.22, pan=0.76)
    bridge = rotary_scan(0.54, 198.0, 63.0, 3.25)
    place(tonal, bridge, 0.12, level=0.22, pan=0.0)
    dry_for_space = 0.72 * mechanical + 0.68 * tonal + 0.24 * texture
    space = stereo_space(dry_for_space, 887)
    work_stems = {
        "mechanical": mechanical * 0.86,
        "tonal": tonal * 0.78,
        "sub": sub * 0.9,
        "texture": texture * 0.9,
        "space": space * 0.44,
    }
    mix = sum(work_stems.values(), start=new_track(duration))
    rendered = {name: finish_stem(track) for name, track in work_stems.items()}
    rendered["mix"] = master(mix, 0.0)
    return rendered


def place(track: np.ndarray, signal: np.ndarray, offset_seconds: float, level: float, pan: float) -> None:
    start = round(offset_seconds * WORK_RATE)
    end = min(start + len(signal), len(track))
    if end <= start:
        return
    segment = signal[: end - start] * level
    angle = (pan + 1.0) * math.pi / 4.0
    track[start:end, 0] += segment * math.cos(angle)
    track[start:end, 1] += segment * math.sin(angle)


def scan_preamble(family: SoundFamily, track: np.ndarray) -> float:
    for index in range(2):
        offset = index * family.scan_spacing_seconds
        cricket = robot_cricket(family, alternate=index == 1)
        place(track, cricket, offset, level=0.88, pan=-0.12 if index == 0 else 0.12)
        tick = metallic_tick(family.metallic_hz, min(0.075, family.scan_duration_seconds), 41 + index)
        place(track, tick, offset + 0.008, level=0.23, pan=-0.46 if index == 0 else 0.46)
    return family.scan_spacing_seconds + family.scan_duration_seconds


def neutral_result(family: SoundFamily, track: np.ndarray, offset: float) -> None:
    body = tonal_sweep(
        family.result_duration_seconds,
        *family.result_hz,
        decay_seconds=family.result_duration_seconds * 0.63,
        harmonic_mix=(1.0, 0.28, 0.12, 0.04),
    )
    place(track, body, offset, level=0.93, pan=0.0)
    tick = metallic_tick(family.metallic_hz, 0.11, 73)
    place(track, tick, offset + 0.012, level=0.18, pan=0.21)


def complete_result(family: SoundFamily, track: np.ndarray, offset: float) -> None:
    first = tonal_sweep(
        family.result_duration_seconds * 0.72,
        family.result_hz[0] * 0.92,
        family.result_hz[1] * 1.02,
        decay_seconds=family.result_duration_seconds * 0.38,
        harmonic_mix=(1.0, 0.24, 0.1),
    )
    second = tonal_sweep(
        family.result_duration_seconds,
        family.result_hz[0] * 1.22,
        family.result_hz[1] * 1.46,
        decay_seconds=family.result_duration_seconds * 0.66,
        harmonic_mix=(1.0, 0.31, 0.13, 0.05),
    )
    place(track, first, offset, level=0.66, pan=-0.08)
    place(track, second, offset + 0.078, level=0.86, pan=0.08)
    tick = metallic_tick(tuple(value * 1.11 for value in family.metallic_hz), 0.12, 97)
    place(track, tick, offset + 0.082, level=0.22, pan=0.34)


def threat_result(family: SoundFamily, track: np.ndarray, offset: float) -> None:
    duration = family.result_duration_seconds * 1.18
    primary = tonal_sweep(
        duration,
        family.result_hz[0] * 0.82,
        family.result_hz[1] * 0.62,
        decay_seconds=duration * 0.76,
        harmonic_mix=(1.0, 0.4, 0.19, 0.09, 0.04),
    )
    dissonance = tonal_sweep(
        duration * 0.87,
        family.result_hz[0] * 0.89,
        family.result_hz[1] * 0.71,
        decay_seconds=duration * 0.58,
        harmonic_mix=(0.72, 0.3, 0.12),
    )
    place(track, primary, offset, level=0.94, pan=-0.04)
    place(track, dissonance, offset + 0.018, level=0.57, pan=0.04)
    for index in range(2):
        tick = metallic_tick(tuple(value * 0.83 for value in family.metallic_hz), 0.1, 131 + index)
        place(track, tick, offset + index * 0.072, level=0.2, pan=-0.32 + index * 0.64)


def add_short_room(track: np.ndarray, amount: float) -> np.ndarray:
    wet = track.copy()
    for delay_seconds, gain, crossfeed in (
        (0.029, 0.13, False),
        (0.047, 0.095, True),
        (0.073, 0.068, False),
        (0.109, 0.045, True),
    ):
        delay = round(delay_seconds * WORK_RATE)
        source = track[:-delay, ::-1] if crossfeed else track[:-delay]
        wet[delay:] += source * gain * amount
    return wet


def master(track: np.ndarray, room_amount: float) -> np.ndarray:
    track = add_short_room(track, room_amount)
    track -= np.mean(track, axis=0, keepdims=True)
    track = np.tanh(track * 1.18) / math.tanh(1.18)
    track = resample_poly(track, up=1, down=WORK_RATE // OUTPUT_RATE, axis=0)
    high_pass = butter(3, 24.0, btype="highpass", fs=OUTPUT_RATE, output="sos")
    track = sosfilt(high_pass, track, axis=0)
    peak = float(np.max(np.abs(track)))
    if peak > 0.0:
        track *= MASTER_PEAK / peak
    return track


def build_sound(family: SoundFamily, event: str) -> np.ndarray:
    result_duration = family.result_duration_seconds * (1.18 if event == "threat" else 1.0)
    scan_end = family.scan_spacing_seconds + family.scan_duration_seconds
    if event == "scan":
        track = new_track(scan_end + 0.18)
        scan_preamble(family, track)
    else:
        track = new_track(result_duration + 0.22)
        if event == "neutral":
            neutral_result(family, track, 0.0)
        elif event == "complete":
            complete_result(family, track, 0.0)
        elif event == "threat":
            threat_result(family, track, 0.0)
    room_amount = 0.38 if family.slug == "03-deep" else 0.26
    return master(track, room_amount)


def build_evidence_marker() -> np.ndarray:
    track = new_track(0.76)
    for index, offset in enumerate((0.0, 0.083, 0.166)):
        pulse = scanner_pulse(0.092, (510.0, 286.0), (126.0, 82.0), 92.0, 211 + index)
        place(track, pulse, offset, level=0.68 - index * 0.06, pan=(-0.24, 0.24, 0.0)[index])
    ring = tonal_sweep(0.34, 428.0, 176.0, 0.21, (1.0, 0.37, 0.16, 0.07))
    sub = tonal_sweep(0.42, 112.0, 61.0, 0.3, (1.0, 0.25, 0.08))
    place(track, ring, 0.272, level=0.7, pan=0.0)
    place(track, sub, 0.268, level=0.88, pan=0.0)
    return master(track, 0.24)


def build_scene_reconstruction() -> np.ndarray:
    track = new_track(2.18)
    bed = rotary_scan(1.72, 214.0, 62.0, 7.0)
    place(track, bed, 0.08, level=0.52, pan=0.0)
    offsets = (0.08, 0.31, 0.57, 0.86, 1.18, 1.48)
    for index, offset in enumerate(offsets):
        progress = index / (len(offsets) - 1)
        pulse = scanner_pulse(
            0.17,
            (360.0 + progress * 140.0, 206.0 + progress * 46.0),
            (104.0, 68.0 + progress * 9.0),
            61.0 + index * 3.0,
            307 + index,
        )
        place(track, pulse, offset, level=0.54 + progress * 0.12, pan=-0.72 + progress * 1.44)
    resolve = tonal_sweep(0.48, 91.0, 151.0, 0.34, (1.0, 0.31, 0.12, 0.04))
    tick = metallic_tick((486.0, 811.0, 1_327.0, 2_149.0), 0.19, 331)
    place(track, resolve, 1.66, level=0.86, pan=0.0)
    place(track, tick, 1.68, level=0.19, pan=0.0)
    return master(track, 0.42)


def build_target_track() -> np.ndarray:
    track = new_track(1.66)
    bed = rotary_scan(1.38, 276.0, 73.0, 5.0)
    place(track, bed, 0.08, level=0.42, pan=0.0)
    for index, offset in enumerate((0.06, 0.34, 0.62, 0.9, 1.18)):
        pulse = scanner_pulse(0.13, (434.0, 248.0), (98.0, 72.0), 74.0, 401 + index)
        pan = (-0.64, -0.3, 0.0, 0.3, 0.64)[index]
        place(track, pulse, offset, level=0.58, pan=pan)
        tick = metallic_tick((589.0, 997.0, 1_601.0), 0.08, 431 + index)
        place(track, tick, offset + 0.018, level=0.14, pan=pan)
    return master(track, 0.3)


def build_target_lock() -> np.ndarray:
    track = new_track(1.12)
    offsets = (0.0, 0.145, 0.255, 0.337)
    for index, offset in enumerate(offsets):
        pulse = scanner_pulse(
            0.105,
            (470.0 + index * 34.0, 238.0 + index * 18.0),
            (112.0, 77.0),
            78.0 + index * 6.0,
            503 + index,
        )
        place(track, pulse, offset, level=0.52 + index * 0.07, pan=-0.5 + index / 3.0)
    verdict = tonal_sweep(0.48, 138.0, 64.0, 0.34, (1.0, 0.38, 0.17, 0.07))
    ring = tonal_sweep(0.29, 612.0, 294.0, 0.17, (0.68, 0.25, 0.09))
    place(track, verdict, 0.458, level=0.96, pan=0.0)
    place(track, ring, 0.466, level=0.29, pan=0.0)
    return master(track, 0.3)


def build_render_complete() -> np.ndarray:
    track = new_track(1.36)
    for index, offset in enumerate((0.0, 0.13, 0.26, 0.39, 0.52)):
        progress = index / 4.0
        pulse = scanner_pulse(
            0.16,
            (318.0 + progress * 222.0, 184.0 + progress * 104.0),
            (96.0, 67.0 + progress * 14.0),
            58.0 + index * 5.0,
            607 + index,
        )
        place(track, pulse, offset, level=0.5 + progress * 0.1, pan=(-1.0 + progress * 2.0) * 0.58)
    first = tonal_sweep(0.35, 92.0, 128.0, 0.22, (1.0, 0.28, 0.1))
    second = tonal_sweep(0.47, 121.0, 172.0, 0.34, (1.0, 0.34, 0.13, 0.05))
    place(track, first, 0.7, level=0.54, pan=-0.08)
    place(track, second, 0.79, level=0.83, pan=0.08)
    return master(track, 0.4)


def build_condition_critical() -> np.ndarray:
    track = new_track(1.42)
    for index, offset in enumerate((0.0, 0.235, 0.47)):
        body = tonal_sweep(
            0.36,
            126.0 - index * 8.0,
            61.0 - index * 4.0,
            0.23,
            (1.0, 0.41, 0.19, 0.08),
        )
        rasp = scanner_pulse(0.15, (286.0, 142.0), (84.0, 49.0), 49.0, 701 + index)
        place(track, body, offset, level=0.76 + index * 0.06, pan=-0.08 if index % 2 == 0 else 0.08)
        place(track, rasp, offset + 0.014, level=0.31, pan=0.08 if index % 2 == 0 else -0.08)
    tail = tonal_sweep(0.58, 78.0, 42.0, 0.48, (1.0, 0.32, 0.12))
    place(track, tail, 0.71, level=0.91, pan=0.0)
    return master(track, 0.46)


def build_study(slug: str) -> np.ndarray:
    builders = {
        "04-evidence-marker": build_evidence_marker,
        "05-scene-reconstruction": build_scene_reconstruction,
        "06-target-track": build_target_track,
        "07-target-lock": build_target_lock,
        "08-render-complete": build_render_complete,
        "09-condition-critical": build_condition_critical,
    }
    return builders[slug]()


def render_preview(
    output_directory: Path,
    rendered: dict[str, list[tuple[str, str]]],
    rendered_studies: list[tuple[SoundStudy, str]],
    rendered_hud_prototype: list[tuple[str, str, str]],
    rendered_app_feedback: list[tuple[str, str, str]],
) -> None:
    family_sections = []
    for family in FAMILIES:
        players = []
        for event, filename in rendered[family.slug]:
            players.append(
                f"""
                <article class="sound">
                  <div><strong>{html.escape(event.title())}</strong><span>{html.escape(filename)}</span></div>
                  <audio controls preload="metadata" src="{html.escape(filename)}"></audio>
                </article>
                """
            )
        family_sections.append(
            f"""
            <section>
              <header><p>{html.escape(family.slug)}</p><h2>{html.escape(family.title)}</h2></header>
              <p class="description">{html.escape(family.description)}</p>
              <div class="sounds">{''.join(players)}</div>
            </section>
            """
        )
    study_players = []
    for study, filename in rendered_studies:
        study_players.append(
            f"""
            <article class="sound study">
              <div><strong>{html.escape(study.title)}</strong><span>{html.escape(filename)}</span></div>
              <p>{html.escape(study.description)}</p>
              <audio controls preload="metadata" src="{html.escape(filename)}"></audio>
            </article>
            """
        )
    hud_prototype_players = []
    for slug, description, filename in rendered_hud_prototype:
        title = next(title for part, title, _ in DEEP_HUD_PARTS if part == slug)
        emphasis = " prototype-mix" if slug.startswith("mix") else ""
        hud_prototype_players.append(
            f"""
            <article class="sound study{emphasis}">
              <div><strong>{html.escape(title)}</strong><span>{html.escape(filename)}</span></div>
              <p>{html.escape(description)}</p>
              <audio controls preload="metadata" src="{html.escape(filename)}"></audio>
            </article>
            """
        )
    app_feedback_players = []
    for slug, description, filename in rendered_app_feedback:
        title = next(title for part, title, _ in APP_FEEDBACK_PARTS if part == slug)
        app_feedback_players.append(
            f"""
            <article class="sound study prototype-mix">
              <div><strong>{html.escape(title)}</strong><span>{html.escape(filename)}</span></div>
              <p>{html.escape(description)}</p>
              <audio controls preload="metadata" src="{html.escape(filename)}"></audio>
            </article>
            """
        )
    mixer_markup = """
    <section class="mix-lab">
      <header><p>Interactive</p><h2>HUD mix lab</h2></header>
      <p class="description">Adjust synchronized layers, compare presets, and leave the final recipe in the URL for the next iteration.</p>
      <div class="mixer-panel">
        <div class="preset-row">
          <button type="button" data-preset="baseline">Pulse + Sub</button>
          <button type="button" data-preset="subBody">Sub-forward + Body</button>
          <button type="button" data-preset="referenceBody">Low-mid Body</button>
          <button type="button" data-preset="referenceData">Low-mid + Data</button>
        </div>
        <div class="transport-row">
          <button class="primary" id="mix-play" type="button">Play mix</button>
          <button id="mix-stop" type="button" disabled>Stop</button>
          <label class="loop-control"><input id="mix-loop" type="checkbox"> Loop</label>
          <span id="mix-status">Ready</span>
        </div>
        <div class="mixer-grid">
          <label class="mixer-layer" for="level-pulse"><span>HUD Pulse</span><output id="level-pulse-value">38</output><input id="level-pulse" data-layer="pulse" type="range" min="0" max="120" step="1" value="38"></label>
          <label class="mixer-layer" for="level-sub"><span>Mono Sub</span><output id="level-sub-value">88</output><input id="level-sub" data-layer="sub" type="range" min="0" max="120" step="1" value="88"></label>
          <label class="mixer-layer" for="level-body"><span>Synthetic Body</span><output id="level-body-value">34</output><input id="level-body" data-layer="body" type="range" min="0" max="120" step="1" value="34"></label>
          <label class="mixer-layer" for="level-mid"><span>Raised Body</span><output id="level-mid-value">0</output><input id="level-mid" data-layer="mid" type="range" min="0" max="120" step="1" value="0"></label>
          <label class="mixer-layer" for="level-data"><span>Data Detail</span><output id="level-data-value">0</output><input id="level-data" data-layer="data" type="range" min="0" max="120" step="1" value="0"></label>
        </div>
        <div class="recipe-row"><div><span>Current recipe</span><code id="mix-recipe"></code></div><button id="mix-copy" type="button">Copy link</button></div>
      </div>
    </section>
    """
    mixer_script = """
    <script>
    (() => {
      const stems = {
        pulse: { url: "11-deep-hud-pulse.wav", renderedScale: 0.82 },
        sub: { url: "11-deep-hud-sub.wav", renderedScale: 0.72 },
        body: { url: "11-deep-hud-body.wav", renderedScale: 0.70 },
        mid: { url: "11-deep-hud-body-mid.wav", renderedScale: 0.70 },
        data: { url: "11-deep-hud-data.wav", renderedScale: 0.90 },
      };
      const presets = {
        baseline: { pulse: 88, sub: 38, body: 0, mid: 0, data: 0 },
        subBody: { pulse: 38, sub: 88, body: 34, mid: 0, data: 0 },
        referenceBody: { pulse: 56, sub: 42, body: 0, mid: 72, data: 0 },
        referenceData: { pulse: 56, sub: 42, body: 0, mid: 72, data: 28 },
      };
      const sliders = Object.fromEntries(
        [...document.querySelectorAll("[data-layer]")].map(slider => [slider.dataset.layer, slider])
      );
      const playButton = document.querySelector("#mix-play");
      const stopButton = document.querySelector("#mix-stop");
      const loopControl = document.querySelector("#mix-loop");
      const status = document.querySelector("#mix-status");
      const recipe = document.querySelector("#mix-recipe");
      let context;
      let buffers;
      let loadPromise;
      let activeSources = [];
      let activeGains = {};
      let masterGain;
      let playing = false;

      const currentLevels = () => Object.fromEntries(
        Object.entries(sliders).map(([name, slider]) => [name, Number(slider.value)])
      );

      const updateRecipe = () => {
        const levels = currentLevels();
        for (const [name, value] of Object.entries(levels)) {
          document.querySelector(`#level-${name}-value`).value = value;
        }
        const parameters = new URLSearchParams(levels);
        history.replaceState(null, "", `${location.pathname}${location.search}#${parameters}`);
        recipe.textContent = [...parameters].map(([name, value]) => `${name}=${value}`).join("  ");
        for (const button of document.querySelectorAll("[data-preset]")) {
          const preset = presets[button.dataset.preset];
          button.classList.toggle(
            "active",
            Object.keys(sliders).every(name => levels[name] === preset[name]),
          );
        }
      };

      const readHash = () => {
        const parameters = new URLSearchParams(location.hash.slice(1));
        if (![...parameters.keys()].some(key => key in sliders)) return null;
        return Object.fromEntries(
          Object.keys(sliders).map(name => [name, Number(parameters.get(name) ?? 0)])
        );
      };

      const setLevels = levels => {
        for (const [name, slider] of Object.entries(sliders)) {
          slider.value = Math.max(0, Math.min(120, levels[name] ?? 0));
        }
        updateRecipe();
        updateLiveMix();
      };

      const loadBuffers = async () => {
        if (!context) {
          const AudioContextClass = window.AudioContext || window.webkitAudioContext;
          context = new AudioContextClass();
          masterGain = context.createGain();
          masterGain.connect(context.destination);
        }
        await context.resume();
        if (!loadPromise) {
          status.textContent = "Loading layers...";
          loadPromise = Promise.all(
            Object.entries(stems).map(async ([name, stem]) => {
              const response = await fetch(stem.url);
              if (!response.ok) throw new Error(`Failed to load ${stem.url}`);
              return [name, await context.decodeAudioData(await response.arrayBuffer())];
            })
          ).then(entries => Object.fromEntries(entries));
        }
        buffers = await loadPromise;
        status.textContent = "Ready";
      };

      const normalizedMasterGain = levels => {
        if (!buffers) return 0.55;
        const names = Object.keys(stems);
        const frameCount = Math.max(...names.map(name => buffers[name].length));
        const layers = names.map(name => {
          const buffer = buffers[name];
          return {
            gain: (levels[name] / 100) / stems[name].renderedScale,
            left: buffer.getChannelData(0),
            right: buffer.getChannelData(Math.min(1, buffer.numberOfChannels - 1)),
          };
        });
        let peak = 0;
        for (let channel = 0; channel < 2; channel += 1) {
          for (let frame = 0; frame < frameCount; frame += 1) {
            let sample = 0;
            for (const layer of layers) {
              const samples = channel === 0 ? layer.left : layer.right;
              if (frame < samples.length) sample += samples[frame] * layer.gain;
            }
            peak = Math.max(peak, Math.abs(sample));
          }
        }
        return peak > 0 ? Math.min(1.0, 0.87 / peak) : 1.0;
      };

      const updateLiveMix = () => {
        if (!context || !buffers) return;
        const levels = currentLevels();
        for (const [name, gainNode] of Object.entries(activeGains)) {
          const gain = (levels[name] / 100) / stems[name].renderedScale;
          gainNode.gain.setTargetAtTime(gain, context.currentTime, 0.012);
        }
        masterGain.gain.setTargetAtTime(
          normalizedMasterGain(levels),
          context.currentTime,
          0.012,
        );
      };

      const finishPlayback = () => {
        activeSources = [];
        activeGains = {};
        playing = false;
        playButton.textContent = "Play mix";
        stopButton.disabled = true;
        status.textContent = "Ready";
      };

      const stopPlayback = () => {
        for (const source of activeSources) {
          try { source.stop(); } catch (_) {}
        }
        finishPlayback();
      };

      const playMix = async () => {
        stopPlayback();
        playButton.disabled = true;
        try {
          await loadBuffers();
          const levels = currentLevels();
          const startAt = context.currentTime + 0.045;
          const entries = Object.entries(stems);
          activeSources = entries.map(([name]) => {
            const source = context.createBufferSource();
            const gain = context.createGain();
            source.buffer = buffers[name];
            source.loop = loopControl.checked;
            gain.gain.value = (levels[name] / 100) / stems[name].renderedScale;
            source.connect(gain).connect(masterGain);
            activeGains[name] = gain;
            source.start(startAt);
            return source;
          });
          masterGain.gain.value = normalizedMasterGain(levels);
          activeSources[0].onended = () => {
            if (playing && !loopControl.checked) finishPlayback();
          };
          playing = true;
          playButton.textContent = "Restart mix";
          stopButton.disabled = false;
          status.textContent = loopControl.checked ? "Playing loop" : "Playing";
        } catch (error) {
          finishPlayback();
          status.textContent = error.message;
        } finally {
          playButton.disabled = false;
        }
      };

      for (const slider of Object.values(sliders)) {
        slider.addEventListener("input", () => {
          updateRecipe();
          updateLiveMix();
        });
      }
      for (const button of document.querySelectorAll("[data-preset]")) {
        button.addEventListener("click", () => setLevels(presets[button.dataset.preset]));
      }
      playButton.addEventListener("click", playMix);
      stopButton.addEventListener("click", stopPlayback);
      loopControl.addEventListener("change", () => {
        for (const source of activeSources) source.loop = loopControl.checked;
        if (playing) status.textContent = loopControl.checked ? "Playing loop" : "Playing";
      });
      document.querySelector("#mix-copy").addEventListener("click", async event => {
        await navigator.clipboard.writeText(location.href);
        event.currentTarget.textContent = "Copied";
        setTimeout(() => { event.currentTarget.textContent = "Copy link"; }, 1_200);
      });
      setLevels(readHash() ?? presets.subBody);
      window.hudMixerState = currentLevels;
    })();
    </script>
    """
    page = f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Gromozeka HUD sound study</title>
  <style>
    :root {{ color-scheme: dark; --ink: #ecf4ee; --muted: #8ca299; --panel: #13201d; --edge: #30453d; --signal: #ee4e3f; }}
    * {{ box-sizing: border-box; }}
    body {{ margin: 0; background: radial-gradient(circle at 15% 0%, #20372f, #080d0c 42rem); color: var(--ink); font: 15px/1.45 ui-monospace, SFMono-Regular, Menlo, monospace; }}
    main {{ width: min(920px, calc(100% - 32px)); margin: 0 auto; padding: 56px 0 80px; }}
    h1 {{ max-width: 700px; margin: 0; font-size: clamp(32px, 7vw, 68px); line-height: .95; letter-spacing: -.07em; }}
    .lead {{ max-width: 700px; color: var(--muted); margin: 24px 0 52px; }}
    section {{ border-top: 1px solid var(--edge); padding: 28px 0 38px; }}
    section header {{ display: flex; align-items: baseline; gap: 18px; }}
    section header p {{ margin: 0; color: var(--signal); font-size: 11px; text-transform: uppercase; letter-spacing: .12em; }}
    h2 {{ margin: 0; font-size: 24px; }}
    .description {{ color: var(--muted); margin: 8px 0 22px; }}
    .sounds {{ display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }}
    .sound {{ min-width: 0; border: 1px solid var(--edge); background: color-mix(in srgb, var(--panel) 88%, transparent); padding: 14px; }}
    .sound div {{ display: flex; justify-content: space-between; gap: 12px; margin-bottom: 12px; }}
    .sound span {{ overflow: hidden; color: var(--muted); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }}
    .study p {{ min-height: 42px; margin: 0 0 14px; color: var(--muted); font-size: 12px; }}
    .prototype-mix {{ border-color: var(--signal); background: color-mix(in srgb, var(--signal) 9%, var(--panel)); }}
    .mixer-panel {{ border: 1px solid var(--edge); background: color-mix(in srgb, var(--panel) 92%, transparent); padding: 18px; }}
    .preset-row, .transport-row {{ display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }}
    .transport-row {{ margin: 14px 0 18px; }}
    button {{ border: 1px solid var(--edge); border-radius: 4px; background: #192823; color: var(--ink); padding: 8px 11px; font: inherit; cursor: pointer; }}
    button:hover {{ border-color: var(--muted); }}
    button.active {{ border-color: var(--signal); background: color-mix(in srgb, var(--signal) 14%, #192823); }}
    button:disabled {{ cursor: default; opacity: .45; }}
    button.primary {{ border-color: var(--signal); background: color-mix(in srgb, var(--signal) 18%, #192823); }}
    .loop-control {{ display: flex; align-items: center; gap: 6px; color: var(--muted); margin-left: 4px; }}
    #mix-status {{ color: var(--muted); font-size: 12px; margin-left: auto; }}
    .mixer-grid {{ display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 18px; }}
    .mixer-layer {{ display: grid; grid-template-columns: 1fr auto; gap: 5px 12px; align-items: center; }}
    .mixer-layer output {{ color: var(--signal); font-variant-numeric: tabular-nums; }}
    .mixer-layer input {{ grid-column: 1 / -1; width: 100%; accent-color: var(--signal); }}
    .recipe-row {{ display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; border-top: 1px solid var(--edge); margin-top: 18px; padding-top: 14px; }}
    .recipe-row div {{ display: grid; gap: 4px; min-width: 0; }}
    .recipe-row span {{ color: var(--muted); font-size: 10px; text-transform: uppercase; letter-spacing: .1em; }}
    .recipe-row code {{ overflow-wrap: anywhere; color: var(--ink); }}
    audio {{ width: 100%; height: 34px; }}
    @media (max-width: 620px) {{ main {{ padding-top: 32px; }} .sounds, .mixer-grid {{ grid-template-columns: 1fr; }} #mix-status {{ width: 100%; margin-left: 0; }} .recipe-row {{ align-items: stretch; flex-direction: column; }} }}
  </style>
</head>
<body>
  <main>
    <h1>HUD sound study</h1>
    <p class="lead">Full-band 48 kHz / 24-bit masters. Five balances compare the same synthetic HUD vocabulary without physical-world layers.</p>
    <section>
      <header><p>Application roles</p><h2>Feedback candidates</h2></header>
      <p class="description">Attention is model-requested, Error is a surfaced failure, and Activity is a rate-limited pulse tied to observed progress.</p>
      <div class="sounds">{''.join(app_feedback_players)}</div>
    </section>
    {mixer_markup}
    <section>
      <header><p>Synthetic HUD</p><h2>Deep HUD prototype</h2></header>
      <p class="description">The retained baseline and bass-forward candidates are followed by two reference-shaped low-mid experiments. Individual components remain below.</p>
      <div class="sounds">{''.join(hud_prototype_players)}</div>
    </section>
    <section>
      <header><p>Second reference</p><h2>Forensic studies</h2></header>
      <p class="description">Original synthesis informed by the scene reconstruction, target tracking, and biological diagnostic sequences.</p>
      <div class="sounds">{''.join(study_players)}</div>
    </section>
    <section>
      <header><p>First reference</p><h2>Core vocabulary</h2></header>
      <p class="description">Play Scan independently, then compare the three clean result sounds without a repeated acquisition prefix.</p>
    </section>
    {''.join(family_sections)}
  </main>
  {mixer_script}
</body>
</html>
"""
    (output_directory / "index.html").write_text(page, encoding="utf-8")


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("build/sound-design/hud-theme-v2"),
    )
    parser.add_argument(
        "--activity-tick-source",
        type=Path,
        default=Path("scripts/sound-design/sources/activity-tick-source.wav"),
    )
    parser.add_argument(
        "--app-output",
        type=Path,
        default=Path("presentation/src/commonMain/composeResources/files/sounds"),
    )
    return parser.parse_args()


def main() -> None:
    arguments = parse_arguments()
    arguments.output.mkdir(parents=True, exist_ok=True)
    rendered: dict[str, list[tuple[str, str]]] = {}
    for family in FAMILIES:
        rendered[family.slug] = []
        for event in ("scan", "neutral", "complete", "threat"):
            filename = f"{family.slug}-{event}.wav"
            sound = build_sound(family, event)
            sf.write(arguments.output / filename, sound, OUTPUT_RATE, subtype="PCM_24")
            rendered[family.slug].append((event, filename))
    rendered_studies = []
    for study in STUDIES:
        filename = f"{study.slug}.wav"
        sf.write(arguments.output / filename, build_study(study.slug), OUTPUT_RATE, subtype="PCM_24")
        rendered_studies.append((study, filename))
    prototype = build_deep_layered_prototype()
    for slug, _, description in DEEP_LAYERED_PARTS:
        filename = f"10-deep-layered-{slug}.wav"
        sf.write(arguments.output / filename, prototype[slug], OUTPUT_RATE, subtype="PCM_24")
    hud_prototype = build_deep_hud_prototype()
    rendered_hud_prototype = []
    for slug, _, description in DEEP_HUD_PARTS:
        filename = f"11-deep-hud-{slug}.wav"
        sf.write(arguments.output / filename, hud_prototype[slug], OUTPUT_RATE, subtype="PCM_24")
        rendered_hud_prototype.append((slug, description, filename))
    app_feedback = build_app_feedback_sounds(hud_prototype, arguments.activity_tick_source)
    rendered_app_feedback = []
    for slug, _, description in APP_FEEDBACK_PARTS:
        filename = f"12-app-{slug}.wav"
        sf.write(arguments.output / filename, app_feedback[slug], OUTPUT_RATE, subtype="PCM_24")
        rendered_app_feedback.append((slug, description, filename))
    arguments.app_output.mkdir(parents=True, exist_ok=True)
    for filename, slug in APP_RUNTIME_SELECTION.items():
        sf.write(arguments.app_output / filename, app_feedback[slug], OUTPUT_RATE, subtype="PCM_16")
    render_preview(
        arguments.output,
        rendered,
        rendered_studies,
        rendered_hud_prototype,
        rendered_app_feedback,
    )
    print(arguments.output.resolve())


if __name__ == "__main__":
    main()
