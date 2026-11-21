#!/usr/bin/env python
"""Create a GitHub release and upload the APK asset (stdlib only).

Usage: python tools/gh_release.py <owner/repo> <tag> <name> <notes_file> <asset_path>
Reads the token from the GH_TOKEN environment variable (never hardcoded).
Prints only non-secret result lines.
"""
import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"


def token() -> str:
    tok = os.environ.get("GH_TOKEN", "").strip()
    if not tok:
        sys.exit("GH_TOKEN is not set")
    return tok


def call(url: str, method: str = "GET", data: bytes | None = None,
         content_type: str | None = None, accept: str = "application/vnd.github+json"):
    headers = {
        "Authorization": "Bearer " + token(),
        "Accept": accept,
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "smath-release-agent",
    }
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            body = resp.read()
            return resp.status, (json.loads(body) if body else None)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")
        return exc.code, body


def main() -> int:
    if len(sys.argv) != 6:
        sys.exit(__doc__)
    repo, tag, name, notes_file, asset_path = sys.argv[1:6]
    with open(notes_file, encoding="utf-8") as fh:
        notes = fh.read()

    payload = json.dumps({
        "tag_name": tag,
        "target_commitish": "main",
        "name": name,
        "body": notes,
        "draft": False,
        "prerelease": False,
    }).encode("utf-8")

    status, rel = call(f"{API}/repos/{repo}/releases", "POST", payload,
                       "application/json; charset=utf-8")
    if status == 422:
        # Release for this tag already exists: reuse it.
        status2, found = call(f"{API}/repos/{repo}/releases/tags/{tag}")
        if status2 != 200:
            print(f"release create failed: {status} {rel}")
            return 1
        rel = found
        print(f"release already existed (id={rel['id']})")
    elif status >= 300:
        print(f"release create failed: {status} {rel}")
        return 1
    else:
        print(f"release created id={rel['id']} tag={rel['tag_name']} draft={rel['draft']}")

    upload_url = rel["upload_url"].split("{")[0]
    asset_name = os.path.basename(asset_path)
    existing = {a["name"] for a in rel.get("assets", [])}
    if asset_name in existing:
        print(f"asset already present: {asset_name}")
    else:
        with open(asset_path, "rb") as fh:
            blob = fh.read()
        status, asset = call(f"{upload_url}?name={asset_name}", "POST", blob,
                             "application/octet-stream")
        if status >= 300:
            print(f"asset upload failed: {status} {asset}")
            return 1
        print(f"asset uploaded name={asset['name']} size={asset['size']} state={asset['state']}")

    status, final = call(f"{API}/repos/{repo}/releases/{rel['id']}")
    if status == 200:
        print(f"html_url={final['html_url']}")
        print(f"published={final['published_at']} draft={final['draft']}")
        for a in final.get("assets", []):
            print(f"download_url={a['browser_download_url']}")
            print(f"asset_size={a['size']} content_type={a['content_type']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())