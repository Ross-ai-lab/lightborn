"""Companion mods: other authors' mods that Lightborn is built and tested together with.

  py -3.14 -I tools/companion_mods.py resolve   # look up the newest 26.3 Fabric release of each wanted mod and
                                                 # its required libraries on Modrinth; write companion-mods.lock.json
                                                 # --keep-pins preserves existing versions while adding new mods
  py -3.14 -I tools/companion_mods.py fetch     # download exactly what the lock file pins (sha512-checked) into
                                                 # companion-mods/ (used by the dev and test runs) and Game/mods

The jars are gitignored; the lock file is tracked, so `fetch` restores the identical set on any PC.
Fabric API is left out: the build pins it itself (gradle.properties) and Game/mods carries that same version.
"""
import hashlib, json, pathlib, shutil, sys, urllib.parse, urllib.request, zipfile, io, struct

WANTED = ["mutant-monsters", "variants-and-ventures", "friends-and-foes", "naturalist", "from-the-fog",
          "sit", "visual-workbench", "terralith", "ambientsounds", "xaeros-world-map",
          "dungeons-and-taverns",
          "moogs-voyager-structures", "entity-model-features",
          "entitytexturefeatures", "double-doors", "mouse-tweaks", "nemos-creatures",
          "fryc-mob-variants", "mutants-and-zombies",
          "spawn-animations", "sound", "enchantment-descriptions",
          "leaves-be-gone", "legendary-tooltips", "item-borders", "mns-moogs-nether-structures", "mes-moogs-end-structures",
          # Frame rate (owner, 2026-11-20): Lithium speeds the integrated server's tick, the laptop's bottleneck;
          # EntityCulling skips drawing mobs hidden behind walls, which night hearing gathers in crowds. Left out on
          # purpose: C2ME (alpha only for 26.3, risks the save), ImmediatelyFast (26.x already batches its GUI),
          # FerriteCore (saves RAM, which is plentiful) and ModernFix (no 26.3 build).
          "lithium", "entityculling"]
PACKS = ["fresh-animations"]
EXTRA_REQUIRED = []
# Tectonic is deliberately excluded: its continents felt too large and empty to the owner.
VERSION_IDS = {}
MC, LOADER = "26.3", "fabric"
SKIP = {"P7dR8mSH"}  # Fabric API
ROOT = pathlib.Path(__file__).resolve().parent.parent
LOCK = ROOT / "tools" / "companion-mods.lock.json"
UA = {"User-Agent": "lightborn-companion-mods"}


def api(path, **params):
    q = "?" + urllib.parse.urlencode({k: json.dumps(v) for k, v in params.items()}) if params else ""
    with urllib.request.urlopen(urllib.request.Request("https://api.modrinth.com/v2" + path + q, headers=UA)) as r:
        return json.load(r)


def resolve(keep_pins=False):
    previous = {m["slug"]: m for m in json.loads(LOCK.read_text(encoding="utf-8"))} if keep_pins and LOCK.exists() else {}
    found, queue = {}, [(s, None) for s in WANTED + PACKS + EXTRA_REQUIRED]
    while queue:
        ref, pinned = queue.pop(0)
        project = api(f"/project/{ref}")
        loaders = ["minecraft"] if project["project_type"] == "resourcepack" else [LOADER]
        if project["id"] in found or project["id"] in SKIP:
            continue
        pinned = VERSION_IDS.get(project["slug"], pinned)
        old = previous.get(project["slug"])
        if old and not pinned:
            pinned = urllib.parse.urlparse(old["url"]).path.split("/versions/", 1)[1].split("/", 1)[0]
        if pinned:
            v = api(f"/version/{pinned}")
        else:
            versions = [v for v in api(f"/project/{ref}/version", loaders=loaders, game_versions=[MC]) if v["version_type"] == "release"] \
                or api(f"/project/{ref}/version", loaders=loaders, game_versions=[MC])
            if not versions:
                sys.exit(f"no {MC} {LOADER} build of {ref}")
            v = versions[0]
        if v["project_id"] in found or v["project_id"] in SKIP:
            continue
        f = next(f for f in v["files"] if f["primary"]) if any(f["primary"] for f in v["files"]) else v["files"][0]
        found[v["project_id"]] = {"slug": project["slug"], "title": project["title"], "version": v["version_number"],
                                  "wanted": project["slug"] in WANTED + PACKS, "file": f["filename"], "url": f["url"],
                                  "sha512": f["hashes"]["sha512"], "size": f["size"]}
        if project["project_type"] == "resourcepack":
            found[v["project_id"]]["resource_pack"] = True
        for d in v["dependencies"]:
            # This combined Fabric/Quilt upload lists QSL for its Quilt entry point only.
            if project["slug"] == "spawn-animations" and d.get("project_id") == "qvIfYCYJ":
                continue
            if d["dependency_type"] == "required" and d.get("project_id") not in SKIP:
                queue.append((d["project_id"], d.get("version_id")))
    lock = sorted(found.values(), key=lambda m: (not m["wanted"], m["slug"]))
    LOCK.write_text(json.dumps(lock, indent=1) + "\n", encoding="utf-8")
    for m in lock:
        print(f"{'mod' if m['wanted'] else 'lib'}  {m['slug']:<28} {m['version']:<28} {m['size'] / 1e6:5.1f} MB")


def mod_id(path):
    try:
        with zipfile.ZipFile(path) as jar:
            return json.loads(jar.read("fabric.mod.json"), strict=False)["id"]
    except (OSError, KeyError, ValueError, zipfile.BadZipFile):
        return None


def isolate_iceberg(data):
    """Relocate Iceberg's unrelocated NightConfig 3.6 copy away from FCAP's 3.8 API."""
    old, new = b"com/electronwill/nightconfig", b"lightborn/vendor/iceberg/nightconfig"
    def relocate_class(code):
        if code[:4] != b"\xca\xfe\xba\xbe":
            return code
        out=bytearray(code[:10]); offset=10; index=1
        while index < struct.unpack_from('>H',code,8)[0]:
            tag=code[offset]; out.append(tag); offset+=1
            if tag==1:
                length=struct.unpack_from('>H',code,offset)[0]; offset+=2
                value=code[offset:offset+length].replace(old,new).replace(old.replace(b'/',b'.'),new.replace(b'/',b'.'))
                out.extend(struct.pack('>H',len(value)));out.extend(value);offset+=length
            else:
                sizes={3:4,4:4,5:8,6:8,7:2,8:2,9:4,10:4,11:4,12:4,15:3,16:2,17:4,18:4,19:2,20:2}
                size=sizes[tag];out.extend(code[offset:offset+size]);offset+=size
                if tag in (5,6):index+=1
            index+=1
        out.extend(code[offset:]);return bytes(out)
    target=io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as source, zipfile.ZipFile(target,'w') as output:
        for info in source.infolist():
            value=source.read(info)
            if info.filename.endswith('.class'):value=relocate_class(value)
            info.filename=info.filename.replace(old.decode(),new.decode())
            output.writestr(info,value)
    return target.getvalue()

def fetch(deploy=True):
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    cache = ROOT / "companion-mods"
    game = ROOT / "Game" / "mods"
    packs = ROOT / "Game" / "resourcepacks"
    cache.mkdir(exist_ok=True)
    if deploy:
        game.mkdir(parents=True, exist_ok=True)
        packs.mkdir(parents=True, exist_ok=True)
    keep = {m["file"] for m in lock}
    installed = game / ".lightborn-companions.json"
    legacy = game / ".runeborn-companions.json"  # the record's name before the 2026-11-18 rename
    if deploy and legacy.exists():
        if not installed.exists():
            legacy.replace(installed)
        else:
            legacy.unlink()
    if deploy and installed.exists():
        for name in set(json.loads(installed.read_text(encoding="utf-8"))) - keep:
            if pathlib.Path(name).name == name and name.endswith((".jar", ".zip")):
                ((packs if name.endswith(".zip") else game) / name).unlink(missing_ok=True)
    for old in list(cache.glob("*.jar")) + list(cache.glob("*.zip")):
        if old.name not in keep:
            stale = (packs if old.suffix == ".zip" else game) / old.name
            if deploy and stale.exists():
                stale.unlink()
            old.unlink()
    for m in lock:
        dst = cache / m["file"]
        transformed=m['slug']=='iceberg'
        raw = cache / '.upstream' / m['file'] if transformed else dst
        if transformed and not raw.exists() and dst.exists() and hashlib.sha512(dst.read_bytes()).hexdigest()==m['sha512']:
            raw.parent.mkdir(exist_ok=True);shutil.copy2(dst,raw)
        if not raw.exists() or hashlib.sha512(raw.read_bytes()).hexdigest() != m["sha512"]:
            with urllib.request.urlopen(urllib.request.Request(m["url"], headers=UA)) as r:
                data = r.read()
            if hashlib.sha512(data).hexdigest() != m["sha512"]:
                sys.exit(f"checksum mismatch: {m['file']}")
            raw.parent.mkdir(parents=True,exist_ok=True);raw.write_bytes(data)
        if transformed:
            patched=isolate_iceberg(raw.read_bytes())
            if not dst.exists() or dst.read_bytes()!=patched:dst.write_bytes(patched)
        if deploy:
            if m.get("resource_pack"):
                shutil.copy2(dst, packs / m["file"])
                continue
            # Cache-only builds may already have removed the old cached version. Retire it by mod id in Game too.
            identity = mod_id(dst)
            if identity:
                for old in game.glob("*.jar"):
                    if old.name != m["file"] and mod_id(old) == identity:
                        old.unlink()
            shutil.copy2(dst, game / m["file"])
    if deploy:
        installed.write_text(json.dumps(sorted(keep), indent=1) + "\n", encoding="utf-8")
    print(f"{sum(not m.get('resource_pack') for m in lock)} companion jars and {sum(bool(m.get('resource_pack')) for m in lock)} resource packs verified" + (" and installed" if deploy else " (test cache only)"))


if __name__ == "__main__":
    if sys.argv[1] == "fetch":
        fetch(deploy="--cache-only" not in sys.argv)
    else:
        if sys.argv[1] == "resolve":
            resolve(keep_pins="--keep-pins" in sys.argv)
        else:
            sys.exit("unknown command")
