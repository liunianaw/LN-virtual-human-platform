"""Rebuild locked official source and separately prepare/verify locked weights."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path


def git(root, *args, env=None, input=None):
    return subprocess.check_output(["git", "-c", "safe.directory=" + str(Path(root).resolve()), "-C", str(root), *args], text=True, encoding="utf-8", env=env, input=input).strip()


def read_lock(path):
    path = Path(path).resolve(strict=True)
    lock = json.loads(path.read_text(encoding="utf-8"))
    lock["_directory"] = str(path.parent)
    return lock


def verify_source(root, lock):
    root = Path(root).resolve(strict=True)
    if git(root, "remote", "get-url", "origin").removesuffix(".git") != lock["source"]["url"].removesuffix(".git") \
            or git(root, "rev-parse", "HEAD") != lock["source"]["commit"]:
        raise ValueError("Official source does not match source.lock.json")
    patches = lock.get("upstreamPatches", [])
    expected = {entry["path"]: entry["sha256"] for patch in patches for entry in patch["files"]}
    if set(git(root, "diff", "--name-only", "HEAD").splitlines()) != set(expected) \
            or git(root, "ls-files", "--others", "--exclude-standard"):
        raise ValueError("Unrecorded changes in official source")
    for path, digest in expected.items():
        if hashlib.sha256((root / path).read_text(encoding="utf-8").encode()).hexdigest() != digest:
            raise ValueError("Official source patch content does not match lock")
    for item in lock["source"]["submodules"]:
        child = root / item["path"]
        if git(child, "rev-parse", "HEAD") != item["commit"] or git(child, "status", "--porcelain", "--untracked-files=normal"):
            raise ValueError("Official submodule does not match source.lock.json")
    return root


def patch_source(root, lock):
    verify_source(root, dict(lock, upstreamPatches=[]))
    for entry in lock.get("upstreamPatches", []):
        path = Path(lock["_directory"]) / entry["path"]
        patch = path.read_text(encoding="utf-8")
        if hashlib.sha256(patch.encode()).hexdigest() != entry["sha256"]:
            raise ValueError("Upstream patch does not match lock")
        git(root, "apply", "--unidiff-zero", "--check", input=patch)
        git(root, "apply", "--unidiff-zero", input=patch)
    verify_source(root, lock)


def verify_weights(root, lock):
    root = Path(root).resolve(strict=True)
    for entry in lock["weights"]["files"]:
        path = (root / entry["path"]).resolve(strict=True)
        if not path.is_relative_to(root):
            raise ValueError("Weight path outside model directory")
        digest = hashlib.new(entry["algorithm"])
        if entry["algorithm"] == "sha1":
            digest.update(f"blob {path.stat().st_size}\0".encode())
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1048576), b""):
                digest.update(chunk)
        if digest.hexdigest() != entry["hash"]:
            raise ValueError("Weight content does not match source.lock.json")
    return root


def verify_resources(root, lock):
    return verify_weights(root, {"weights": lock})


def fetch_resources(destination, lock):
    root = Path(destination).resolve()
    root.mkdir(parents=True, exist_ok=True)
    existing = [entry for entry in lock["files"] if (root / entry["path"]).exists()]
    verify_resources(root, dict(lock, files=existing))
    missing = [entry for entry in lock["files"] if entry not in existing]
    if missing:
        # Git LFS preparation is explicit; runtime uses only the verified directory.
        with tempfile.TemporaryDirectory(prefix="ln-wetext-") as temporary:
            env = dict(os.environ, GIT_LFS_SKIP_SMUDGE="1")
            git(temporary, "init")
            git(temporary, "remote", "add", "origin", lock["sourceUrl"])
            git(temporary, "fetch", "--depth", "1", "origin", lock["revision"], env=env)
            git(temporary, "checkout", "--detach", "FETCH_HEAD", env=env)
            paths = [entry["path"] for entry in missing]
            git(temporary, "lfs", "fetch", "--include=" + ",".join(paths), "origin", lock["revision"])
            git(temporary, "lfs", "checkout", *paths)
            verify_resources(temporary, dict(lock, files=missing))
            for entry in missing:
                path = root / entry["path"]
                path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(Path(temporary) / entry["path"], path)
    verify_resources(root, lock)


def fetch_source(destination, lock):
    root = Path(destination).resolve()
    if not root.exists():
        root.mkdir(parents=True)
        git(root, "init")
        git(root, "remote", "add", "origin", lock["source"]["url"])
        git(root, "fetch", "--depth", "1", "origin", lock["source"]["commit"])
        git(root, "checkout", "--detach", "FETCH_HEAD")
        if lock["source"]["submodules"]:
            git(root, "submodule", "update", "--init", "--recursive")
        patch_source(root, lock)
    # Existing paths are only inspected. Never reset or overwrite someone else's checkout.
    verify_source(root, lock)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("source", "patch-source", "weights", "resources", "verify-source", "verify-weights", "verify-resources"))
    parser.add_argument("lock")
    parser.add_argument("destination")
    args = parser.parse_args()
    lock = read_lock(args.lock)
    if args.command == "source":
        fetch_source(args.destination, lock)
    elif args.command == "patch-source":
        patch_source(args.destination, lock)
    elif args.command == "resources":
        fetch_resources(args.destination, lock)
    elif args.command == "verify-resources":
        verify_resources(args.destination, lock)
    elif args.command == "weights":
        from huggingface_hub import hf_hub_download
        for entry in lock["weights"]["files"]:
            hf_hub_download(repo_id=lock["weights"]["repo"], revision=lock["weights"]["revision"],
                            filename=entry["path"], local_dir=args.destination)
        verify_weights(args.destination, lock)
    elif args.command == "verify-source":
        verify_source(args.destination, lock)
    else:
        verify_weights(args.destination, lock)
    print("Locked inputs verified")


if __name__ == "__main__":
    main()
