"""Synthesise the effect sounds AshVehicles asks for but never shipped.

Nine files: a round going into armour, a round skipping off it, ground crew
hanging a store on a rack, the landing gear travelling, the tone that says
a seeker lock just broke, the one that says a target is finished, the siren a
dive bomber screams with, a powered turret going round, and the handwheel a
towed gun is laid with.

Read tool/make_engine_sounds.py first. The rate rule there applies here too and
for the same reason: 32000 Hz, whole seconds, so the Vorbis stage has nothing
to round and nothing to pad. Four of these loop, but a one-shot that ends
in encoder padding still ends in a small step, so the rule is kept throughout.

The difference from the engine tool is that four of these are one-shots, and a
one-shot may use real noise. Looping forbids a random generator -- a random
buffer does not join itself -- which is why the engine notes are built from
sums of partials. Nothing here except the gear, the siren and the two mounts
has to join anything, so noise through a filter is both cheaper and better, and
the filters are what turn a hiss into a piece of struck metal.

A loop may not be filtered either, for the same reason it may not be seeded: a
biquad carries state, and the state at the last sample is not the state at the
first. Where a loop needs the shape a filter would give it -- the two mounts
both do -- the shape is applied as a weight on each partial instead. See
resonance().

What the numbers are taken from, rather than chosen by ear:

  * Struck steel plate. Measured impact spectra put the audible modes at
    612 Hz, 936 Hz and 3183 Hz. Both the impact and the ricochet start from
    those, because it is the same plate; what differs is how long it rings.
    A round that stays in the plate leaves its energy there and the ring is
    killed by the mass behind it. A round that leaves takes most of its energy
    with it, and the plate is free to ring on.
  * Ricochet whine. A glancing hit flattens one side of the round, so it
    tumbles, and the turbulence off the asymmetric shape whistles. A measured
    example has a dominant 633 Hz with neighbours at 324 Hz and 910 Hz -- an
    inharmonic chord, not a note. Each deflection also takes up to 35% of the
    velocity, and the round is going away, so the whine falls as it fades.
  * Hydraulic pump. Aircraft gear runs off an axial piston pump, and the noise
    is the pistons passing: nine of them at 1500 rpm is 225 Hz. That, its
    harmonics, and fluid rush is most of what a gear bay sounds like.
  * Cockpit tone. Flight-deck alerts sit near 1000 Hz and last about 150 ms,
    and anything above 2 kHz is lost under a mask. The lock-lost tone is two
    of those falling, placed below the search growl (790-1100 Hz measured) and
    well below the lock tone (1860-2110 Hz) so that three signals from one
    instrument stay three signals.
  * Kill confirmation. The same instrument, and the same 150 ms tones, but
    rising -- the crew already reads falling pairs as something lost. It sits
    in the gap the seeker tones leave (880 Hz to 1320 Hz, a fifth) so it is
    neither the lost tone below it nor the lock tone above it. Under the pair
    is one struck-plate note from the impact set, because what the crew is
    being told about started as a round going into armour.
  * Bomb rack. The store goes up onto the hooks (a heavy seat), the hooks
    close, and then the sway brace arms are wound down against a ratchet and
    pawl. Three sounds, in that order, which is why the load note is not one
    clunk.
  * Powered turret. The drive is an electric motor through a reduction train,
    so what is heard is the motor and the teeth, not the turret. 3000 rpm is
    50 Hz on the motor shaft; a 24-slot armature turns that into the 1200 Hz
    whine a turret drive is recognised by, and the first reduction pinion (12
    teeth on the same shaft) meshes at 600 Hz. Under it is the ring: sixty
    tonnes going round on a bearing, which is broad and low and has no note.
  * Handwheel. A towed gun has no motor at all -- the gunner turns a wheel
    against a worm, and every sound is a tooth. One turn a second through an
    18-tooth pinion is 18 clicks a second, which is a rattle rather than a
    note, so it is built as a pulse train: every harmonic, in phase. The gun
    is what shapes it, so the harmonics are weighted by the structure instead
    of being filtered, and the hand itself shows up as one slow beat a turn.
    Both mounts are played back between 0.8 and 1.15 of their note by the
    speed the mount is actually turning at, which is the same thing happening
    to the real machine: teeth that meet faster are heard faster.
  * Dive siren. A siren makes its note by chopping airflow, so the note is the
    blade passing frequency -- slots times revolutions -- and the waveform is a
    pulse train, not a sine: every harmonic present, falling about 1/n. That is
    what makes a siren a scream rather than a whistle. The Jericho-Trompete was
    turned by a propeller on the gear leg, so its note rose with the dive: the
    file holds one steady note and DiveSoundInstance moves it between 0.7 and
    1.8 of it, which is the same relationship the device itself had.

Usage: python tool/make_effect_sounds.py <output directory>
"""

import math
import os
import random
import struct
import sys
import wave

# Same rate as the engine notes, for the reason in that file.
RATE = 32000
OUT = sys.argv[1]


def frames(seconds):
    """Sample count for a duration, rounded up to a whole 256-sample block."""
    n = int(math.ceil(RATE * seconds))
    return n + (-n % 256)


def white(n, seed):
    rng = random.Random(seed)
    return [rng.uniform(-1.0, 1.0) for _ in range(n)]


def biquad(x, b0, b1, b2, a0, a1, a2):
    b0, b1, b2, a1, a2 = b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0
    out = [0.0] * len(x)
    x1 = x2 = y1 = y2 = 0.0
    for i, v in enumerate(x):
        y = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        out[i] = y
        x2, x1 = x1, v
        y2, y1 = y1, y
    return out


def bandpass(x, freq, q):
    w = 2.0 * math.pi * freq / RATE
    alpha = math.sin(w) / (2.0 * q)
    return biquad(x, alpha, 0.0, -alpha, 1.0 + alpha, -2.0 * math.cos(w), 1.0 - alpha)


def lowpass(x, freq, q=0.707):
    w = 2.0 * math.pi * freq / RATE
    alpha = math.sin(w) / (2.0 * q)
    c = math.cos(w)
    return biquad(x, (1.0 - c) / 2.0, 1.0 - c, (1.0 - c) / 2.0,
                  1.0 + alpha, -2.0 * c, 1.0 - alpha)


def highpass(x, freq, q=0.707):
    w = 2.0 * math.pi * freq / RATE
    alpha = math.sin(w) / (2.0 * q)
    c = math.cos(w)
    return biquad(x, (1.0 + c) / 2.0, -(1.0 + c), (1.0 + c) / 2.0,
                  1.0 + alpha, -2.0 * c, 1.0 - alpha)


def decay(x, seconds, attack=0.0008):
    """Exponential fall, with a short ramp on so the first sample is silence."""
    out = [0.0] * len(x)
    rise = max(1, int(RATE * attack))
    for i, v in enumerate(x):
        a = min(1.0, i / rise)
        out[i] = v * a * math.exp(-i / (RATE * seconds))
    return out


def mode(n, freq, amp, seconds, phase=0.0):
    """One decaying sinusoid: a single resonance of something struck."""
    step = 2.0 * math.pi * freq / RATE
    return [amp * math.exp(-i / (RATE * seconds)) * math.sin(step * i + phase)
            for i in range(n)]


def glide(n, start_hz, end_hz, amp, seconds, wobble_hz=0.0, wobble=0.0):
    """A falling tone. Frequency slides in log space; amplitude falls away.

    The ricochet whine is this: a note that drops as the round slows and
    recedes, with the tumble beating on top of it.
    """
    out = [0.0] * n
    phase = 0.0
    for i in range(n):
        t = i / max(1, n - 1)
        f = start_hz * (end_hz / start_hz) ** t
        phase += 2.0 * math.pi * f / RATE
        level = math.exp(-i / (RATE * seconds))
        if wobble:
            level *= 1.0 - wobble + wobble * math.cos(2.0 * math.pi * wobble_hz * i / RATE)
        out[i] = amp * level * math.sin(phase)
    return out


def place(dst, src, at, gain=1.0):
    """Mix one event into the output at a sample offset."""
    for i, v in enumerate(src):
        j = at + i
        if 0 <= j < len(dst):
            dst[j] += v * gain
    return dst


def add(dst, *layers):
    for layer in layers:
        for i, v in enumerate(layer):
            if i < len(dst):
                dst[i] += v
    return dst


def partials(seconds, parts, seed):
    """Integer-hertz sines over a whole number of seconds: the loopable kind."""
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


def resonance(f, centre, q):
    """How much a single resonance passes at one frequency. A weight, not a filter.

    The magnitude of a two-pole bandpass: zero at nothing and at everything,
    one at the centre. Applied to the amplitude of each partial it gives a loop
    the shape a filter would have given it, without the state that would break
    the seam.
    """
    r = f / centre
    x = r / q
    return x / math.sqrt((1.0 - r * r) ** 2 + x * x)


def rotate(buf, samples):
    """Turn a loop so that a chosen instant is no longer at the seam.

    A buffer built from whole hertz over whole seconds joins itself at every
    sample, not only at the first, so it may be cut anywhere at all. What that
    buys is a choice of where the cut lands. It matters for anything with a
    transient in it: the join itself stays exact either way, but an encoder
    given a loud click hard against the boundary smears it across the join and
    the click is heard twice. Cut between the clicks instead.
    """
    k = samples % len(buf)
    return buf[k:] + buf[:k]


def modulate(buf, hz, depth):
    """One slow swell over the buffer. Whole hertz only, or the seam breaks."""
    return [v * (1.0 - depth + depth * (0.5 - 0.5 * math.cos(2.0 * math.pi * hz * i / RATE)))
            for i, v in enumerate(buf)]


def band(low, high, amp, tilt, seed, count=None):
    """A dense set of integer-hertz partials: broadband noise that loops."""
    rng = random.Random(seed)
    low, high = int(low), int(high)
    freqs = list(range(low, high + 1))
    if count and count < len(freqs):
        freqs = rng.sample(freqs, count)
    return [(f, amp * (f / low) ** tilt, True) for f in freqs]


def write(path, buf, peak=0.86):
    high = max(1e-9, max(abs(v) for v in buf))
    scale = peak / high
    data = b''.join(struct.pack('<h', int(max(-32767, min(32767, v * scale * 32767))))
                    for v in buf)
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data)


# ------------------------------------------------------------------ impact
# A round that went in. The game plays this file 15% low on purpose, so it is
# built at the measured frequencies and allowed to land under them: the whole
# point of the impact and ricochet pair is that a gunner can tell them apart
# without looking, and the pitch offsets widen a difference that is already here.
def impact():
    n = frames(0.42)
    out = [0.0] * n

    # the strike itself: a few milliseconds of nothing but broadband energy
    strike = decay(bandpass(white(n, 101), 1800.0, 0.8), 0.006)
    # the plate, damped by everything standing behind it
    plate = add([0.0] * n,
                mode(n, 612, 0.55, 0.085),
                mode(n, 936, 0.32, 0.055),
                mode(n, 3183, 0.10, 0.018))
    # the hull taking the blow, which is the part that reads as "heavy"
    body = add([0.0] * n,
               mode(n, 95, 0.85, 0.20),
               mode(n, 141, 0.50, 0.150))
    # and the dull spread underneath it
    rumble = decay(lowpass(white(n, 102), 260.0), 0.10)

    add(out, strike, plate, body)
    return add(out, [v * 0.55 for v in rumble])


# ---------------------------------------------------------------- ricochet
# A round that did not. Same plate, longer ring, and then the thing that makes
# a ricochet recognisable: the flattened round tumbling away, whistling.
def ricochet():
    n = frames(0.95)
    out = [0.0] * n

    strike = decay(bandpass(white(n, 201), 3000.0, 0.7), 0.004)
    plate = add([0.0] * n,
                mode(n, 612, 0.30, 0.30),
                mode(n, 936, 0.42, 0.22),
                mode(n, 3183, 0.22, 0.090),
                mode(n, 1870, 0.16, 0.130))

    # the whine: an inharmonic chord, falling as the round slows and recedes,
    # beaten at the tumble rate. 35% off the velocity per deflection is what
    # sets how far it falls.
    tail = frames(0.90)
    whine = add([0.0] * tail,
                glide(tail, 633, 470, 0.40, 0.42, wobble_hz=52, wobble=0.35),
                glide(tail, 324, 240, 0.22, 0.38, wobble_hz=52, wobble=0.30),
                glide(tail, 910, 676, 0.16, 0.34, wobble_hz=71, wobble=0.40))

    add(out, strike, plate)
    return place(out, whine, int(RATE * 0.018))


# -------------------------------------------------------------------- load
# Ground crew putting a store on a rack, in the order it actually happens:
# the lugs seat in the hooks, the hooks close, then the sway brace arms are
# wound down and each notch of the ratchet clicks.
def load():
    n = frames(1.00)
    out = [0.0] * n

    seat = frames(0.30)
    place(out, add([0.0] * seat,
                   [v * 0.7 for v in decay(lowpass(white(seat, 301), 900.0), 0.030)],
                   mode(seat, 128, 0.80, 0.110),
                   mode(seat, 243, 0.45, 0.070),
                   mode(seat, 511, 0.20, 0.040)), 0)

    latch = frames(0.20)
    place(out, add([0.0] * latch,
                   decay(bandpass(white(latch, 302), 1400.0, 1.1), 0.018),
                   mode(latch, 905, 0.30, 0.035)), int(RATE * 0.185), 0.75)

    # four notches, closing up as the arm is wound down against the store
    for k, (at, level) in enumerate(((0.40, 1.00), (0.52, 0.86), (0.62, 0.74), (0.70, 0.62))):
        tick = frames(0.09)
        place(out, add([0.0] * tick,
                       decay(bandpass(white(tick, 310 + k), 2200.0, 1.6), 0.010),
                       mode(tick, 1630, 0.25, 0.012)),
              int(RATE * at), 1.05 * level)

    return out


# -------------------------------------------------------------------- gear
# The only loop here, so the rules from the engine tool apply in full: whole
# hertz, a whole number of seconds, nothing random. Two seconds, because the
# gear takes about that long and the file is looped for as long as it travels.
def gear():
    seconds = 2.0
    # the pump. Nine pistons at 1500 rpm is 225 Hz; the second line two hertz
    # away gives the beat a real pump has, and two hertz is four whole beats
    # inside the buffer, so it loops with everything else.
    pump = [(225, 0.30, False), (227, 0.07, False),
            (450, 0.14, True), (675, 0.07, True),
            (900, 0.045, True), (1125, 0.025, True)]

    return add([0.0] * int(RATE * seconds),
               partials(seconds, pump, 401),
               # the structure moving: low, broad, unhurried
               partials(seconds, band(34, 170, 0.020, -0.5, 402, count=110), 402),
               # fluid through the actuator
               partials(seconds, band(700, 5000, 0.0055, -0.95, 403, count=200), 403),
               # and the mid the bay itself adds
               partials(seconds, band(180, 640, 0.008, -0.7, 404, count=120), 404))


# ------------------------------------------------------------- seeker lost
# Two tones falling. Short, under 2 kHz, and placed below both of the seeker
# tones that already exist so that the instrument keeps saying three things.
def seeker_lost():
    n = frames(0.40)
    out = [0.0] * n

    for at, f in ((0.00, 660), (0.155, 415)):
        step = frames(0.145)
        tone = add([0.0] * step,
                   mode(step, f, 0.80, 0.075),
                   mode(step, f * 2, 0.20, 0.055),
                   mode(step, f * 3, 0.07, 0.040))
        # a slower ramp on than a struck thing: this is an instrument, not metal
        rise = int(RATE * 0.004)
        tone = [v * min(1.0, i / rise) for i, v in enumerate(tone)]
        place(out, tone, int(RATE * at))

    return out


# -------------------------------------------------------------------- kill
# Two tones rising, over one low note of struck plate. Deliberately the mirror
# of seeker_lost: the crew reads a falling pair as something slipping away, so
# the pair that says a target is finished has to go the other way.
def kill():
    n = frames(0.55)
    out = [0.0] * n

    # the round arriving. The lowest of the measured plate modes, on its own and
    # short, so it reads as a thud under the tones rather than a second impact.
    thud = frames(0.12)
    place(out, add([0.0] * thud,
                   decay(lowpass(white(thud, 910), 900.0), 0.030),
                   mode(thud, 612, 0.55, 0.045),
                   mode(thud, 936, 0.16, 0.028)),
          0, 0.55)

    for at, f in ((0.06, 880), (0.215, 1320)):
        step = frames(0.145)
        tone = add([0.0] * step,
                   mode(step, f, 0.80, 0.075),
                   mode(step, f * 2, 0.18, 0.050),
                   mode(step, f * 3, 0.06, 0.035))
        # the same slow ramp the lost tone gets: an instrument, not metal
        rise = int(RATE * 0.004)
        tone = [v * min(1.0, i / rise) for i, v in enumerate(tone)]
        place(out, tone, int(RATE * at))

    return out


# ------------------------------------------------------------------- siren
# The second loop in this file, and it follows the gear's rules: integer hertz
# over whole seconds, no random generator anywhere near it. A buffer of noise
# does not join itself.
def siren():
    seconds = 2.0
    # 500 Hz because the whole band it has to cover is the shift applied to it:
    # 0.7 puts it at 350 and 1.8 at 900, which is where the recordings of the
    # real thing sit. Building it at the bottom of that range instead would
    # make the top of the dive a stretched, dull note.
    note = 500

    # The chopped air. Every harmonic to the top of what the shift can reach
    # without help -- 1.8 x 6000 is still well inside the band.
    parts = [(note * n, 0.34 / n, n > 1) for n in range(1, 13)]
    # The other row of slots, two hertz off. No siren has two matched rows, and
    # the beat that mismatch makes is most of why the note sounds mechanical
    # rather than electronic. Two hertz is four whole beats inside the buffer.
    parts += [(note + 2, 0.12, True), (note * 2 + 4, 0.05, True)]

    return add([0.0] * int(RATE * seconds),
               partials(seconds, parts, 501),
               # air through the housing
               partials(seconds, band(320, 6000, 0.0045, -0.85, 502, count=260), 502),
               # the propeller driving it, and the leg it is bolted to
               partials(seconds, band(52, 300, 0.016, -0.6, 503, count=120), 503))


# ------------------------------------------------------------------ turret
# A powered mount going round. The third loop, so the same rules: whole hertz
# over whole seconds, nothing random. What is heard is the drive, not the
# turret -- a motor and a gear train, with the mass of the thing underneath.
def turret():
    seconds = 2.0
    shaft = 50
    # 50 Hz is the motor shaft at 3000 rpm. Everything with a note comes off it:
    # 24 armature slots make 1200, the 12-tooth first pinion meshes at 600. The
    # second line two hertz away beats four whole times inside the buffer, the
    # same trick the pump and the siren use to sound like a machine.
    drive = [(600, 0.30, False), (602, 0.09, True),
             (1200, 0.22, False), (2400, 0.07, True), (3600, 0.025, True),
             (1800, 0.05, True),
             # the shaft itself: the growl the whine sits on
             (50, 0.10, False), (100, 0.13, False), (150, 0.08, False),
             (200, 0.05, False), (250, 0.03, False)]

    # Every line off the shaft is a harmonic of it, so they meet once a
    # revolution and sample zero is where they meet. Cut half a revolution
    # later, for the reason in rotate().
    return add([0.0] * int(RATE * seconds),
               rotate(partials(seconds, drive, 601), RATE // (2 * shaft)),
               # the ring. Sixty tonnes on a bearing has no note, only weight
               partials(seconds, band(34, 190, 0.024, -0.5, 602, count=120), 602),
               # the basket and the floor of it moving with the turret
               partials(seconds, band(200, 820, 0.009, -0.7, 603, count=140), 603),
               # teeth and bearings: the hiss on top, kept thin
               partials(seconds, band(900, 6000, 0.0032, -0.9, 604, count=220), 604))


# ------------------------------------------------------------------- crank
# The same job done by hand. No motor, so no whine and nothing steady: the
# whole sound is teeth meeting, one at a time. Built as a pulse train -- every
# harmonic of the tooth rate, in phase -- because that is what a train of
# clicks is, and shaped by weighting those harmonics rather than by filtering.
def crank():
    seconds = 2.0
    # An 18-tooth pinion on the handwheel shaft, one turn a second: 18 clicks a
    # second. Low enough to be heard as a rattle rather than as a note, which
    # is the difference between a gun being laid and a turret being driven.
    tooth = 18
    parts = []
    harmonic = 1

    while tooth * harmonic <= 7000:
        f = tooth * harmonic
        # 1/n is the pulse train; the three resonances are the gun around it --
        # the carriage, the gearbox housing, and the ring of the trail legs.
        shape = (0.60 * resonance(f, 420, 2.5)
                 + 1.00 * resonance(f, 1150, 4.0)
                 + 0.45 * resonance(f, 2700, 6.0))
        parts.append((f, 0.9 / harmonic * shape, False))
        harmonic += 1

    # Every harmonic in phase puts a click on sample zero, which is the one
    # place a click must not be. Half a tooth later is the quietest instant the
    # waveform has, so cut there.
    clicks = rotate(partials(seconds, parts, 701), RATE // (2 * tooth))
    # The hand. One turn a second is two whole turns inside the buffer, and a
    # wheel turned by an arm is not turned evenly -- the swell is the arm.
    clicks = modulate(clicks, 1, 0.30)

    return add([0.0] * int(RATE * seconds),
               clicks,
               # the screw dragging the mount round under the weight of the gun
               partials(seconds, band(60, 400, 0.013, -0.6, 702, count=130), 702),
               # grease and steel sliding, thin and high
               partials(seconds, band(1200, 6500, 0.0022, -0.9, 703, count=180), 703))


for name, buf in (('impact', impact()), ('ricochet', ricochet()), ('load', load()),
                  ('gear', gear()), ('seekerlost', seeker_lost()), ('kill', kill()),
                  ('divesiren', siren()), ('turretsound', turret()), ('cranksound', crank())):
    path = os.path.join(OUT, name + '.wav')
    write(path, buf)
    print('%-11s %6d samples  %.3f s  (256 blocks: %s)'
          % (name, len(buf), len(buf) / RATE, len(buf) % 256 == 0))
