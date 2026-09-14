"""Synthesise looping engine notes for AshVehicles.

Everything here is built from sine components whose frequencies are whole
numbers of hertz, and the buffer is a whole number of seconds. Every component
therefore completes an exact number of cycles inside the buffer, so the last
sample joins the first with no step -- which is what a seamless loop is. Noise
is made the same way (a dense sum of harmonics with fixed random phases)
rather than from a random generator, because a random buffer does not loop.

The rate is not free, because the Vorbis stage rounds. The encoder rounds the
declared length of the stream up to a multiple of 64 samples, and the decoder
hands back whole blocks of 256, and both fill what they added with a fade to
silence. That fade lands exactly on the loop join, which is a tick once per
loop -- the one artefact all of the above exists to avoid. A rate whose whole
seconds are already a multiple of 256 leaves neither of them anything to round:
32000 works (32000 = 256 * 125), 24000 satisfies only the encoder, and 22050
satisfies neither. Check it by reading the last Ogg page -- its granule
position must equal the sample count written -- and by decoding the file back
and checking that too.
"""

import math
import os
import random
import struct
import sys
import wave

# 32000, not 22050. See the note above -- whole seconds here are a whole
# number of 256-sample blocks, which is what keeps the loop join clean through
# the encoder and back out of the decoder.
RATE = 32000
OUT = sys.argv[1]

def build(seconds, parts, seed):
    """parts: list of (freq_hz, amplitude, phase_jitter). Returns float samples."""
    rng = random.Random(seed)
    n = int(RATE * seconds)
    buf = [0.0] * n
    for freq, amp, jitter in parts:
        if freq <= 0 or freq >= RATE / 2:
            continue
        phase = rng.random() * 2.0 * math.pi if jitter else 0.0
        step = 2.0 * math.pi * freq / RATE
        for i in range(n):
            buf[i] += amp * math.sin(step * i + phase)
    return buf


def band(low, high, amp, tilt, seed, count=None):
    """A dense set of integer-hertz partials: loopable broadband noise."""
    rng = random.Random(seed)
    low, high = int(low), int(high)
    freqs = list(range(low, high + 1))
    if count and count < len(freqs):
        freqs = rng.sample(freqs, count)
    parts = []
    for f in freqs:
        # tilt < 0 rolls the top off, which is what makes it read as air
        parts.append((f, amp * (f / low) ** tilt, True))
    return parts


def pulses(period_samples, seconds, decay, brightness, seed):
    """A periodic impulse train shaped into thumps.

    Two things make this loop cleanly and both are easy to get wrong. The
    period must be a whole number of samples that divides the buffer, so the
    train does not drift across the seam. And every thump must be identical --
    same partials, same phase -- so that the tail of the last one, wrapping
    onto the head of the first, lands exactly where the next thump's tail
    would have. Give the thumps random phases and the wrap no longer matches,
    which is heard as a click once per loop.
    """
    n = int(RATE * seconds)
    assert n % period_samples == 0, 'period must divide the buffer'
    buf = [0.0] * n
    length = int(RATE * decay * 4.0)
    for at in range(0, n, period_samples):
        for f, a in brightness:
            step = 2.0 * math.pi * f / RATE
            for i in range(length):
                buf[(at + i) % n] += a * math.exp(-i / (RATE * decay)) * math.sin(step * i)
    return buf



def hump(peak_hz, width, amp, low, high, seed, count=220):
    """Partials whose levels follow a hump centred on peak_hz.

    Jet mixing noise is not flat: measured spectra are self-similar humps that
    are plotted against log(f / f_peak), which is why the same shape fits jets
    of every size once it is placed on its own peak frequency. A Gaussian in
    log-frequency is that shape -- `width` is its half-width in natural log
    units, so 0.7 is about one octave either side.
    """
    rng = random.Random(seed)
    freqs = rng.sample(range(int(low), int(high) + 1), min(count, int(high) - int(low) + 1))
    parts = []
    for f in freqs:
        level = math.exp(-0.5 * (math.log(f / peak_hz) / width) ** 2)
        parts.append((f, amp * level, True))
    return parts


def comb(base_hz, count, amp, seed, tilt=-1.0, spread=0.55):
    """Harmonics of one shaft speed with uneven strengths: buzz-saw noise.

    When the fan tips go supersonic each blade throws a shock up the intake,
    and because no two blades are dimensionally identical the shocks differ in
    strength and spacing. What arrives is not the blade-passing tone but a
    rasp of harmonics of the *shaft* rotation, with irregular amplitudes. The
    irregularity is the point -- an even comb sounds like an organ, not a fan.
    """
    rng = random.Random(seed)
    parts = []
    for k in range(1, count + 1):
        f = base_hz * k
        level = amp * (k ** tilt) * (1.0 - spread + rng.random() * spread * 2.0)
        parts.append((f, level, True))
    return parts


def mix(*layers):
    n = max(len(x) for x in layers)
    out = [0.0] * n
    for layer in layers:
        for i, v in enumerate(layer):
            out[i] += v
    return out


def write(path, buf, peak=0.86):
    high = max(1e-9, max(abs(v) for v in buf))
    scale = peak / high
    frames = b''.join(struct.pack('<h', int(max(-32767, min(32767, v * scale * 32767)))) for v in buf)
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(frames)


# ---------------------------------------------------------------- jet
# Built to the shape real turbofan spectra have rather than to taste. Sources,
# in the order they matter for a fighter at power:
#
#   * Jet mixing noise. Two self-similar components -- a peaky one from the
#     large turbulence structures and a very broad, nearly isotropic one from
#     the fine scale. Both peak at a Strouhal number of about 0.2, so for a
#     nozzle around 0.9 m passing gas at roughly 550 m/s the peak lands near
#     120 Hz. This is the part that carries to the horizon.
#   * Buzz-saw. Harmonics of the shaft, not of blade passing, with uneven
#     amplitudes. The rasp that says "turbofan" rather than "wind".
#   * Blade passing tone and its second harmonic, high and thin.
#   * Combustion, a low broad hump of its own.
#
# The shaft is taken as 200 Hz (12,000 rpm) and the fan as 24 blades, which
# puts blade passing at 4800 Hz.
SHAFT = 200
BLADES = 24

jet = mix(
    # large turbulence structures: peaky, and the loudest thing here
    build(2.0, hump(120, 0.62, 0.055, 30, 900, 41, count=210), 41),
    # fine-scale turbulence: broad, quieter, reaching much further up
    build(2.0, hump(420, 1.35, 0.020, 60, 7000, 42, count=260), 42),
    # buzz-saw rasp
    build(2.0, comb(SHAFT, 14, 0.030, 43, tilt=-1.15), 43),
    # blade passing and its octave
    build(2.0, [(SHAFT * BLADES, 0.030, True), (SHAFT * BLADES * 2, 0.010, True)], 44),
    # combustion
    build(2.0, hump(380, 0.80, 0.012, 120, 1400, 45, count=120), 45),
)

# ---------------------------------------------------------------- rotor
# Blade slap first, turbine second. The slap is the signature: a heavy periodic
# thump at blade-passage rate, which is why a helicopter is recognisable long
# before it is visible.
rotor = mix(
    # 3200 samples is 10 Hz at this rate: a four-blade head turning 150 rpm
    pulses(3200, 2.0, 0.055, [(46, 1.0), (92, 0.55), (150, 0.30), (240, 0.16)], 21),
    build(2.0, band(38, 420, 0.016, -0.7, 22, count=120), 22),
    build(2.0, [(1320, 0.045, True), (2640, 0.018, True)], 23),
    build(2.0, band(700, 3600, 0.005, -0.9, 24, count=120), 24),
)

# ---------------------------------------------------------------- tank
# Diesel: firing strokes, harmonics, and the track clatter above them.
tank = mix(
    # 1280 samples is 25 Hz: a V12 at 250 rpm of firing strokes, idling hard
    pulses(1280, 1.0, 0.030, [(58, 1.0), (116, 0.42), (174, 0.22), (290, 0.12)], 31),
    build(1.0, band(30, 300, 0.024, -0.6, 32, count=130), 32),
    build(1.0, band(600, 3000, 0.006, -1.0, 33, count=130), 33),
)

# ---------------------------------------------------------------- prop
# Turboprop. Nothing about a Hercules sounds like a fighter, and the reason is
# that a propeller is tonal where a jet is broadband: cabin measurements are
# dominated by the blade-passing frequency and its first three or four
# harmonics, and very little else. Four blades at 1020 rpm is 68 Hz, which is
# the C-130H figure; the J model turns six blades at the same speed and lands
# at 102 Hz, which is why the two aircraft are told apart by ear so easily.
#
# The second line one hertz above the first is the beat. Four engines are never
# exactly synchronised and the slow throb that comes of it is the most
# recognisable thing about a four-engine turboprop. One hertz is two whole
# beats inside the buffer, so it loops with everything else.
BLADE_PASSING = 68

prop = mix(
    build(2.0, [(BLADE_PASSING, 0.26, False), (BLADE_PASSING + 1, 0.10, False),
                (BLADE_PASSING * 2, 0.32, True), (BLADE_PASSING * 2 + 2, 0.11, True),
                (BLADE_PASSING * 3, 0.20, True), (BLADE_PASSING * 4, 0.12, True),
                (BLADE_PASSING * 5, 0.065, True), (BLADE_PASSING * 6, 0.035, True),
                (BLADE_PASSING * 7, 0.020, True)], 51),
    # the core behind the gearbox: thin, high, and much quieter than the blades
    build(2.0, [(1428, 0.022, True), (2856, 0.008, True)], 52),
    # exhaust and the air the blades throw
    build(2.0, band(40, 500, 0.011, -0.6, 53, count=140), 53),
    build(2.0, band(600, 5000, 0.004, -0.9, 54, count=180), 54),
)

for name, buf in (('jet', jet), ('rotor', rotor), ('tank', tank), ('prop', prop)):
    path = os.path.join(OUT, name + '.wav')
    write(path, buf)
    print(name, len(buf), 'samples', round(len(buf) / RATE, 2), 's')
