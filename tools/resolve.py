#!/usr/bin/env python3
"""Minimal Maven POM resolver: walks compile/runtime deps, downloads aar/jar artifacts.
Repos: Google Maven first (androidx), then Maven Central."""
import os, subprocess, sys, xml.etree.ElementTree as ET
from collections import deque

SPIKE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIBS = os.path.join(SPIKE, "libs")
CACHE = os.path.join(SPIKE, "build", "pomcache")
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

def interp(s, props, parent_props):
    if s is None:
        return None
    merged = dict(parent_props); merged.update(props)
    for _ in range(5):
        if "${" not in s:
            break
        for k, v in merged.items():
            s = s.replace("${%s}" % k, v)
        if "${project.version}" in s and "project.version" in merged:
            s = s.replace("${project.version}", merged["project.version"])
    return s

def parse_pom(g, a, v, depth=0):
    """Returns (props, deps list of (g,a,v,type,scope,optional), packaging)"""
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
    ver_el = root.find("m:version", NS)
    myver = ver_el.text.strip() if ver_el is not None else v
    props["project.version"] = myver
    props["pom.version"] = myver
    # parent: merge depMgmt + props
    dep_mgmt = {}
    parent = root.find("m:parent", NS)
    if parent is not None and depth < 4:
        pg, pa, pv = txt(parent, "groupId"), txt(parent, "artifactId"), txt(parent, "version")
        pprops, _, _ = parse_pom(pg, pa, pv, depth + 1)
        for k, val in pprops.items():
            props.setdefault(k, val)
        # parent depMgmt needs separate parse; handled below via recursion return
    dm = root.find("m:dependencyManagement/m:dependencies", NS)
    if dm is not None:
        for d in dm.findall("m:dependency", NS):
            dg, da, dv = txt(d, "groupId"), txt(d, "artifactId"), txt(d, "version")
            if dg and da and dv:
                dep_mgmt[(dg, da)] = interp(dv, props, {})
    deps = []
    deps_el = root.find("m:dependencies", NS)
    if deps_el is not None:
        for d in deps_el.findall("m:dependency", NS):
            dg, da = txt(d, "groupId"), txt(d, "artifactId")
            dv = txt(d, "version")
            if dv is None:
                dv = dep_mgmt.get((dg, da))
            dv = clean_version(interp(dv, props, {}))
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
    fn = f"{a}-{v}.{ext}"
    dest = os.path.join(LIBS, fn)
    if os.path.exists(dest):
        return dest
    for repo in REPOS:
        if fetch(f"{repo}/{path_of(g, a, v, fn)}", dest):
            return dest
    return None

def clean_version(v):
    if v is None:
        return None
    v = v.strip()
    if v.startswith("[") and v.endswith("]"):
        v = v[1:-1].split(",")[0].strip()
    return v

# Gradle-style highest-version pins for families that must stay coherent
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

seen = {}       # (g,a) -> version chosen
artifacts = []  # (g,a,v,packaging)
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
        if (dg, da) in seen:
            continue
        seen[(dg, da)] = dv
        queue.append((dg, da, dv))

print("\n=== downloading artifacts ===")
ok, missing = [], []
for g, a, v, packaging in artifacts:
    typ = "aar" if packaging == "aar" else "jar"
    d = download_artifact(g, a, v, typ)
    if d:
        ok.append((g, a, v, os.path.basename(d)))
    else:
        # maybe packaging lied; try the other ext
        alt = download_artifact(g, a, v, "jar" if typ == "aar" else "aar")
        if alt:
            ok.append((g, a, v, os.path.basename(alt)))
        else:
            missing.append(f"{g}:{a}:{v} ({packaging})")

print(f"\nOK {len(ok)}:")
for g, a, v, f in ok:
    print(f"  {g}:{a}:{v} -> {f}")
print(f"\nMISSING {len(missing)}:")
for m in missing:
    print(f"  {m}")
