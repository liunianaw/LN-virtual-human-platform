"""Rebuild locked official source and separately prepare/verify locked weights."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path


def git(root, *args):
    return subprocess.check_output(["git", "-c", "safe.directory=" + str(Path(root).resolve()), "-C", str(root), *args], text=True).strip()


def verify_source(root, lock):
    root = Path(root).resolve(strict=True)
    if git(root, "remote", "get-url", "origin").removesuffix(".git") != lock["source"]["url"].removesuffix(".git") \
            or git(root, "rev-parse", "HEAD") != lock["source"]["commit"] \
            or git(root, "status", "--porcelain", "--untracked-files=normal"):
        raise ValueError("Official source does not match source.lock.json")
    for item in lock["source"]["submodules"]:
        child = root / item["path"]
        if git(child, "rev-parse", "HEAD") != item["commit"] or git(child, "status", "--porcelain", "--untracked-files=normal"):
            raise ValueError("Official submodule does not match source.lock.json")
    return root


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
    # Existing paths are only inspected. Never reset or overwrite someone else's checkout.
    verify_source(root, lock)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("source", "weights", "verify-source", "verify-weights"))
    parser.add_argument("lock")
    parser.add_argument("destination")
    args = parser.parse_args()
    lock = json.loads(Path(args.lock).read_text(encoding="utf-8"))
    if args.command == "source":
        fetch_source(args.destination, lock)
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
