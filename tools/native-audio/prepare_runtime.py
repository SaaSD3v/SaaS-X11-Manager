#!/usr/bin/env python3
"""Prepare a self-contained Android PulseAudio runtime for SaaS X11 Manager.

The runtime is built from the same Termux Android package stream that provides
our physically validated PulseAudio 17 AAudio/OpenSL ES backend, but it is
repackaged into the Manager APK. Nothing from the Termux application is needed
at device runtime.

Generated files live under app/build and are never source-of-truth artifacts.
Every downloaded .deb is verified against the repository Packages index.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

MIRRORS = (
    "https://packages-cf.termux.dev/apt/termux-main",
    "https://termux.librehat.com/apt/termux-main",
)

ABI_TO_TERMUX = {
    "arm64-v8a": "aarch64",
    "armeabi-v7a": "arm",
    "x86": "i686",
    "x86_64": "x86_64",
}

ROOT_PACKAGE = "pulseaudio"
EXECUTABLES = {
    "pulseaudio": "libsaas_pulseaudio_exec.so",
    "pactl": "libsaas_pactl_exec.so",
    "pacat": "libsaas_pacat_exec.so",
}
REQUIRED_MODULES = {
    "module-aaudio-sink.so",
    "module-sles-sink.so",
    "module-native-protocol-unix.so",
    "module-native-protocol-tcp.so",
}

# Packages whose runtime is supplied by Android itself or which are metadata-only.
SKIP_DEPENDENCIES = {
    "base-files",
    "command-not-found",
    "termux-am",
    "termux-auth",
    "termux-core",
    "termux-exec",
    "termux-keyring",
    "termux-tools",
}


def die(message: str) -> None:
    print(f"native-audio: {message}", file=sys.stderr)
    raise SystemExit(1)


def run(*args: str, cwd: Path | None = None) -> str:
    proc = subprocess.run(
        list(args), cwd=cwd, text=True, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, check=False,
    )
    if proc.returncode != 0:
        die(f"command failed ({proc.returncode}): {' '.join(args)}\n{proc.stderr}")
    return proc.stdout


def download(url: str, destination: Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "SaaS-X11-Manager-native-audio"})
    try:
        with urllib.request.urlopen(request, timeout=90) as response, destination.open("wb") as out:
            shutil.copyfileobj(response, out)
    except (urllib.error.URLError, TimeoutError) as exc:
        die(f"download failed: {url}: {exc}")


def fetch_index(arch: str, work: Path) -> tuple[str, str]:
    errors: list[str] = []
    for mirror in MIRRORS:
        url = f"{mirror}/dists/stable/main/binary-{arch}/Packages.gz"
        target = work / f"Packages-{arch}.gz"
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "SaaS-X11-Manager-native-audio"})
            with urllib.request.urlopen(request, timeout=60) as response, target.open("wb") as out:
                shutil.copyfileobj(response, out)
            with gzip.open(target, "rt", encoding="utf-8", errors="replace") as src:
                return mirror, src.read()
        except Exception as exc:  # try the next mirror
            errors.append(f"{url}: {exc}")
    die("could not fetch Termux package index:\n" + "\n".join(errors))
    raise AssertionError


def parse_control_paragraphs(text: str) -> dict[str, dict[str, str]]:
    packages: dict[str, dict[str, str]] = {}
    for paragraph in re.split(r"\n\s*\n", text):
        if not paragraph.strip():
            continue
        fields: dict[str, str] = {}
        current: str | None = None
        for line in paragraph.splitlines():
            if line.startswith((" ", "\t")) and current:
                fields[current] = fields[current] + " " + line.strip()
                continue
            if ":" not in line:
                continue
            key, value = line.split(":", 1)
            current = key.strip()
            fields[current] = value.strip()
        name = fields.get("Package")
        if name:
            packages[name] = fields
    return packages


def dependency_name(token: str) -> str:
    token = re.sub(r"\[[^]]*]", "", token).strip()
    token = re.sub(r"\([^)]*\)", "", token).strip()
    token = token.split(":", 1)[0].strip()
    return token


def choose_dependency(group: str, packages: dict[str, dict[str, str]]) -> str | None:
    for alternative in group.split("|"):
        name = dependency_name(alternative)
        if not name or name in SKIP_DEPENDENCIES:
            continue
        if name in packages:
            return name
    return None


def resolve_packages(root: str, packages: dict[str, dict[str, str]]) -> list[str]:
    if root not in packages:
        die(f"package {root!r} is missing from Termux index")
    ordered: list[str] = []
    visiting: set[str] = set()
    done: set[str] = set()

    def visit(name: str) -> None:
        if name in done or name in SKIP_DEPENDENCIES:
            return
        if name in visiting:
            return
        meta = packages.get(name)
        if meta is None:
            return
        visiting.add(name)
        deps = ",".join(filter(None, (meta.get("Pre-Depends", ""), meta.get("Depends", ""))))
        for group in deps.split(","):
            group = group.strip()
            if not group:
                continue
            selected = choose_dependency(group, packages)
            if selected:
                visit(selected)
        visiting.remove(name)
        done.add(name)
        ordered.append(name)

    visit(root)
    return ordered


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_packages(
    mirror: str,
    names: list[str],
    packages: dict[str, dict[str, str]],
    work: Path,
) -> list[Path]:
    result: list[Path] = []
    deb_dir = work / "debs"
    deb_dir.mkdir(parents=True, exist_ok=True)
    for name in names:
        meta = packages[name]
        filename = meta.get("Filename")
        expected = meta.get("SHA256", "").lower()
        if not filename or not expected:
            die(f"package metadata for {name} lacks Filename/SHA256")
        destination = deb_dir / Path(filename).name
        if not destination.exists():
            print(f"native-audio: downloading {name} {meta.get('Version', '')}")
            download(f"{mirror}/{filename.lstrip('/')}", destination)
        actual = sha256(destination)
        if actual != expected:
            die(f"SHA-256 mismatch for {name}: expected {expected}, got {actual}")
        result.append(destination)
    return result


def extract_packages(debs: list[Path], work: Path) -> Path:
    root = work / "root"
    root.mkdir(parents=True, exist_ok=True)
    for deb in debs:
        run("dpkg-deb", "-x", str(deb), str(root))
    return root


def is_elf(path: Path) -> bool:
    try:
        with path.open("rb") as f:
            return f.read(4) == b"\x7fELF"
    except OSError:
        return False


def termux_prefix(root: Path) -> Path:
    candidates = [
        root / "data/data/com.termux/files/usr",
        root / "data/data/com.termux/files/usr-staging",
    ]
    for candidate in candidates:
        if candidate.exists():
            return candidate
    for p in root.rglob("bin/pulseaudio"):
        return p.parent.parent
    die("could not locate Termux prefix in extracted packages")
    raise AssertionError


def canonical_library_name(name: str) -> str:
    # libfoo.so.1.2 -> libfoo.so.  Android's APK native-library scanner only
    # accepts .so names; DT_NEEDED entries are rewritten to this canonical form.
    if ".so." in name:
        return name.split(".so.", 1)[0] + ".so"
    return name


def collect_runtime(prefix: Path, out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    copied: dict[str, Path] = {}
    aliases: dict[str, str] = {}

    def copy_elf(source: Path, destination_name: str) -> None:
        if not is_elf(source):
            die(f"expected ELF file: {source}")
        destination = out / destination_name
        if destination_name in copied:
            return
        shutil.copy2(source, destination)
        destination.chmod(0o755)
        copied[destination_name] = destination

    for binary, renamed in EXECUTABLES.items():
        source = prefix / "bin" / binary
        if not source.is_file():
            die(f"required PulseAudio executable missing: {source}")
        copy_elf(source, renamed)

    module_sources: dict[str, Path] = {}
    for path in prefix.rglob("module-*.so"):
        if path.name in REQUIRED_MODULES:
            module_sources[path.name] = path
    missing_modules = REQUIRED_MODULES - module_sources.keys()
    if missing_modules:
        die("required PulseAudio modules missing: " + ", ".join(sorted(missing_modules)))
    for name, source in sorted(module_sources.items()):
        copy_elf(source, name)

    # Flatten all runtime ELF libraries from the dependency closure. PulseAudio
    # modules use dlopen(), so keeping the closure together in nativeLibraryDir
    # gives both the executable and modules a deterministic $ORIGIN search path.
    for path in prefix.rglob("*"):
        if not path.is_file() or path.is_symlink() or not is_elf(path):
            continue
        if "/bin/" in path.as_posix() or path.name in REQUIRED_MODULES:
            continue
        if ".so" not in path.name:
            continue
        canonical = canonical_library_name(path.name)
        copy_elf(path, canonical)
        aliases[path.name] = canonical
        try:
            soname = run("patchelf", "--print-soname", str(path)).strip()
        except SystemExit:
            soname = ""
        if soname:
            aliases[soname] = canonical

    # Preserve symlink aliases such as libpulse.so.0 -> libpulse.so by teaching
    # the dynamic linker users to request the canonical .so name instead.
    for path in prefix.rglob("*.so*"):
        if not path.is_symlink():
            continue
        target_name = canonical_library_name(Path(os.path.realpath(path)).name)
        if target_name in copied:
            aliases[path.name] = target_name

    for name, path in copied.items():
        needed = run("patchelf", "--print-needed", str(path)).splitlines()
        for old in needed:
            new = aliases.get(old) or canonical_library_name(old)
            if new != old and new in copied:
                run("patchelf", "--replace-needed", old, new, str(path))
        run("patchelf", "--set-rpath", "$ORIGIN", str(path))
        if name not in EXECUTABLES.values():
            try:
                run("patchelf", "--set-soname", name, str(path))
            except SystemExit:
                pass

    required_outputs = set(EXECUTABLES.values()) | REQUIRED_MODULES
    missing = [name for name in required_outputs if not (out / name).is_file()]
    if missing:
        die("runtime assembly incomplete: " + ", ".join(sorted(missing)))


def write_manifest(out: Path, abi: str, names: list[str], packages: dict[str, dict[str, str]]) -> None:
    lines = [
        "SaaS X11 Manager embedded PulseAudio runtime",
        f"ABI={abi}",
        "provider=Termux package build stream (repackaged; no Termux app runtime)",
    ]
    for name in names:
        lines.append(f"package={name} version={packages[name].get('Version', 'unknown')}")
    (out / "NATIVE-AUDIO-MANIFEST.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def prepare_abi(abi: str, output_root: Path, work_root: Path) -> None:
    termux_arch = ABI_TO_TERMUX[abi]
    abi_work = work_root / abi
    abi_work.mkdir(parents=True, exist_ok=True)
    mirror, index_text = fetch_index(termux_arch, abi_work)
    packages = parse_control_paragraphs(index_text)
    names = resolve_packages(ROOT_PACKAGE, packages)
    print(f"native-audio: {abi}: resolved {len(names)} packages")
    debs = download_packages(mirror, names, packages, abi_work)
    root = extract_packages(debs, abi_work)
    prefix = termux_prefix(root)
    out = output_root / abi
    if out.exists():
        shutil.rmtree(out)
    collect_runtime(prefix, out)
    write_manifest(out, abi, names, packages)
    print(f"native-audio: {abi}: runtime ready at {out}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        default="app/build/generated/nativeAudio/jniLibs",
        help="jniLibs output root",
    )
    parser.add_argument(
        "--abi",
        action="append",
        choices=sorted(ABI_TO_TERMUX),
        help="ABI to prepare (repeatable); defaults to every app ABI",
    )
    args = parser.parse_args()

    for tool in ("dpkg-deb", "patchelf"):
        if shutil.which(tool) is None:
            die(f"required host tool is missing: {tool}")

    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    abis = args.abi or list(ABI_TO_TERMUX)
    with tempfile.TemporaryDirectory(prefix="saas-native-audio-") as temp:
        work = Path(temp)
        for abi in abis:
            prepare_abi(abi, output, work)


if __name__ == "__main__":
    main()
