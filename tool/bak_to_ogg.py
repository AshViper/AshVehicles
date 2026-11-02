"""Convert .bak files to .ogg.

A .bak is not one format, so each file is looked at by content, not by name:

1. If it already holds an Ogg stream (pages starting "OggS"), the stream is
   cut out as is. No re-encoding, so nothing is lost.
2. Otherwise ffmpeg is asked to read it (WAV, MP3, FLAC, ... renamed to .bak)
   and it is encoded to Ogg Vorbis.
3. If ffmpeg cannot read it either, the file is reported and skipped.

FMOD banks (.bank / .fsb) hold many sounds in FMOD's own Vorbis, which has no
Ogg pages and which ffmpeg cannot read. Those go through vgmstream-cli (from
PATH or %LOCALAPPDATA%\\vgmstream), one .ogg per sound, into a folder named
after the bank.

Usage:
    python tool/bak_to_ogg.py <file-or-folder> [...] [-o OUT] [-q 6] [-r]

    -o OUT   folder to write into (default: next to each input)
    -q N     Vorbis quality for re-encoded files, 0-10 (default 6)
    -r       search folders recursively
    -f       overwrite existing .ogg files
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from fmod_names import bank_labels  # noqa: E402

OGG_MAGIC = b"OggS"
# Without this every ffmpeg/vgmstream call flashes a console under pythonw.
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def carve_ogg(data: bytes) -> list[bytes]:
    """Return every Ogg logical stream found in data, cut on page boundaries."""
    streams: list[bytes] = []
    pos = data.find(OGG_MAGIC)
    current = bytearray()
    while pos != -1 and pos + 27 <= len(data):
        if data[pos + 4] != 0:  # stream structure version must be 0
            pos = data.find(OGG_MAGIC, pos + 1)
            continue
        header_type = data[pos + 5]
        segments = data[pos + 26]
        table_end = pos + 27 + segments
        if table_end > len(data):
            break
        body = sum(data[pos + 27:table_end])
        end = table_end + body
        if end > len(data):
            break
        if header_type & 0x02 and current:  # beginning of a new stream
            streams.append(bytes(current))
            current = bytearray()
        current += data[pos:end]
        if header_type & 0x04:  # end of stream
            streams.append(bytes(current))
            current = bytearray()
        pos = data.find(OGG_MAGIC, end)
    if current:
        streams.append(bytes(current))
    return streams


def encode_with_ffmpeg(src: Path, dst: Path, quality: int) -> tuple[bool, str]:
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        return False, "ffmpeg not found on PATH"
    result = subprocess.run(
        [ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", str(src),
         "-vn", "-c:a", "libvorbis", "-q:a", str(quality), str(dst)],
        capture_output=True, text=True, creationflags=NO_WINDOW)
    if result.returncode != 0:
        dst.unlink(missing_ok=True)
        message = result.stderr.strip().splitlines()
        return False, message[-1] if message else "ffmpeg failed"
    return True, ""


BANK_SUFFIXES = {".bank", ".fsb"}
# Written beside a bank's sounds: stream number, file, every event that plays it.
INDEX_NAME = "_events.tsv"


SETTINGS = Path.home() / ".bak_to_ogg.json"


def vgmstream_candidates() -> list[Path]:
    """Where vgmstream-cli is looked for, in order. The saved choice comes first.

    %LOCALAPPDATA% alone is not enough: the Store build of Python redirects
    AppData, so the home-relative path is tried as well.
    """
    places = []
    try:
        saved = json.loads(SETTINGS.read_text(encoding="utf-8")).get("vgmstream")
        if saved:
            places.append(Path(saved))
    except (OSError, ValueError):
        pass
    on_path = shutil.which("vgmstream-cli")
    if on_path:
        places.append(Path(on_path))
    if os.environ.get("LOCALAPPDATA"):
        places.append(Path(os.environ["LOCALAPPDATA"]) / "vgmstream" / "vgmstream-cli.exe")
    places.append(Path.home() / "AppData" / "Local" / "vgmstream" / "vgmstream-cli.exe")
    places.append(Path(__file__).parent / "vgmstream" / "vgmstream-cli.exe")
    return places


def find_vgmstream() -> str | None:
    for place in vgmstream_candidates():
        if place.is_file():
            return str(place)
    return None


def remember_vgmstream(exe: str) -> None:
    try:
        settings = json.loads(SETTINGS.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        settings = {}
    settings["vgmstream"] = exe
    SETTINGS.write_text(json.dumps(settings, ensure_ascii=False), encoding="utf-8")


def convert_bank(src: Path, out_dir: Path | None, quality: int, force: bool) -> str:
    vgm = find_vgmstream()
    if vgm is None:
        looked = ", ".join(str(p) for p in vgmstream_candidates())
        return f"FAIL   {src}: vgmstream-cli not found (looked in {looked})"
    target_dir = (out_dir or src.parent) / src.stem
    target_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        # ?s is the stream index. ?n would be its name, but banks often carry
        # none and vgmstream then puts the whole input path there.
        result = subprocess.run(
            [vgm, "-S", "0", "-o", str(Path(tmp) / "?s.wav"), str(src)],
            capture_output=True, text=True, creationflags=NO_WINDOW)
        wavs = sorted(Path(tmp).glob("*.wav"), key=lambda w: int(w.stem))
        if not wavs:
            message = (result.stderr or result.stdout).strip().splitlines()
            return f"FAIL   {src}: {message[-1] if message else 'vgmstream failed'}"
        try:
            labels, events, strings_path = bank_labels(src)
        except Exception:  # an unreadable bank only loses the names
            labels, events, strings_path = {}, {}, None
        jobs = []
        index_rows = []
        for wav in wavs:
            n = int(wav.stem)
            dst = target_dir / f"{labels.get(n, src.stem)}_{n:03d}.ogg"
            index_rows.append(f"{n}\t{dst.name}\t{' '.join(events.get(n, []))}")
            if force or not dst.exists():
                jobs.append((wav, dst))
        (target_dir / INDEX_NAME).write_text(
            "stream\tfile\tevents\n" + "\n".join(index_rows) + "\n", encoding="utf-8")
        # One ffmpeg per sound is mostly process start-up; run them side by side.
        with ThreadPoolExecutor(max_workers=os.cpu_count() or 4) as pool:
            oks = list(pool.map(lambda j: encode_with_ffmpeg(j[0], j[1], quality)[0], jobs))
        done = sum(oks)
        failed = len(oks) - done
    status = "FAIL  " if failed else "bank  "
    names = (f", named from {strings_path.name}" if strings_path and labels
             else ", no .strings.bank beside it so unnamed")
    return f"{status} {src} -> {target_dir} ({done} encoded, {failed} failed, {len(wavs)} sounds{names})"


def convert(src: Path, out_dir: Path | None, quality: int, force: bool) -> str:
    if src.suffix.lower() in BANK_SUFFIXES:
        return convert_bank(src, out_dir, quality, force)
    target_dir = out_dir or src.parent
    target_dir.mkdir(parents=True, exist_ok=True)
    base = target_dir / src.stem
    first = base.with_suffix(".ogg")
    if first.exists() and not force:
        return f"skip   {src} ({first.name} exists, use -f)"

    streams = carve_ogg(src.read_bytes())
    if streams:
        names = []
        for i, stream in enumerate(streams):
            dst = first if i == 0 else target_dir / f"{src.stem}_{i}.ogg"
            dst.write_bytes(stream)
            names.append(dst.name)
        return f"copy   {src} -> {', '.join(names)}"

    ok, error = encode_with_ffmpeg(src, first, quality)
    if ok:
        return f"encode {src} -> {first.name}"
    return f"FAIL   {src}: {error}"


def collect(paths: list[str], recursive: bool) -> list[Path]:
    files: list[Path] = []
    for p in map(Path, paths):
        if p.is_dir():
            walk = p.rglob if recursive else p.glob
            files += sorted(f for f in walk("*")
                            if f.suffix.lower() in {".bak"} | BANK_SUFFIXES)
        elif p.is_file():
            files.append(p)
        else:
            print(f"missing {p}", file=sys.stderr)
    return files


def main() -> int:
    parser = argparse.ArgumentParser(description="Convert .bak files to .ogg")
    parser.add_argument("paths", nargs="+")
    parser.add_argument("-o", "--out", type=Path)
    parser.add_argument("-q", "--quality", type=int, default=6)
    parser.add_argument("-r", "--recursive", action="store_true")
    parser.add_argument("-f", "--force", action="store_true")
    args = parser.parse_args()

    files = collect(args.paths, args.recursive)
    if not files:
        print("no .bak files found")
        return 1
    failed = 0
    for f in files:
        line = convert(f, args.out, args.quality, args.force)
        failed += line.startswith("FAIL")
        print(line)
    print(f"{len(files) - failed}/{len(files)} converted")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
