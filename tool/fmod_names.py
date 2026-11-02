"""Give the sounds of an FMOD Studio .bank the names of the events that play them.

The FSB inside a bank carries no names. What carries them:

- <name>.strings.bank (STDT chunk): a packed radix tree from GUID to path,
  e.g. "event:/tanks/explosions/heavy_explosion". Layout after
  FModBankParser (github.com/Masusder/FModBankParser).
- the bank's own metadata: each "WAV " chunk is a waveform resource
  (GUID, sound bank index, subsound index), and events reach those through
  timelines, multi/scatterer instruments and waveform instruments.

Rather than parse every node type, each object is read as "its GUID and the
known GUIDs that appear in its bytes", and events are followed through that
graph down to the waveforms. Sounds no event reaches keep only their number.
"""

import struct
from collections import defaultdict
from pathlib import Path

SENTINEL24 = 0xFFFFFF


# ---------- RIFF

def chunks(data: bytes, start: int, end: int):
    """Yield (id, form, body_start, body_end) for each chunk in [start, end)."""
    pos = start
    while pos + 8 <= end:
        cid = data[pos:pos + 4]
        size = struct.unpack_from("<I", data, pos + 4)[0]
        body = pos + 8
        if cid in (b"RIFF", b"LIST"):
            yield cid, data[body:body + 4], body + 4, body + size
        else:
            yield cid, None, body, body + size
        pos = body + size + (size & 1)


def find_chunks(data: bytes, want: bytes):
    """Every leaf chunk with this id, anywhere in the file."""
    def walk(start, end):
        for cid, form, b, e in chunks(data, start, end):
            if form is not None:
                yield from walk(b, e)
            elif cid == want:
                yield b, e
    yield from walk(0, len(data))


def item_lists(data: bytes):
    """Every LIST whose first child is a GUID-led chunk: one object each."""
    def walk(start, end):
        for cid, form, b, e in chunks(data, start, end):
            if form is None:
                continue
            first = next(chunks(data, b, e), None)
            if first and first[1] is None and first[3] - first[2] >= 16 and form != b"PROJ":
                yield form, data[first[2]:first[2] + 16], data[b:e]
            yield from walk(b, e)
    yield from walk(0, len(data))


# ---------- strings bank

class Reader:
    def __init__(self, data: bytes, pos: int):
        self.data, self.pos = data, pos

    def u16(self):
        v = struct.unpack_from("<H", self.data, self.pos)[0]
        self.pos += 2
        return v

    def u32(self):
        v = struct.unpack_from("<I", self.data, self.pos)[0]
        self.pos += 4
        return v

    def x16(self):
        low = self.u16()
        if low & 0x8000:
            return (low & 0x7FFF) | (self.u16() << 15)
        return low

    def take(self, n):
        b = self.data[self.pos:self.pos + n]
        self.pos += n
        return b

    def elem_list(self, size):
        count = self.x16() >> 1
        if count <= 0:
            return []
        self.u16()  # payload size
        return [self.take(size) for _ in range(count)]

    def array24(self):
        count = self.x16()
        raw = self.take(count * 3)
        return [raw[i] | raw[i + 1] << 8 | raw[i + 2] << 16 for i in range(0, len(raw), 3)]


def read_strings(path: Path) -> dict[bytes, str]:
    """GUID (16 raw bytes) -> path, from a .strings.bank."""
    data = path.read_bytes()
    names: dict[bytes, str] = {}
    for start, end in find_chunks(data, b"STDT"):
        r = Reader(data, start)
        kind = r.u32()
        if kind != 1:  # only the 24-bit radix tree is known
            continue
        nodes = [struct.unpack("<II", n) for n in r.elem_list(8)]
        guids = r.elem_list(16)
        blob = r.take(r.x16())
        leaves = r.array24()
        parents = r.array24()
        if len(leaves) != len(guids) or len(parents) != len(nodes):
            continue

        def segment(offset):
            stop = blob.find(b"\0", offset)
            return blob[offset:stop if stop >= 0 else len(blob)].decode("utf-8", "replace")

        for guid, node in zip(guids, leaves):
            parts = []
            guard = 0
            while node != SENTINEL24 and node < len(nodes) and guard < 4096:
                offset = nodes[node][0] & 0xFFFFFF
                if offset != SENTINEL24:
                    parts.append(segment(offset))
                node = parents[node]
                guard += 1
            names[guid] = "".join(reversed(parts))
    return names


def find_strings_bank(bank: Path) -> Path | None:
    for candidate in sorted(bank.parent.glob("*.strings.bank")):
        return candidate
    return None


# ---------- sound bank

def sound_names(bank: Path, strings: dict[bytes, str]) -> dict[int, list[str]]:
    """vgmstream stream number (1-based) -> event paths that play it."""
    data = bank.read_bytes()

    # Waveform resources, in the order vgmstream numbers the streams:
    # by sound bank, then by subsound.
    waves = []
    for start, end in find_chunks(data, b"WAV "):
        if end - start >= 26:
            guid = data[start:start + 16]
            bank_index, sub_index = struct.unpack_from("<ii", data, start + 18)
            waves.append((bank_index, sub_index, guid))
    waves.sort()
    stream_of = {guid: n for n, (_, _, guid) in enumerate(waves, 1)}

    objects = list(item_lists(data))
    known = {guid for _, guid, _ in objects} | set(stream_of)
    edges: dict[bytes, set[bytes]] = defaultdict(set)
    for _, guid, body in objects:
        for i in range(16, len(body) - 15):
            ref = body[i:i + 16]
            if ref in known and ref != guid:
                edges[guid].add(ref)

    found: dict[int, list[str]] = defaultdict(list)
    for form, guid, _ in objects:
        if form != b"EVNT":
            continue
        name = strings.get(guid)
        if not name:
            continue
        seen, stack = {guid}, [guid]
        while stack:
            for ref in edges[stack.pop()]:
                if ref in seen:
                    continue
                seen.add(ref)
                if ref in stream_of:
                    found[stream_of[ref]].append(name)
                else:
                    stack.append(ref)
    return {n: sorted(set(v)) for n, v in found.items()}


def file_stem(name: str) -> str:
    """event:/tanks/explosions/heavy -> tanks_explosions_heavy, safe for a file name."""
    if ":/" in name:
        name = name.split(":/", 1)[1]
    name = name.replace("/", "_")
    return "".join(c if c.isalnum() or c in "-_." else "_" for c in name).strip("_") or "unnamed"


def label(events: list[str]) -> str:
    """One short name for a sound several events share: what their paths have in common."""
    stems = [file_stem(e) for e in events]
    if len(stems) == 1:
        return stems[0]
    common = stems[0]
    for s in stems[1:]:
        while not s.startswith(common):
            common = common[:-1]
    common = common.rstrip("_")
    # Cut back to a whole word, so "art_expl" does not come out of "art_explosion"/"art_explode".
    if not all(len(s) == len(common) or s[len(common)] == "_" for s in stems):
        common = common.rsplit("_", 1)[0] if "_" in common else common
    # Shared by events that agree only on their folder: the folder alone says
    # nothing, so name it after the first event and count the rest.
    folders = {e.rsplit("/", 1)[0] for e in events}
    if len(folders) == 1 and common == file_stem(next(iter(folders))):
        return f"{stems[0]}_and{len(stems) - 1}"
    return common or f"{stems[0]}_and{len(stems) - 1}"


def bank_labels(bank: Path) -> tuple[dict[int, str], dict[int, list[str]], Path | None]:
    """Stream number -> file-name label and -> event paths. Empty without a strings bank."""
    strings_path = find_strings_bank(bank)
    if strings_path is None:
        return {}, {}, None
    events = sound_names(bank, read_strings(strings_path))
    return {n: label(e) for n, e in events.items()}, events, strings_path


if __name__ == "__main__":
    import sys
    bank = Path(sys.argv[1])
    strings_path = find_strings_bank(bank)
    strings = read_strings(strings_path) if strings_path else {}
    print(f"strings: {strings_path} ({len(strings)} names)")
    names = sound_names(bank, strings)
    for n in sorted(names)[:40]:
        print(n, names[n])
    print(f"{len(names)} sounds named")
