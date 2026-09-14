# -*- coding: utf-8 -*-
"""戦闘 AI の判断ロジックを、ゲームを起動せずに確かめる。

    python tool/ai/check/run_checks.py

src/main/java を build/ai-check/classes へ javac で直にコンパイルし、tool/ai/check の合成データの試験
（方針・拠点の点数・脅威マップ・高さ地図の射線・版と既定値・統計・可視化パケット・特徴量）を回す。
Gradle を通さないので、開発クライアントが起動していても走る（記憶ノート gradle-blocked-while-game-runs）。
失敗が1つでもあれば終了コード 1。

要る物: JDK 21 と、一度 Gradle を通した作業ツリー。クラスパスは Gradle が解決した物をそのまま借りる——
build/moddev/artifacts/neoforge-*.jar（Minecraft と NeoForge）、build/moddev/clientLegacyClasspath.txt
（ModDevGradle が起動用に書く、ライブラリ1つにつき1版の一覧）、build.gradle の implementation / compileOnly が
名指しする MOD（GeckoLib・JEI の API・Distant Horizons）を Gradle のキャッシュから。

Gradle のキャッシュを丸ごと載せてはいけない。同じライブラリの古い版（DataFixerUpper 6 など）が同居していて、
先に載った方が勝つので、ゲームの Codec が NoSuchMethodError で落ちる。
"""
import glob
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
OUT = os.path.join(REPO, "build", "ai-check")
CACHE = os.path.join(os.path.expanduser("~"), ".gradle", "caches", "modules-2", "files-2.1")
MAIN = "com.ashvehicles.ai.perception.AiLogicCheck"
DEPENDENCY = re.compile(r"""^\s*(?:implementation|compileOnly|api)\s*\(?\s*["']([^"':]+:[^"':]+:[^"']+)["']""")


def slash(path):
    """argfile の引用符の中ではバックスラッシュがエスケープになるので、前向きの斜線にする。"""
    return path.replace("\\", "/")


def quoted(text):
    return '"%s"' % slash(text)


def find_jdk():
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(os.path.join(os.environ["JAVA_HOME"], "bin"))
    javac = shutil.which("javac")
    if javac:
        candidates.append(os.path.dirname(javac))
    candidates += sorted(glob.glob(r"C:\Program Files\Eclipse Adoptium\jdk-21*\bin"), reverse=True)
    candidates += sorted(glob.glob(os.path.join(os.path.expanduser("~"), ".gradle", "jdks", "*21*", "bin")),
                         reverse=True)
    suffix = ".exe" if os.name == "nt" else ""
    for folder in candidates:
        tool = os.path.join(folder, "javac" + suffix)
        if not os.path.exists(tool):
            continue
        version = subprocess.run([tool, "-version"], capture_output=True, text=True)
        if (version.stdout + version.stderr).strip().startswith("javac 21"):
            return tool, os.path.join(folder, "java" + suffix)
    sys.exit("no JDK 21 found (set JAVA_HOME)")


def properties():
    values = {}
    with open(os.path.join(REPO, "gradle.properties"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    return values


def classpath():
    """Minecraft と NeoForge の jar、Gradle が解決したライブラリ、build.gradle が名指しする MOD。"""
    moddev = os.path.join(REPO, "build", "moddev")
    artifacts = [path for path in glob.glob(os.path.join(moddev, "artifacts", "neoforge-*.jar"))
                 if not re.search(r"-(sources|merged|client-extra.*)\.jar$", os.path.basename(path))]
    legacy = os.path.join(moddev, "clientLegacyClasspath.txt")

    if len(artifacts) != 1 or not os.path.exists(legacy):
        sys.exit("build/moddev has no single NeoForge jar and client classpath: run a Gradle build once")

    with open(legacy, encoding="utf-8") as f:
        path = [artifacts[0]] + [line.strip() for line in f if line.strip()]

    values = properties()

    with open(os.path.join(REPO, "build.gradle"), encoding="utf-8") as f:
        for line in f:
            found = DEPENDENCY.match(line)
            if not found:
                continue
            coordinate = re.sub(r"\$\{(\w+)\}", lambda name: values.get(name.group(1), name.group(0)), found.group(1))
            group, artifact, version = coordinate.split(":")[:3]
            jars = sorted(jar for jar in glob.glob(os.path.join(CACHE, group, artifact, version, "*", "*.jar"))
                          if not jar.endswith(("-sources.jar", "-javadoc.jar")))
            if not jars:
                sys.exit("%s is not in the Gradle cache: run a Gradle build once" % coordinate)
            path += jars

    unique = []
    for jar in path:
        if jar not in unique:
            unique.append(jar)
    return unique


def argfile(name, lines):
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8") as f:
        for line in lines:
            f.write(line + "\n")
    return "@" + slash(path)


def compile_into(javac, name, destination, path, root):
    shutil.rmtree(destination, ignore_errors=True)
    os.makedirs(destination)
    sources = glob.glob(os.path.join(root, "**", "*.java"), recursive=True)
    print("compiling %d files under %s ..." % (len(sources), os.path.relpath(root, REPO)))
    arguments = argfile(name + ".args", ["-d", quoted(destination), "-encoding", "UTF-8", "-proc:none", "-nowarn",
                                          "-Xlint:none", "-cp", quoted(os.pathsep.join(path))]
                        + [quoted(source) for source in sources])
    # -J はargfileに書けない。日本語ロケールの javac は注記を化けた文字で出すので英語にする。
    return subprocess.run([javac, "-J-Duser.language=en", arguments]).returncode == 0


def main():
    javac, java = find_jdk()
    path = classpath()
    classes = os.path.join(OUT, "classes")
    checks = os.path.join(OUT, "checks")

    if not os.path.isdir(OUT):
        os.makedirs(OUT)

    if not compile_into(javac, "main", classes, path, os.path.join(REPO, "src", "main", "java")):
        sys.exit("the mod does not compile")

    if not compile_into(javac, "checks", checks, [classes] + path, HERE):
        sys.exit("the checks do not compile")

    run = argfile("run.args", ["-cp", quoted(os.pathsep.join([checks, classes] + path)), MAIN])
    return subprocess.run([java, run]).returncode


if __name__ == "__main__":
    sys.exit(main())
