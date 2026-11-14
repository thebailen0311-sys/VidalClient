"""Synthesizes Vanta's UI sounds as OGG Vorbis into src/main/resources/assets/refract/sounds/ui/.

Chill set: soft kalimba/marimba plucks and airy chords, warm low-passed, with a gentle room tail.
Nothing harsh, nothing loud; hovers and clicks are barely-there ticks.

Run: python tools/gen_sounds.py   (needs numpy + soundfile)
"""
import os
import numpy as np
import soundfile as sf

SR = 44100
OUT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'refract', 'sounds', 'ui')


def env(n, attack, decay):
    t = np.arange(n) / SR
    a = np.clip(t / attack, 0, 1) ** 2  # soft, rounded attack
    return a * np.exp(-t / decay)


def pluck(freq, dur, decay, attack=0.006, partials=((1, 1.0), (2, 0.18), (3, 0.05), (4.2, 0.03))):
    """Kalimba-ish: mostly fundamental, a touch of overtones that die away faster."""
    n = int(SR * dur)
    t = np.arange(n) / SR
    sig = np.zeros(n)
    for h, a in partials:
        sig += a * np.sin(2 * np.pi * freq * h * t) * np.exp(-t * (h - 1) * 9)
    return sig * env(n, attack, decay)


def pad(freqs, dur, attack=0.05, decay=0.35, detune=0.003):
    """Airy chord: detuned sines with a slow swell."""
    n = int(SR * dur)
    t = np.arange(n) / SR
    sig = np.zeros(n)
    for f in freqs:
        for d in (-detune, detune):
            sig += np.sin(2 * np.pi * f * (1 + d) * t)
    return sig / (2 * len(freqs)) * env(n, attack, decay)


def mix(*parts):
    n = max(int(off * SR) + len(p) for off, p in parts)
    out = np.zeros(n)
    for off, p in parts:
        o = int(off * SR)
        out[o:o + len(p)] += p
    return out


def lowpass(sig, cutoff):
    k = np.exp(-2 * np.pi * cutoff / SR)
    out = np.empty_like(sig)
    acc = 0.0
    for i, v in enumerate(sig):
        acc = (1 - k) * v + k * acc
        out[i] = acc
    return out


def reverb(sig, length=1.1, wet=0.4):
    n = int(SR * length)
    ir = np.random.default_rng(3).standard_normal(n) * np.exp(-np.arange(n) / (SR * length / 5))
    ir = lowpass(ir, 3500)
    ir /= np.sum(np.abs(ir)) + 1e-9
    dry = np.concatenate([sig, np.zeros(n)])
    tail = np.convolve(sig, ir)
    tail = np.pad(tail, (0, max(0, len(dry) - len(tail))))[:len(dry)]
    return dry * (1 - wet) + tail * wet * 7


def save(name, sig, gain, cutoff=2300, room=0.42):
    gain *= 0.85
    sig = lowpass(lowpass(sig, cutoff), cutoff * 1.4)
    sig = reverb(sig, wet=room)
    sig = lowpass(sig, cutoff * 1.6)
    sig = sig / (np.max(np.abs(sig)) + 1e-9) * gain
    fade = int(SR * 0.03)
    sig[-fade:] *= np.linspace(1, 0, fade)
    sf.write(os.path.join(OUT, name + '.ogg'), sig.astype(np.float32), SR, format='OGG', subtype='VORBIS')


os.makedirs(OUT, exist_ok=True)
# A fourth lower than before: rounder, calmer.
E5, G5, A5, B5, Cs6, D6, E6 = [f * 0.75 for f in (659.3, 784.0, 880.0, 987.8, 1108.7, 1174.7, 1318.5)]
A4, Cs5, C4, E4 = [f * 0.75 for f in (440.0, 554.4, 261.6, 329.6)]

save('hover', pluck(1760, 0.05, 0.008, attack=0.003, partials=((1, 1.0),)), 0.05, cutoff=2600, room=0.1)
save('click', pluck(1175, 0.08, 0.018, attack=0.002, partials=((1, 1.0), (2, 0.1))), 0.16, cutoff=2400, room=0.15)
save('toggle_on', mix((0, pluck(E5, 0.4, 0.13)), (0.07, pluck(B5, 0.5, 0.18))), 0.26)
save('toggle_off', mix((0, pluck(B5, 0.35, 0.11)), (0.07, pluck(E5, 0.45, 0.15) * 0.8)), 0.21)
save('open', mix((0, pad((A4, Cs5, E5), 0.9, attack=0.06, decay=0.3)), (0.08, pluck(E6, 0.6, 0.2) * 0.35)), 0.24, cutoff=2800, room=0.4)
save('close', pad((E5, Cs5, A4), 0.5, attack=0.02, decay=0.14), 0.18, cutoff=2400, room=0.3)
save('focus', pluck(E6, 0.7, 0.22, attack=0.01, partials=((1, 1.0), (2.76, 0.12))), 0.13, cutoff=3600, room=0.45)
save('notify', mix((0, pluck(G5, 0.45, 0.14)), (0.09, pluck(D6, 0.6, 0.2))), 0.25, room=0.4)
save('alert', mix((0, pluck(A5, 0.5, 0.15)), (0.09, pluck(Cs6, 0.5, 0.16)), (0.18, pluck(E6, 0.8, 0.26))), 0.3, room=0.45)
save('warn', mix((0, pluck(E4, 0.6, 0.2, partials=((1, 1.0), (2, 0.3), (3, 0.1)))), (0.12, pluck(C4, 0.8, 0.26, partials=((1, 1.0), (2, 0.3), (3, 0.1))))), 0.32, cutoff=2200, room=0.35)
print('wrote', len(os.listdir(OUT)), 'sounds to', os.path.abspath(OUT))
