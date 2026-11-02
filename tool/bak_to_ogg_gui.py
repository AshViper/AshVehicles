"""Window for bak_to_ogg.py: convert, listen, and save the sounds you want.

    python tool/bak_to_ogg_gui.py      (or double-click with pythonw)

Conversions go into a work folder first. The result list plays a sound on
double-click (ffplay) and saves the selected ones (Ctrl/Shift for several)
to a folder of your choice.
"""

import os
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

sys.path.insert(0, str(Path(__file__).parent))
from bak_to_ogg import (BANK_SUFFIXES, INDEX_NAME, collect, convert,  # noqa: E402
                        find_vgmstream, remember_vgmstream)

PATTERNS = " ".join(f"*{s}" for s in sorted({".bak"} | BANK_SUFFIXES))
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("bak / bank → ogg")
        self.geometry("820x620")
        self.minsize(640, 460)
        self.inputs: list[str] = []
        self.results: list[Path] = []
        self.work = Path(tempfile.mkdtemp(prefix="bak_to_ogg_"))
        self.messages: queue.Queue = queue.Queue()
        self.worker: threading.Thread | None = None
        self.player: subprocess.Popen | None = None
        self.ffplay = shutil.which("ffplay")
        self.protocol("WM_DELETE_WINDOW", self.close)

        pad = {"padx": 8, "pady": 4}

        # --- inputs
        src = ttk.LabelFrame(self, text="入力")
        src.pack(fill="x", **pad)
        row = ttk.Frame(src)
        row.pack(fill="x", padx=4, pady=4)
        ttk.Button(row, text="ファイルを追加…", command=self.add_files).pack(side="left")
        ttk.Button(row, text="フォルダを追加…", command=self.add_folder).pack(side="left", padx=4)
        ttk.Button(row, text="クリア", command=self.clear_inputs).pack(side="left")
        self.recursive = tk.BooleanVar(value=True)
        ttk.Checkbutton(row, text="サブフォルダも", variable=self.recursive).pack(side="left", padx=8)
        ttk.Label(row, text="品質").pack(side="left")
        self.quality = tk.IntVar(value=6)
        ttk.Spinbox(row, from_=0, to=10, width=4, textvariable=self.quality).pack(side="left", padx=4)
        self.run_button = ttk.Button(row, text="変換", command=self.start)
        self.run_button.pack(side="right")
        ttk.Button(row, text="vgmstream の場所…", command=self.pick_vgmstream).pack(side="right", padx=4)
        self.input_list = tk.Listbox(src, height=4)
        self.input_list.pack(fill="x", padx=4, pady=(0, 4))
        self.progress = ttk.Progressbar(src, mode="determinate")
        self.progress.pack(fill="x", padx=4, pady=(0, 4))

        # --- results
        res = ttk.LabelFrame(self, text="変換結果（ダブルクリックで再生・Ctrl/Shift で複数選択）")
        res.pack(fill="both", expand=True, **pad)
        bar = ttk.Frame(res)
        bar.pack(fill="x", padx=4, pady=4)
        ttk.Button(bar, text="▶ 再生", command=self.play).pack(side="left")
        ttk.Button(bar, text="■ 停止", command=self.stop).pack(side="left", padx=4)
        ttk.Button(bar, text="すべて選択", command=self.select_all).pack(side="left", padx=(12, 0))
        ttk.Button(bar, text="選択解除", command=lambda: self.tree.selection_set(())).pack(side="left", padx=4)
        ttk.Button(bar, text="選択をダウンロード…", command=self.save_selected).pack(side="right")
        ttk.Button(bar, text="この1件を保存…", command=self.save_one).pack(side="right", padx=4)

        find = ttk.Frame(res)
        find.pack(fill="x", padx=4, pady=(0, 4))
        ttk.Label(find, text="絞り込み").pack(side="left")
        self.filter = tk.StringVar()
        self.filter.trace_add("write", lambda *_: self.refill())
        ttk.Entry(find, textvariable=self.filter).pack(side="left", fill="x", expand=True, padx=4)
        ttk.Label(find, text="（名前とイベントを空白区切りで AND 検索）").pack(side="left")

        frame = ttk.Frame(res)
        frame.pack(fill="both", expand=True, padx=4, pady=(0, 4))
        self.tree = ttk.Treeview(frame, columns=("name", "length", "size", "events"),
                                 show="headings", selectmode="extended")
        for col, title, width, anchor in (("name", "名前", 300, "w"),
                                          ("length", "長さ", 70, "e"),
                                          ("size", "サイズ", 70, "e"),
                                          ("events", "使うイベント", 360, "w")):
            self.tree.heading(col, text=title)
            self.tree.column(col, width=width, anchor=anchor, stretch=col in ("name", "events"))
        scroll = ttk.Scrollbar(frame, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=scroll.set)
        self.tree.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        self.tree.bind("<Double-1>", lambda e: self.play())
        self.tree.bind("<Return>", lambda e: self.play())
        self.tree.bind("<space>", lambda e: self.play())
        self.tree.bind("<Control-a>", lambda e: (self.select_all(), "break")[1])
        self.count = ttk.Label(res, text="")
        self.count.pack(anchor="w", padx=4)
        self.tree.bind("<<TreeviewSelect>>", lambda e: self.update_count())

        # --- log
        self.log = tk.Text(self, height=5, state="disabled", wrap="none")
        self.log.pack(fill="x", **pad)

        vgm = find_vgmstream()
        if vgm is None:
            self.write("vgmstream-cli が見つかりません。「vgmstream の場所…」で vgmstream-cli.exe を選んでください。")
        else:
            self.write(f"vgmstream: {vgm}")
        if self.ffplay is None:
            self.write("ffplay が見つかりません。再生はできません。")
        self.after(100, self.poll)

    # ---------- inputs
    def add_files(self):
        for f in filedialog.askopenfilenames(
                filetypes=[("音声バンク", PATTERNS), ("すべて", "*.*")]):
            self.add(f)

    def add_folder(self):
        folder = filedialog.askdirectory()
        if folder:
            self.add(folder)

    def add(self, path: str):
        if path not in self.inputs:
            self.inputs.append(path)
            self.input_list.insert("end", path)

    def clear_inputs(self):
        self.inputs.clear()
        self.input_list.delete(0, "end")

    def pick_vgmstream(self):
        exe = filedialog.askopenfilename(title="vgmstream-cli.exe を選択",
                                         filetypes=[("vgmstream-cli", "vgmstream-cli.exe"),
                                                    ("実行ファイル", "*.exe")])
        if exe:
            remember_vgmstream(exe)
            self.write(f"vgmstream: {exe}（次回から自動で使います）")

    def write(self, line: str):
        self.log.configure(state="normal")
        self.log.insert("end", line + "\n")
        self.log.see("end")
        self.log.configure(state="disabled")

    # ---------- conversion
    def start(self):
        if self.worker and self.worker.is_alive():
            return
        files = collect(self.inputs, self.recursive.get())
        if not files:
            messagebox.showinfo("変換", "変換する .bak / .bank / .fsb がありません。")
            return
        self.progress.configure(maximum=len(files), value=0)
        self.run_button.configure(state="disabled")
        self.worker = threading.Thread(target=self.run, daemon=True,
                                       args=(files, self.quality.get()))
        self.worker.start()

    def run(self, files, quality):
        failed = 0
        for f in files:
            self.messages.put(("log", f"… {f}"))
            try:
                line = convert(f, self.work, quality, True)
            except Exception as e:  # keep going with the rest
                line = f"FAIL   {f}: {e}"
            failed += line.startswith("FAIL")
            self.messages.put(("log", line))
            self.messages.put(("step", None))
        events: dict[Path, str] = {}
        for index in self.work.rglob(INDEX_NAME):
            for line in index.read_text(encoding="utf-8").splitlines()[1:]:
                parts = line.split("\t")
                if len(parts) == 3:
                    events[index.parent / parts[1]] = parts[2].replace("event:/", "")
        found = sorted(self.work.rglob("*.ogg"))
        rows = [(p, duration(p), events.get(p, "")) for p in found]
        self.messages.put(("done", (len(files), failed, rows)))

    def poll(self):
        while not self.messages.empty():
            kind, value = self.messages.get()
            if kind == "log":
                self.write(value)
            elif kind == "step":
                self.progress.step(1)
            elif kind == "done":
                total, failed, rows = value
                self.write(f"{total - failed}/{total} 変換しました")
                self.show_results(rows)
                self.run_button.configure(state="normal")
        self.after(100, self.poll)

    def show_results(self, rows):
        self.results = [p for p, _, _ in rows]
        self.rows = []
        for p, secs, ev in rows:
            length = f"{secs:.2f} s" if secs is not None else ""
            self.rows.append((str(p.relative_to(self.work)), length,
                              f"{p.stat().st_size / 1024:.0f} KB", ev))
        self.refill()

    def refill(self):
        """Show the rows that match every word in the filter box."""
        if not hasattr(self, "rows"):
            return
        words = self.filter.get().lower().split()
        self.tree.delete(*self.tree.get_children())
        for i, row in enumerate(self.rows):
            text = (row[0] + " " + row[3]).lower()
            if all(w in text for w in words):
                self.tree.insert("", "end", iid=str(i), values=row)
        self.update_count()

    # ---------- results
    def selected(self) -> list[Path]:
        return [self.results[int(i)] for i in self.tree.selection()]

    def update_count(self):
        shown = len(self.tree.get_children())
        self.count.configure(text=f"{len(self.results)} 件中 {shown} 件を表示・{len(self.tree.selection())} 件を選択")

    def select_all(self):
        self.tree.selection_set(self.tree.get_children())

    def play(self):
        focus = self.tree.focus() or next(iter(self.tree.selection()), None)
        if focus is None or self.ffplay is None:
            return
        self.stop()
        self.player = subprocess.Popen(
            [self.ffplay, "-nodisp", "-autoexit", "-loglevel", "quiet",
             str(self.results[int(focus)])], creationflags=NO_WINDOW)

    def stop(self):
        if self.player and self.player.poll() is None:
            self.player.terminate()
        self.player = None

    def save_one(self):
        focus = self.tree.focus() or next(iter(self.tree.selection()), None)
        if focus is None:
            messagebox.showinfo("保存", "保存する音を選んでください。")
            return
        src = self.results[int(focus)]
        dst = filedialog.asksaveasfilename(initialfile=src.name, defaultextension=".ogg",
                                           filetypes=[("Ogg Vorbis", "*.ogg")])
        if dst:
            shutil.copy2(src, dst)
            self.write(f"保存 {dst}")

    def save_selected(self):
        files = self.selected()
        if not files:
            messagebox.showinfo("ダウンロード", "保存する音を選んでください。")
            return
        folder = filedialog.askdirectory(title=f"{len(files)} 件の保存先")
        if not folder:
            return
        skipped = 0
        for src in files:
            dst = Path(folder) / src.name
            if dst.exists() and not messagebox.askyesno("上書き", f"{dst.name} は既にあります。上書きしますか？"):
                skipped += 1
                continue
            shutil.copy2(src, dst)
        self.write(f"{len(files) - skipped} 件を {folder} に保存しました")
        if os.name == "nt":
            os.startfile(folder)

    def close(self):
        self.stop()
        shutil.rmtree(self.work, ignore_errors=True)
        self.destroy()


def duration(path: Path) -> float | None:
    """Length of an Ogg Vorbis file from its headers, without starting ffprobe.

    The sample rate is in the identification header on the first page; the
    last page's granule position is the total sample count.
    """
    data = path.read_bytes()
    ident = data.find(b"\x01vorbis")
    last = data.rfind(b"OggS")
    if ident < 0 or last < 0 or last + 14 > len(data):
        return None
    rate = int.from_bytes(data[ident + 12:ident + 16], "little")
    samples = int.from_bytes(data[last + 6:last + 14], "little")
    return samples / rate if rate else None


if __name__ == "__main__":
    App().mainloop()
