#!/usr/bin/env python3
"""手動ビルド用の最小 Maven 依存解決スクリプト。

Compose のルート成果物から compile/runtime 依存グラフをたどり、
.aar / .jar を libs/ へダウンロードして libs/artifacts.txt
(build-apk.sh が消費するファイル一覧) を書く。
リポジトリは Google Maven (androidx) → Maven Central の順に試す。
成果物が1つでも欠けたら artifacts.txt を更新せず非ゼロで終了する
(不完全なダウンロードがビルドに紛れ込まないようにするため)。
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import deque

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIBS = os.path.join(ROOT_DIR, "libs")
CACHE = os.path.join(ROOT_DIR, "build", "pomcache")
os.makedirs(LIBS, exist_ok=True)
os.makedirs(CACHE, exist_ok=True)

REPOS = [
    "https://dl.google.com/dl/android/maven2",
    "https://repo1.maven.org/maven2",
]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def path_of(g, a, v, filename):
    return f"{g.replace('.', '/')}/{a}/{v}/{filename}"


def fetch(url, dest=None):
    if dest and os.path.exists(dest):
        return dest
    r = subprocess.run(["curl", "-sfL", url], capture_output=True)
    if r.returncode != 0:
        return None
    if dest:
        with open(dest, "wb") as f:
            f.write(r.stdout)
        return dest
    return r.stdout


def get_pom(g, a, v):
    fn = f"{a}-{v}.pom"
    dest = os.path.join(CACHE, f"{g}_{a}_{v}.pom")
    if os.path.exists(dest):
        return dest
    for repo in REPOS:
        if fetch(f"{repo}/{path_of(g, a, v, fn)}", dest):
            return dest
    return None


def txt(el, tag):
    c = el.find(f"m:{tag}", NS)
    return c.text.strip() if c is not None and c.text else None


def interp(value, props):
    if value is None:
        return None
    out = value
    for _ in range(5):
        start = out.find("${")
        if start < 0:
            break
        end = out.find("}", start)
        if end < 0:
            break
        key = out[start + 2:end]
        if key not in props:
            break
        out = out[:start] + props[key] + out[end + 1:]
    return out


def clean_version(v):
    # Maven のバージョン範囲 "[1.7.6]" などは下限に正規化する
    if v is None:
        return None
    v = v.strip()
    if v.startswith("[") and v.endswith("]"):
        v = v[1:-1].split(",")[0].strip()
    return v


def parse_pom(g, a, v, depth=0):
    """(props, deps, packaging) を返す。deps は (g, a, v, type, scope, optional)。"""
    p = get_pom(g, a, v)
    if not p:
        print(f"  !! POM not found: {g}:{a}:{v}")
        return {}, [], "jar"
    root = ET.parse(p).getroot()

    props = {}
    props_el = root.find("m:properties", NS)
    if props_el is not None:
        for child in props_el:
            tag = child.tag.split("}")[-1]
            props[tag] = (child.text or "").strip()
    myver = txt(root, "version") or v
    props["project.version"] = myver
    props["pom.version"] = myver

    dep_mgmt = {}
    parent = root.find("m:parent", NS)
    if parent is not None and depth < 4:
        pg, pa, pv = txt(parent, "groupId"), txt(parent, "artifactId"), txt(parent, "version")
        pprops, _, _ = parse_pom(pg, pa, pv, depth + 1)
        for k, val in pprops.items():
            props.setdefault(k, val)

    dm = root.find("m:dependencyManagement/m:dependencies", NS)
    if dm is not None:
        for d in dm.findall("m:dependency", NS):
            dg, da, dv = txt(d, "groupId"), txt(d, "artifactId"), txt(d, "version")
            if dg and da and dv:
                dep_mgmt[(dg, da)] = interp(dv, props)

    deps = []
    deps_el = root.find("m:dependencies", NS)
    if deps_el is not None:
        for d in deps_el.findall("m:dependency", NS):
            dg, da = txt(d, "groupId"), txt(d, "artifactId")
            dv = txt(d, "version")
            if dv is None:
                dv = dep_mgmt.get((dg, da))
            dv = clean_version(interp(dv, props))
            if (dg, da) in PINS:
                dv = PINS[(dg, da)]
            scope = txt(d, "scope") or "compile"
            typ = txt(d, "type") or "jar"
            opt = txt(d, "optional") or "false"
            if dg and da and dv:
                deps.append((dg, da, dv, typ, scope, opt == "true"))
    packaging = txt(root, "packaging") or "jar"
    return props, deps, packaging


def download_artifact(g, a, v, typ):
    ext = "aar" if typ == "aar" else "jar"
    # ファイル名は {artifact}-{version}.{ext} (groupId は含めない)
    fn = f"{a}-{v}.{ext}"
    dest = os.path.join(LIBS, fn)
    if os.path.exists(dest):
        return dest
    for repo in REPOS:
        if fetch(f"{repo}/{path_of(g, a, v, fn)}", dest):
            return dest
    return None


# Gradle 流の最高バージョン固定: 世代が揃わないと壊れるファミリーを揃える
PINS = {
    ("androidx.lifecycle", "lifecycle-common"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-common-java8"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-runtime"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-runtime-ktx"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-viewmodel"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-viewmodel-ktx"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-viewmodel-savedstate"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-process"): "2.8.7",
    ("androidx.lifecycle", "lifecycle-runtime-compose"): "2.8.7",
    ("androidx.core", "core"): "1.13.1",
    ("androidx.core", "core-ktx"): "1.13.1",
    ("androidx.arch.core", "core-common"): "2.2.0",
    ("androidx.arch.core", "core-runtime"): "2.2.0",
}

ROOTS = [
    ("androidx.compose.ui", "ui", "1.7.6"),
    ("androidx.compose.ui", "ui-graphics", "1.7.6"),
    ("androidx.compose.ui", "ui-text", "1.7.6"),
    ("androidx.compose.ui", "ui-unit", "1.7.6"),
    ("androidx.compose.foundation", "foundation", "1.7.6"),
    ("androidx.compose.foundation", "foundation-layout", "1.7.6"),
    ("androidx.compose.runtime", "runtime", "1.7.6"),
    ("androidx.compose.runtime", "runtime-saveable", "1.7.6"),
    ("androidx.compose.material3", "material3", "1.3.1"),
    ("androidx.activity", "activity-compose", "1.9.3"),
]

# 除外: collection-ktx 1.2.0 のクラスは collection-jvm 1.4.0 に統合済みで、
# 両方を dex に入れると duplicate class で落ちる
EXCLUDES = {("androidx.collection", "collection-ktx")}


def main():
    seen = {}
    artifacts = []
    queue = deque(ROOTS)
    for g, a, v in ROOTS:
        seen[(g, a)] = v

    while queue:
        g, a, v = queue.popleft()
        props, deps, packaging = parse_pom(g, a, v)
        artifacts.append((g, a, v, packaging))
        print(f"{g}:{a}:{v} [{packaging}] deps={len(deps)}")
        for dg, da, dv, typ, scope, optional in deps:
            if optional or scope in ("test", "provided", "system", "import"):
                continue
            if (dg, da) in EXCLUDES:
                continue
            if (dg, da) in seen:
                continue
            seen[(dg, da)] = dv
            queue.append((dg, da, dv))

    names = []
    failed = False
    for g, a, v, packaging in artifacts:
        if download_artifact(g, a, v, packaging) is None:
            print(f"  !! download failed: {g}:{a}:{v}")
            failed = True
        else:
            ext = "aar" if packaging == "aar" else "jar"
            names.append(f"{a}-{v}.{ext}")
    if failed:
        print("依存の解決に失敗しました (artifacts.txt は更新していません)", file=sys.stderr)
        return 1
    with open(os.path.join(LIBS, "artifacts.txt"), "w") as f:
        f.write("\n".join(names) + "\n")
    print(f"resolved {len(names)} artifacts -> libs/artifacts.txt")
    return 0


if __name__ == "__main__":
    sys.exit(main())
