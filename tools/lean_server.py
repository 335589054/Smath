#!/usr/bin/env python3
"""Smath 的 Lean 编译服务（只依赖 Python 标准库）。

用途
----
手机上的 Smath 在「设置 → lean 编译 Agent」里填好 **服务地址** 和 **API Key** 之后，
模型写出的 ``[$$lean_start$$] … [$$lean_end$$]`` 代码块会被 POST 到这台电脑，
由本机的 ``lean`` 真正编译一遍，再把 stdout / stderr 回传给手机，
于是模型能看到「自己写的 Lean 到底有没有通过类型检查」。

在手机上怎么填
--------------
* 服务地址：``http://<电脑的局域网 IP>:8788``（启动时本脚本会把地址打印出来）
* API Key：启动时 ``--key`` 传入的那串字符
* 手机和电脑要在同一个 Wi-Fi / 热点下，并且电脑防火墙要放行该端口
  （Windows 首次运行会弹「是否允许 python 访问网络」，选「专用网络」即可）

Windows 上怎么装 Lean
--------------------
三条路，任选其一，装完后 `python tools/lean_server.py --self-test` 能打印
`"lean_available": true` 就说明认到了：

1. **pip**（最省事）：`pip install lean`，装完一般会出现 `lean` 命令；如果只有模块，
   启动本服务时用 `--lean "python -m lean"` 这种整条命令的形式。
2. **elan**（https://github.com/leanprover/elan）：装好后重开终端，`lean --version`
   能打印版本号即可，默认位置 `%USERPROFILE%\.elan\bin\lean.exe`。
3. **官方 zip**：从 https://github.com/leanprover/lean4/releases 下载解压，把
   `lean.exe` 的完整路径写给 `--lean`。

本服务只做「单文件编译检查」，不负责 `lake` / mathlib 依赖。

启动
----
    python tools/lean_server.py --key 你的密钥
    python tools/lean_server.py --key 你的密钥 --port 8788 --lean C:\\tools\\lean\\bin\\lean.exe
    python tools/lean_server.py --self-test      # 只检查本机 lean 是否可用
"""

import argparse
import hmac
import json
import os
import shlex
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = "1.1"
MAX_BODY_BYTES = 200_000
DEFAULT_PORT = 8788

SELF_TEST_CODE = """theorem smath_self_test : 1 + 1 = 2 := rfl
"""


def log(message):
    """统一打日志到 stderr，避免污染 stdout。"""
    sys.stderr.write(message + "\n")
    sys.stderr.flush()


def _readable(command):
    return " ".join(command) if command else ""


def _command_from_text(text):
    """把 `--lean` 的值解析成命令前缀：既支持可执行文件路径，也支持
    「python -m lean」这种整条命令（pip 装的 lean 常常是这样用）。"""
    raw = (text or "").strip()
    if not raw:
        return None
    if os.path.isfile(raw):
        return [raw]
    direct = shutil.which(raw)
    if direct:
        return [direct]
    parts = [part for part in shlex.split(raw, posix=False) if part]
    if len(parts) > 1:
        head = parts[0]
        if os.path.isfile(head) or shutil.which(head):
            return parts
    return None


def _module_available(module_name):
    """检查某个 Python 模块能不能导入（pip 安装的 lean 常常只能这么调用）。"""
    try:
        proc = subprocess.run(
            [sys.executable, "-c", "import %s" % module_name],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=20,
        )
        return proc.returncode == 0
    except Exception:
        return False


def resolve_lean(explicit):
    """按「显式参数 → 环境变量 → PATH → elan / pip 脚本目录 → python -m lean」
    的顺序找 lean 的启动命令，返回命令前缀列表（不含源文件名）。"""
    for text in (explicit, os.environ.get("SMATH_LEAN", "")):
        command = _command_from_text(text)
        if command:
            return command

    for name in ("lean", "lean.exe"):
        found = shutil.which(name)
        if found:
            return [found]

    home = os.path.expanduser("~")
    directories = [
        os.path.join(home, ".elan", "bin"),
        os.path.dirname(sys.executable),
        os.path.join(os.path.dirname(sys.executable), "Scripts"),
        os.path.join(sys.prefix, "Scripts"),
        os.path.join(sys.prefix, "bin"),
    ]
    for directory in directories:
        for name in ("lean.exe", "lean"):
            candidate = os.path.join(directory, name)
            if os.path.isfile(candidate):
                return [candidate]

    # pip 安装的 lean 多数没有独立可执行文件，只能用模块方式启动。
    for module_name in ("lean", "lean4", "lean4client"):
        if _module_available(module_name):
            return [sys.executable, "-m", module_name]

    return None


def lean_version(lean_command):
    if not lean_command:
        return ""
    try:
        proc = subprocess.run(
            list(lean_command) + ["--version"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=30,
        )
        text = proc.stdout.decode("utf-8", "replace").strip()
        return text.replace("\n", " ")[:120]
    except Exception:
        return ""


def run_lean(lean_command, code, filename, timeout_sec):
    """把代码写进临时目录再编译，返回 (payload, http_status)。"""
    started = time.time()
    if not lean_command:
        return (
            {
                "ok": False,
                "exit_code": -1,
                "stdout": "",
                "stderr": "电脑上没有找到 lean，请用 --lean 指定路径，或先 pip install 再重启本脚本",
                "elapsed_ms": 0,
                "timed_out": False,
                "lean_available": False,
                "filename": filename,
            },
            200,
        )

    safe_name = os.path.basename(filename or "Main.lean")
    if not safe_name.endswith(".lean"):
        safe_name += ".lean"

    workdir = tempfile.mkdtemp(prefix="smath_lean_")
    path = os.path.join(workdir, safe_name)
    try:
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(code)

        try:
            proc = subprocess.run(
                list(lean_command) + [safe_name],
                cwd=workdir,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=timeout_sec,
            )
            stdout = proc.stdout.decode("utf-8", "replace")
            stderr = proc.stderr.decode("utf-8", "replace")
            exit_code = proc.returncode
            timed_out = False
        except subprocess.TimeoutExpired as exc:
            stdout = (exc.stdout or b"").decode("utf-8", "replace")
            stderr = (exc.stderr or b"").decode("utf-8", "replace")
            stderr += "\n编译超过 %d 秒被中止" % timeout_sec
            exit_code = -9
            timed_out = True

        elapsed = int((time.time() - started) * 1000)
        return (
            {
                "ok": exit_code == 0 and not timed_out,
                "exit_code": exit_code,
                "stdout": stdout,
                "stderr": stderr,
                "elapsed_ms": elapsed,
                "timed_out": timed_out,
                "lean_available": True,
                "filename": safe_name,
            },
            200,
        )
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


def lan_addresses():
    """尽力找出本机局域网地址，方便用户直接抄进手机。"""
    addresses = []
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ip = info[4][0]
            if ip and not ip.startswith("127.") and ip not in addresses:
                addresses.append(ip)
    except Exception:
        pass
    if not addresses:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.connect(("8.8.8.8", 80))
            addresses.append(sock.getsockname()[0])
        except Exception:
            pass
        finally:
            sock.close()
    return addresses


def make_handler(server_key, lean_command, timeout_sec):
    class LeanHandler(BaseHTTPRequestHandler):
        server_version = "SmathLeanServer/" + VERSION
        protocol_version = "HTTP/1.1"

        # --- 基础工具 ---------------------------------------------------
        def log_message(self, fmt, *args):
            log("[%s] %s" % (self.address_string(), fmt % args))

        def send_json(self, status, payload):
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def authorized(self):
            token = self.headers.get("X-API-Key", "").strip()
            if not token:
                auth = self.headers.get("Authorization", "").strip()
                if auth.lower().startswith("bearer "):
                    token = auth[7:].strip()
            return bool(token) and hmac.compare_digest(token, server_key)

        def read_body(self):
            try:
                length = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                return None, (400, {"ok": False, "error": "Content-Length 不合法"})
            if length <= 0:
                return None, (400, {"ok": False, "error": "请求体为空"})
            if length > MAX_BODY_BYTES:
                return None, (413, {"ok": False, "error": "代码太长（上限 200 KB）"})
            raw = self.rfile.read(length)
            try:
                return raw.decode("utf-8"), None
            except UnicodeDecodeError:
                return None, (400, {"ok": False, "error": "请求体不是合法的 UTF-8"})

        # --- 路由 -------------------------------------------------------
        def do_GET(self):
            if self.path.split("?")[0] != "/health":
                self.send_json(404, {"ok": False, "error": "未知路径"})
                return
            if not self.authorized():
                self.send_json(401, {"ok": False, "error": "API Key 不正确"})
                return
            self.send_json(
                200,
                {
                    "ok": True,
                    "server": "smath-lean",
                    "version": VERSION,
                    "lean_available": bool(lean_command),
                    "lean_version": lean_version(lean_command) or "未安装",
                    "lean_command": _readable(lean_command),
                    "timeout_sec": timeout_sec,
                },
            )

        def do_POST(self):
            if self.path.split("?")[0] != "/v1/check":
                self.send_json(404, {"ok": False, "error": "未知路径"})
                return
            if not self.authorized():
                self.send_json(401, {"ok": False, "error": "API Key 不正确"})
                return
            body, error = self.read_body()
            if error is not None:
                self.send_json(error[0], error[1])
                return
            try:
                payload = json.loads(body)
            except json.JSONDecodeError:
                self.send_json(400, {"ok": False, "error": "请求体不是合法 JSON"})
                return
            if not isinstance(payload, dict):
                self.send_json(400, {"ok": False, "error": "请求体必须是 JSON 对象"})
                return
            code = payload.get("code")
            if not isinstance(code, str) or not code.strip():
                self.send_json(400, {"ok": False, "error": "缺少 code 字段"})
                return
            filename = payload.get("filename") or "Main.lean"
            if not isinstance(filename, str):
                filename = "Main.lean"

            log("编译请求：%d 字符，文件名 %s" % (len(code), filename))
            result, status = run_lean(lean_command, code, filename, timeout_sec)
            log(
                "编译结果：ok=%s exit=%s %dms"
                % (result["ok"], result["exit_code"], result["elapsed_ms"])
            )
            self.send_json(status, result)

        def do_OPTIONS(self):
            self.send_response(204)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Headers", "Content-Type, X-API-Key, Authorization")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Content-Length", "0")
            self.end_headers()

    return LeanHandler


def self_test(lean_command, timeout_sec):
    version = lean_version(lean_command)
    result, _ = run_lean(lean_command, SELF_TEST_CODE, "SelfTest.lean", timeout_sec)
    report = {
        "lean_command": _readable(lean_command) or "(未找到)",
        "lean_version": version or "(不可用)",
        "lean_available": bool(lean_command),
        "sample_ok": result["ok"],
        "sample_exit_code": result["exit_code"],
        "sample_stderr": result["stderr"][:1200],
        "elapsed_ms": result["elapsed_ms"],
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Smath 的局域网 Lean 编译服务",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--host", default="0.0.0.0", help="监听地址，默认 0.0.0.0（局域网可访问）")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="监听端口，默认 8788")
    parser.add_argument("--key", default=os.environ.get("SMATH_LEAN_KEY", ""), help="调用所需的 API Key")
    parser.add_argument("--lean", default="", help="lean 命令，可以是可执行文件路径，也可以是「python -m lean」这种整条命令")
    parser.add_argument("--timeout", type=int, default=40, help="单次编译超时秒数，默认 40")
    parser.add_argument("--self-test", action="store_true", help="只检查本机 lean 是否可用")
    args = parser.parse_args(argv)

    lean_command = resolve_lean(args.lean)

    if args.self_test:
        return self_test(lean_command, args.timeout)

    if not args.key.strip():
        log("错误：必须用 --key 指定 API Key（或用环境变量 SMATH_LEAN_KEY）。")
        log("手机端的「API Key」要和这里填的一模一样。")
        return 2

    handler = make_handler(args.key.strip(), lean_command, args.timeout)
    try:
        httpd = ThreadingHTTPServer((args.host, args.port), handler)
    except OSError as exc:
        log("端口 %d 启动失败：%s" % (args.port, exc))
        return 3

    log("Smath lean 编译服务已启动（v%s）" % VERSION)
    log("lean：%s" % (_readable(lean_command) or "未找到（编译请求会返回不可用）"))
    if lean_command:
        log("版本：%s" % (lean_version(lean_command) or "未知"))
    for ip in lan_addresses():
        log("手机端填：http://%s:%d" % (ip, args.port))
    log("API Key：与启动参数 --key 一致")
    log("按 Ctrl+C 停止。")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        log("正在退出…")
    finally:
        httpd.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())