"""音声警報（VoiceWarnings）の仮の声を Windows の音声合成で作って ogg にする。

本物の録音ができるまでの代役。パックが同じイベント名（ashvehicles:voice.<name>）で録音を差し込めば
そちらが勝つ。ここで作るのは一発物なので、ループの継ぎ目の問題（長さを 256 の倍数に）は無い。

    python tool/make_voice_warnings.py
"""
import os
import subprocess
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "ashvehicles", "sounds")

# ファイル名 -> 読み上げる文
LINES = {
    "voicepullup": "Pull up. Pull up.",
    "voicestall": "Stall. Stall.",
    "voicebingo": "Bingo. Bingo.",
    "voicefuelout": "Fuel out.",
    "voicemissile": "Missile. Missile.",
    "voiceoverg": "Over G. Over G.",
    "voicegear": "Too low. Gear.",
    "voicealtitude": "Altitude. Altitude.",
    "voicelock": "Warning. Radar lock.",
    "voiceengine": "Warning. Engine.",
    "voiceenginefire": "Engine fire. Engine fire.",
    "voiceengineout": "Engine out.",
    "voicebankangle": "Bank angle. Bank angle.",
}


def speak(text, wav):
    script = (
        "Add-Type -AssemblyName System.Speech;"
        "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer;"
        "$v = $s.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -like 'en-*' -and $_.VoiceInfo.Gender -eq 'Female' } | Select-Object -First 1;"
        "if ($v) { $s.SelectVoice($v.VoiceInfo.Name) };"
        "$s.Rate = 1;"
        f"$s.SetOutputToWaveFile('{wav}');"
        f"$s.Speak('{text}');"
        "$s.Dispose()"
    )
    subprocess.run(["powershell", "-NoProfile", "-Command", script], check=True)


def main():
    with tempfile.TemporaryDirectory() as tmp:
        for name, text in LINES.items():
            wav = os.path.join(tmp, name + ".wav")
            speak(text, wav)
            # 無線越しの声に寄せる: 帯域を絞って軽く歪ませる。モノラル（定位させないので）。
            subprocess.run([
                "ffmpeg", "-y", "-loglevel", "error", "-i", wav,
                "-af", "highpass=f=350,lowpass=f=3400,acompressor=threshold=-18dB:ratio=4,"
                       "volume=2.0,alimiter=limit=0.9",
                "-ac", "1", "-ar", "44100", "-c:a", "libvorbis", "-q:a", "5",
                os.path.join(OUT, name + ".ogg"),
            ], check=True)
            print("wrote", name)


if __name__ == "__main__":
    main()
