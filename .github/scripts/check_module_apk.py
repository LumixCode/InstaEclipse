#!/usr/bin/env python3
import argparse
import glob
import os
import re
import struct
import subprocess
import sys
import zipfile

DEFAULT_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
ENTRY_CLASS = "ps.reso.instaeclipse.Xposed.ModernEntry"
TARGET_API = "102"
SCOPE = "com.instagram.android"
OWN_PREFIX = "Lps/reso/instaeclipse/"

REQUIRED_CLASSES = (
    "Lio/github/libxposed/service/XposedProvider;",
    "Lio/github/libxposed/service/XposedServiceHelper;",
    "Lio/github/libxposed/service/IXposedService$Stub;",
)
REQUIRED_METHODS = (
    ("Lps/reso/instaeclipse/Xposed/ModernEntry;->", "<init>()V", True),
    ("Lorg/luckypray/dexkit/query/base/BaseQuery;->", "innerBuild(Lcom/google/flatbuffers/FlatBufferBuilder;)I", False),
    ("Lcom/google/flatbuffers/FlatBufferBuilder;->", "<init>()V", False),
    ("Lcom/google/flatbuffers/FlatBufferBuilder;->", "sizedByteArray()[B", False),
)
COMPONENT_TAGS = ("application", "activity", "activity-alias", "service", "receiver", "provider")

ACC_PUBLIC = 0x1
NO_INDEX = 0xFFFFFFFF


def uleb128(data, pos):
    result = 0
    shift = 0
    while True:
        b = data[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, pos
        shift += 7


def mutf8(data, pos):
    units = []
    while True:
        a = data[pos]
        if a == 0:
            break
        if a < 0x80:
            units.append(a)
            pos += 1
        elif a & 0xE0 == 0xC0:
            units.append(((a & 0x1F) << 6) | (data[pos + 1] & 0x3F))
            pos += 2
        else:
            units.append(((a & 0x0F) << 12) | ((data[pos + 1] & 0x3F) << 6) | (data[pos + 2] & 0x3F))
            pos += 3
    raw = b"".join(struct.pack("<H", u) for u in units)
    return raw.decode("utf-16-le", errors="surrogatepass")


class Dex:
    def __init__(self, data):
        if data[:4] != b"dex\n":
            raise ValueError("not a dex file")
        self.data = data
        (self.string_ids_size, self.string_ids_off, self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off, self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off, self.class_defs_size,
         self.class_defs_off) = struct.unpack_from("<12I", data, 0x38)
        self._strings = {}
        self._types = {}

    def string(self, idx):
        s = self._strings.get(idx)
        if s is None:
            off = struct.unpack_from("<I", self.data, self.string_ids_off + idx * 4)[0]
            _, pos = uleb128(self.data, off)
            s = mutf8(self.data, pos)
            self._strings[idx] = s
        return s

    def type(self, idx):
        t = self._types.get(idx)
        if t is None:
            t = self.string(struct.unpack_from("<I", self.data, self.type_ids_off + idx * 4)[0])
            self._types[idx] = t
        return t

    def proto(self, idx):
        _, return_idx, params_off = struct.unpack_from("<3I", self.data, self.proto_ids_off + idx * 12)
        params = []
        if params_off:
            size = struct.unpack_from("<I", self.data, params_off)[0]
            for i in range(size):
                params.append(self.type(struct.unpack_from("<H", self.data, params_off + 4 + i * 2)[0]))
        return "(" + "".join(params) + ")" + self.type(return_idx)

    def method(self, idx):
        class_idx, proto_idx, name_idx = struct.unpack_from("<HHI", self.data, self.method_ids_off + idx * 8)
        return self.type(class_idx), self.string(name_idx) + self.proto(proto_idx)

    def field(self, idx):
        class_idx, type_idx, name_idx = struct.unpack_from("<HHI", self.data, self.field_ids_off + idx * 8)
        return self.type(class_idx), self.string(name_idx) + ":" + self.type(type_idx)

    def classes(self):
        for i in range(self.class_defs_size):
            class_idx, _, _, _, _, _, class_data_off, _ = struct.unpack_from(
                "<8I", self.data, self.class_defs_off + i * 32)
            yield self.type(class_idx), class_data_off

    def members(self, class_data_off):
        fields = []
        methods = []
        if not class_data_off:
            return fields, methods
        pos = class_data_off
        sizes = []
        for _ in range(4):
            n, pos = uleb128(self.data, pos)
            sizes.append(n)
        for count in sizes[:2]:
            idx = 0
            for _ in range(count):
                diff, pos = uleb128(self.data, pos)
                _, pos = uleb128(self.data, pos)
                idx += diff
                fields.append(self.field(idx)[1])
        for count in sizes[2:]:
            idx = 0
            for _ in range(count):
                diff, pos = uleb128(self.data, pos)
                access, pos = uleb128(self.data, pos)
                _, pos = uleb128(self.data, pos)
                idx += diff
                methods.append((self.method(idx)[1], access))
        return fields, methods


def read_lines(apk, name):
    try:
        text = apk.read(name).decode("utf-8", errors="replace")
    except KeyError:
        return None
    return [line.strip() for line in text.splitlines() if line.strip()]


def check_module_files(apk, problems):
    entries = read_lines(apk, "META-INF/xposed/java_init.list")
    if entries is None:
        problems.append("META-INF/xposed/java_init.list is missing")
    elif entries != [ENTRY_CLASS]:
        problems.append("META-INF/xposed/java_init.list is %r, expected exactly %s" % (entries, ENTRY_CLASS))
    props = read_lines(apk, "META-INF/xposed/module.prop")
    if props is None:
        problems.append("META-INF/xposed/module.prop is missing")
    elif "targetApiVersion=" + TARGET_API not in [p.replace(" ", "") for p in props]:
        problems.append("META-INF/xposed/module.prop lacks targetApiVersion=" + TARGET_API)
    scope = read_lines(apk, "META-INF/xposed/scope.list")
    if scope is None:
        problems.append("META-INF/xposed/scope.list is missing")
    elif SCOPE not in scope:
        problems.append("META-INF/xposed/scope.list lacks " + SCOPE)
    if "assets/xposed_init" in apk.namelist():
        problems.append("assets/xposed_init is present (legacy entry)")


def dex_entries(apk, problems):
    infos = {i.filename: i for i in apk.infolist()}
    names = sorted((n for n in infos if re.fullmatch(r"classes\d*\.dex", n)),
                   key=lambda n: int(n[7:-4] or 1))
    expected = ["classes.dex"] + ["classes%d.dex" % i for i in range(2, len(names) + 1)]
    if names != expected:
        problems.append("dex files are not contiguous: " + ", ".join(names or ["none"]))
    for n in names:
        if infos[n].compress_type != zipfile.ZIP_STORED:
            problems.append(n + " is not STORED")
    arsc = infos.get("resources.arsc")
    if arsc is None:
        problems.append("resources.arsc is missing")
    elif arsc.compress_type != zipfile.ZIP_STORED:
        problems.append("resources.arsc is not STORED")
    return [infos[n] for n in names]


def check_native_libs(apk, abis, problems):
    infos = {i.filename: i for i in apk.infolist()}
    present = sorted({n.split("/")[1] for n in infos if n.startswith("lib/") and n.count("/") >= 2})
    for abi in abis:
        info = infos.get("lib/%s/libdexkit.so" % abi)
        if info is None:
            problems.append("lib/%s/libdexkit.so is missing" % abi)
        elif info.compress_type != zipfile.ZIP_DEFLATED:
            problems.append("lib/%s/libdexkit.so is not DEFLATED" % abi)
    for abi in present:
        if abi not in abis:
            problems.append("unexpected ABI lib/%s/" % abi)


def load_definitions(apk, dex_infos):
    classes = {}
    for info in dex_infos:
        dex = Dex(apk.read(info))
        for name, class_data_off in dex.classes():
            fields, methods = dex.members(class_data_off)
            classes[name] = (fields, methods)
    return classes


def check_definitions(classes, problems):
    for name in REQUIRED_CLASSES:
        if name not in classes:
            problems.append("class %s is not defined in the dex" % name)
    for owner, sig, public in REQUIRED_METHODS:
        cls = owner[:-2]
        found = [access for m, access in classes.get(cls, ((), ()))[1] if m == sig]
        if not found:
            problems.append("method %s%s is not defined in the dex" % (owner, sig))
        elif public and not found[0] & ACC_PUBLIC:
            problems.append("method %s%s is not public" % (owner, sig))


def find_aapt2():
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not home:
        return None
    found = []
    for d in glob.glob(os.path.join(home, "build-tools", "*")):
        for exe in ("aapt2", "aapt2.exe"):
            p = os.path.join(d, exe)
            if os.path.isfile(p):
                version = tuple(int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", os.path.basename(d)))
                found.append((version, p))
    return max(found)[1] if found else None


def manifest_components(aapt2, apk_path):
    out = subprocess.run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk_path],
                         capture_output=True, text=True, check=True).stdout
    package = None
    names = []
    tag = None
    for line in out.splitlines():
        m = re.match(r"\s*E: (\S+)", line)
        if m:
            tag = m.group(1)
            continue
        m = re.match(r'\s*A: (?:http://schemas\.android\.com/apk/res/android:)?(\w+)(?:\(0x[0-9a-f]+\))?="([^"]*)"', line)
        if not m:
            continue
        attr, value = m.group(1), m.group(2)
        if tag == "manifest" and attr == "package":
            package = value
        elif tag in COMPONENT_TAGS:
            if (tag == "activity-alias" and attr == "targetActivity") or (tag != "activity-alias" and attr == "name"):
                names.append(value)
    result = []
    for n in names:
        if n.startswith("."):
            n = (package or "") + n
        elif "." not in n and package:
            n = package + "." + n
        result.append("L" + n.replace(".", "/") + ";")
    return result


def check_manifest(apk_path, classes, problems):
    aapt2 = find_aapt2()
    if aapt2 is None:
        print("manifest components: skipped (no aapt2 under $ANDROID_HOME/build-tools)")
        return
    try:
        components = manifest_components(aapt2, apk_path)
    except (OSError, subprocess.CalledProcessError) as e:
        problems.append("aapt2 could not read the manifest: %s" % e)
        return
    for c in components:
        if c not in classes:
            problems.append("manifest component %s is not defined in the dex" % c)
    print("manifest components: %d checked" % len(components))


def own_excluded(name):
    simple = name[:-1].rsplit("/", 1)[-1]
    return (simple == "R" or simple.startswith("R$") or "$$ExternalSynthetic" in name
            or "$r8$" in name or "-$$Nest$" in name)


def dump_own(classes, out):
    lines = []
    for name, (fields, methods) in classes.items():
        if not name.startswith(OWN_PREFIX) or own_excluded(name):
            continue
        lines.append(name)
        for f in fields:
            if "$r8$" not in f and "-$$Nest$" not in f:
                lines.append(name + "->" + f)
        for m, _ in methods:
            if "$r8$" not in m and "-$$Nest$" not in m:
                lines.append(name + "->" + m)
    lines.sort()
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("own definitions: %d lines written to %s" % (len(lines), out))


def main():
    parser = argparse.ArgumentParser(description="Check that an APK is still a loadable InstaEclipse module.")
    parser.add_argument("apk")
    parser.add_argument("--abis", default=",".join(DEFAULT_ABIS))
    parser.add_argument("--dump-own", metavar="OUT")
    args = parser.parse_args()
    abis = [a for a in args.abis.split(",") if a]
    problems = []
    print("apk: %s" % args.apk)
    try:
        apk = zipfile.ZipFile(args.apk)
    except (OSError, zipfile.BadZipFile) as e:
        print("FAIL %s cannot be opened as a zip: %s" % (args.apk, e))
        return 1
    with apk:
        print("apk bytes: %d" % os.path.getsize(args.apk))
        check_module_files(apk, problems)
        dex_infos = dex_entries(apk, problems)
        print("dex bytes: %d in %d files" % (sum(i.file_size for i in dex_infos), len(dex_infos)))
        check_native_libs(apk, abis, problems)
        try:
            classes = load_definitions(apk, dex_infos)
        except (ValueError, IndexError, struct.error) as e:
            problems.append("dex could not be parsed: %s" % e)
            classes = {}
        check_definitions(classes, problems)
        check_manifest(args.apk, classes, problems)
        if args.dump_own:
            dump_own(classes, args.dump_own)
    for p in problems:
        print("FAIL " + p)
    if problems:
        print("%d problem(s)" % len(problems))
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
