#!/usr/bin/env python3
"""Smath 的 Lean 编译服务（只依赖 Python 标准库）。

用途
----
手机上的 Smath 在「设置 → lean 编译 Agent」里填好 **服务地址** 和 **API Key** 之后，
模型写出的 ``[$$lean_start$$] … [$$lean_end$$]`` 代码块会被 POST 到这台电脑，
由本机的 ``lean`` 真正编译一遍，再把 stdout / stderr 回传给手机，
于是模型能看到「自己写的 Lean 到底有没有通过类型检查」。

在手机上怎么填
-------------
* 服务地址：``http://<电脑的局域网 IP>:8788``（启动时本脚本会把地址打印出来）
* API Key：启动时 ``--key`` 传入的那串字符
* 手机和电脑要在同一个 Wi-Fi / 热点下，并且电脑防火墙要放行该端口
  （Windows 首次运行会弹「是否允许 python 访问网络」，选「专用网络」即可）

Lean 从哪来（推荐用 pip 装，本脚本不自己造轮子）
-----------------------------------------------
本脚本**不**自己下载 / 解压 Lean，只负责找一个已经装好的 ``lean`` 可执行文件。
Lean 4 的安装本身交给现成的 Python 包：

1. **lemma-lean（推荐，PyPI 上的 Lean4 工具链管理器）**::

       python -m pip install --user lemma-lean
       lemma toolchain install stable

   lemma 是 elan 的重写版，工具链一般落在 elan 风格的 ``%USERPROFILE%\\.elan\\bin``，
   本脚本会自动去那里找；如果不在那儿，``--install-lean`` 跑完会打印实际找到的路径，
   也可以直接用 ``--lean`` 指过去。

2. **lean-interact**（PyPI 上的 Lean REPL 客户端，自带 ``install-lean`` 命令，
   该命令安装的是 elan 版本管理器）::

       python -m pip install --user lean-interact
       install-lean

3. 自己已经装了 **elan** 或官方 release zip 也行：只要 ``lean --version`` 能跑，
   或者把 ``lean.exe`` 的完整路径用 ``--lean`` 传进来。

一行命令帮你走路线 1（内部就是调 pip 和 lemma，不手写下载逻辑）::

    python tools/lean_server.py --install-lean

注意：**不要** ``pip install lean``。PyPI 上那个 ``lean`` 是 QuantConnect 的量化
交易 CLI，和 Lean 定理证明器完全无关，装了也用不了。

lean 进程的输出去向：``lean`` 把正常的编译信息写到 **stdout**，把
``error:`` / 类型检查失败之类写到 **stderr**，所以本服务两个都原样回传。

接口
----
* ``GET  /health``    —— 不需要鉴权，返回 ``lean_available`` / ``lean_version``。
* ``POST /v1/check``  —— 需要 ``X-API-Key``（或 ``Authorization: Bearer``），
  body ``{"code": "...", "filename": "Main.lean"}``。

启动
----
    python tools/lean_server.py --key 你的密钥
    python tools/lean_server.py --key 你的密钥 --port 8788 --timeout 30
    python tools/lean_server.py --key 你的密钥 --lean C:\\tools\\lean\\bin\\lean.exe
    python tools/lean_server.py --self-test          # 一键自检（会起临时服务跑断言）
    python tools/lean_server.py --install-lean       # 用 pip 装 lean-lean 工具链
"""

import argparse
import hmac
import http.client
import json
import os
import shlex
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = "2.0"
DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 8788
DEFAULT_TIMEOUT = 30
DEFAULT_MAX_BYTES = 200_000
OUTPUT_LIMIT = 20_000
SELF_TEST_CODE = "#check 1 + 1 = 2\n"


def log(message):
    """统一把日志写到 stderr，不污染 stdout。"""
    sys.stderr.write(str(message) + "\n")
    sys.stderr.flush()


def mask_key(key):
    """只暴露前 4 位，避免把密钥打在屏幕上。"""
    key = key or ""
    if not key:
        return "(空)"
    if len(key) <= 4:
        return "****"
    return key[:4] + "****"


# ---------------------------------------------------------------------------
# 找 lean
# ---------------------------------------------------------------------------


def _command_from_text(text):
    """把 ``--lean`` 的值变成命令前缀。

    既支持 ``C:\\tools\\lean\\bin\\lean.exe`` 这样的路径，也支持
    ``python -m some_cli`` 这种整条命令（方便将来换别的 pip 包）。
    """
    raw = (text or "").strip()
    if not raw:
        return None
    if os.path.isfile(raw):
        return [raw]
    direct = shutil.which(raw)
    if direct:
        return [direct]
    try:
        parts = [part for part in shlex.split(raw, posix=False) if part]
    except ValueError:
        return None
    if len(parts) > 1:
        head = parts[0].strip('"')
        if os.path.isfile(head) or shutil.which(head):
            return parts
    return None


def lean_search_dirs():
    """pip 装的工具链管理器会把 lean 放到这些地方，挨个看一眼。"""
    home = os.path.expanduser("~")
    dirs = [
        os.path.join(home, ".elan", "bin"),                     # elan / lemma-lean / install-lean
        os.path.join(home, ".lemma", "bin"),                    # lemma 自己的目录（不同版本略有差异）
        os.path.join(home, ".local", "share", "lemma", "bin"),
        os.path.join(home, ".local", "bin"),
        os.path.join(home, "AppData", "Local", "Programs", "lean"),
        os.path.dirname(sys.executable),
        os.path.join(os.path.dirname(sys.executable), "Scripts"),
        os.path.join(sys.prefix, "Scripts"),
        os.path.join(sys.prefix, "bin"),
    ]
    seen = []
    for item in dirs:
        if item and item not in seen:
            seen.append(item)
    return seen


def resolve_lean(explicit):
    """按「显式参数 → 环境变量 SMATH_LEAN → PATH → pip/elan 常见目录」找 lean。

    返回命令前缀列表（不含源文件名）；找不到返回 ``None``。
    """
    for text in (explicit, os.environ.get("SMATH_LEAN", "")):
        command = _command_from_text(text)
        if command:
            return command

    for name in ("lean", "lean.exe"):
        found = shutil.which(name)
        if found:
            return [found]

    for directory in lean_search_dirs():
        for name in ("lean.exe", "lean"):
            candidate = os.path.join(directory, name)
            if os.path.isfile(candidate):
                return [candidate]
    return None


def lean_version(lean_command):
    """``lean --version`` 的第一行；失败返回空串。"""
    if not lean_command:
        return ""
    try:
        proc = subprocess.run(
            list(lean_command) + ["--version"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=30,
        )
    except Exception:
        return ""
    text = proc.stdout.decode("utf-8", "replace").strip()
    first = text.replace("\r\n", "\n").split("\n")[0][:160]
    # 有的「lean」其实是别的程序，--version 会吐一堆报错；没有版本号就当没认到。
    if not any(ch.isdigit() for ch in first):
        return ""
    return first


def _truncate(text, limit=OUTPUT_LIMIT):
    text = text or ""
    if len(text) <= limit:
        return text
    return text[:limit] + "\n…（输出过长，已截断到 %d 字符）" % limit


# ---------------------------------------------------------------------------
# 编译
# ---------------------------------------------------------------------------


def _safe_filename(filename):
    """只留文件名部分，并且强制 .lean 后缀，避免路径穿越。"""
    name = os.path.basename((filename or "").strip() or "Main.lean")
    name = "".join(ch for ch in name if ch not in '<>:"/\\|?*\0')
    if not name:
        name = "Main.lean"
    if not name.lower().endswith(".lean"):
        name += ".lean"
    return name


def compile_code(lean_command, code, filename, timeout_sec):
    """把代码写进临时目录，调 lean 编译一次，返回归一化的结果 dict。"""
    started = time.time()
    safe_name = _safe_filename(filename)

    if not lean_command:
        return {
            "ok": False,
            "exit_code": -1,
            "stdout": "",
            "stderr": "lean 未安装或路径不对：本机没找到 lean 可执行文件。"
                      "可以先运行 `python tools/lean_server.py --install-lean`，"
                      "或用 --lean 指定 lean 的完整路径。",
            "elapsed_ms": 0,
            "timed_out": False,
            "lean_available": False,
            "filename": safe_name,
        }

    workdir = tempfile.mkdtemp(prefix="smath_lean_")
    try:
        path = os.path.join(workdir, safe_name)
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(code)

        stdout = ""
        stderr = ""
        exit_code = -1
        timed_out = False
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
        except subprocess.TimeoutExpired as exc:
            stdout = (exc.stdout or b"").decode("utf-8", "replace")
            stderr = (exc.stderr or b"").decode("utf-8", "replace")
            stderr += "\n（编译超时：超过 %d 秒仍未结束，已强制终止）" % timeout_sec
            exit_code = -9
            timed_out = True
        except OSError as exc:
            return {
                "ok": False,
                "exit_code": -1,
                "stdout": "",
                "stderr": "lean 未安装或路径不对：%s（%s）" % (" ".join(lean_command), exc),
                "elapsed_ms": int((time.time() - started) * 1000),
                "timed_out": False,
                "lean_available": False,
                "filename": safe_name,
            }

        elapsed = int((time.time() - started) * 1000)
        return {
            "ok": exit_code == 0 and not timed_out,
            "exit_code": exit_code,
            "stdout": _truncate(stdout),
            "stderr": _truncate(stderr),
            "elapsed_ms": elapsed,
            "timed_out": timed_out,
            "lean_available": True,
            "filename": safe_name,
        }
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


# ---------------------------------------------------------------------------
# 网络地址
# ---------------------------------------------------------------------------


def lan_addresses():
    """尽力找出本机局域网 IPv4，方便直接抄进手机。"""
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
            ip = sock.getsockname()[0]
            if ip and ip not in addresses:
                addresses.append(ip)
        except Exception:
            pass
        finally:
            sock.close()
    return addresses


# ---------------------------------------------------------------------------
# HTTP 服务
# ---------------------------------------------------------------------------


class ServerState(object):
    def __init__(self, api_key, lean_command, timeout_sec, max_bytes):
        self.api_key = api_key
        self.lean_command = lean_command
        self.timeout_sec = timeout_sec
        self.max_bytes = max_bytes


def make_handler(state):
    class LeanHandler(BaseHTTPRequestHandler):
        server_version = "SmathLeanServer/" + VERSION
        protocol_version = "HTTP/1.1"

        # --- 日志：只保留 4xx / 5xx，不刷屏 ---------------------------
        def log_message(self, fmt, *args):
            return

        def log_request(self, code="-", size="-"):
            try:
                numeric = int(code)
            except (TypeError, ValueError):
                numeric = 0
            if numeric >= 400:
                log("[%s] %s %s" % (self.address_string(), code, self.requestline))
            return

        # --- 基础工具 -------------------------------------------------
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
            token = (self.headers.get("X-API-Key") or "").strip()
            if not token:
                auth = (self.headers.get("Authorization") or "").strip()
                if auth.lower().startswith("bearer "):
                    token = auth[7:].strip()
            return bool(token) and hmac.compare_digest(token, state.api_key)

        def discard_body(self, length):
            """读掉请求体，否则 keep-alive 连接上残留的字节会被当成下一个请求。"""
            try:
                remaining = int(length)
            except (TypeError, ValueError):
                remaining = 0
            if remaining <= 0:
                return
            if remaining > state.max_bytes:
                self.close_connection = True
                return
            while remaining > 0:
                chunk = self.rfile.read(min(remaining, 65536))
                if not chunk:
                    break
                remaining -= len(chunk)

        def read_json_body(self):
            raw_length = self.headers.get("Content-Length")
            try:
                length = int(raw_length or 0)
            except ValueError:
                self.close_connection = True
                return None, (400, {"ok": False, "error": "invalid json"})
            if length <= 0:
                return None, (400, {"ok": False, "error": "invalid json"})
            if length > state.max_bytes:
                self.discard_body(length)
                return None, (413, {"ok": False, "error": "code too large"})
            raw = self.rfile.read(length)
            try:
                text = raw.decode("utf-8")
            except UnicodeDecodeError:
                return None, (400, {"ok": False, "error": "invalid json"})
            try:
                payload = json.loads(text)
            except json.JSONDecodeError:
                return None, (400, {"ok": False, "error": "invalid json"})
            if not isinstance(payload, dict):
                return None, (400, {"ok": False, "error": "invalid json"})
            return payload, None

        # --- 路由 -----------------------------------------------------
        def do_GET(self):
            path = (self.path or "").split("?")[0].rstrip("/") or "/"
            if path != "/health":
                self.discard_body(self.headers.get("Content-Length") or 0)
                self.send_json(404, {"ok": False, "error": "not found"})
                return
            version = lean_version(state.lean_command)
            self.send_json(
                200,
                {
                    "ok": True,
                    "lean_available": bool(state.lean_command),
                    "lean_version": version or None,
                    "key_required": True,
                    "server": "smath-lean",
                    "version": VERSION,
                    "lean_command": " ".join(state.lean_command) if state.lean_command else "",
                    "timeout_sec": state.timeout_sec,
                },
            )

        def do_POST(self):
            path = (self.path or "").split("?")[0].rstrip("/") or "/"
            length = self.headers.get("Content-Length") or 0
            if path != "/v1/check":
                self.discard_body(length)
                self.send_json(404, {"ok": False, "error": "not found"})
                return
            if not self.authorized():
                self.discard_body(length)
                self.send_json(401, {"ok": False, "error": "unauthorized"})
                return
            payload, error = self.read_json_body()
            if error is not None:
                self.send_json(error[0], error[1])
                return
            code = payload.get("code")
            if not isinstance(code, str) or not code.strip():
                self.send_json(400, {"ok": False, "error": "invalid json"})
                return
            filename = payload.get("filename")
            if not isinstance(filename, str) or not filename.strip():
                filename = "Main.lean"

            result = compile_code(state.lean_command, code, filename, state.timeout_sec)
            log(
                "编译：%d 字符 ok=%s exit=%s %dms"
                % (len(code), result["ok"], result["exit_code"], result["elapsed_ms"])
            )
            self.send_json(200, result)

        # 其余方法一律 405，而不是 BaseHTTPRequestHandler 默认的 501
        def _method_not_allowed(self):
            self.discard_body(self.headers.get("Content-Length") or 0)
            self.send_json(405, {"ok": False, "error": "method not allowed"})

        do_PUT = _method_not_allowed
        do_DELETE = _method_not_allowed
        do_PATCH = _method_not_allowed

        def do_HEAD(self):
            self.send_response(405)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_OPTIONS(self):
            self.send_response(204)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Headers", "Content-Type, X-API-Key, Authorization")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Content-Length", "0")
            self.end_headers()

    return LeanHandler


class LeanHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def start_server(host, port, state):
    httpd = LeanHTTPServer((host, port), make_handler(state))
    thread = threading.Thread(target=httpd.serve_forever, kwargs={"poll_interval": 0.1})
    thread.daemon = True
    thread.start()
    return httpd, thread


# ---------------------------------------------------------------------------
# 用 pip 装 Lean 工具链
# ---------------------------------------------------------------------------


def install_lean():
    """走现成的 PyPI 包：lemma-lean 装工具链。不手写下载/解压逻辑。"""
    log("1/2 用 pip 安装 lemma-lean（PyPI 上的 Lean4 工具链管理器）…")
    proc = subprocess.run(
        [sys.executable, "-m", "pip", "install", "--user", "--upgrade", "lemma-lean"],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=1800,
    )
    log(proc.stdout.decode("utf-8", "replace")[-2000:])
    if proc.returncode != 0:
        log("pip 安装 lemma-lean 失败（退出码 %d）。可以手动执行：" % proc.returncode)
        log("  %s -m pip install --user lemma-lean" % sys.executable)
        return 1

    lemma = None
    for directory in lean_search_dirs():
        for name in ("lemma.exe", "lemma"):
            candidate = os.path.join(directory, name)
            if os.path.isfile(candidate):
                lemma = candidate
                break
        if lemma:
            break
    lemma = lemma or shutil.which("lemma")
    if not lemma:
        log("装完 lemma-lean 但没找到 lemma 命令，请重开一个终端再试。")
        return 1

    log("2/2 用 lemma 安装稳定的 Lean 4 工具链（这一步会下载一两百 MB）…")
    proc = subprocess.run(
        [lemma, "toolchain", "install", "stable"],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=3600,
    )
    log(proc.stdout.decode("utf-8", "replace")[-2000:])
    if proc.returncode != 0:
        log("lemma toolchain install 失败（退出码 %d）。" % proc.returncode)
        return 1

    resolved = resolve_lean("")
    if resolved:
        log("完成：lean 可用 → %s（%s）" % (" ".join(resolved), lean_version(resolved) or "版本未知"))
        return 0
    log("工具链装好了，但没找到 lean 可执行文件；请重开终端，或用 --lean 指定路径。")
    return 1


# ---------------------------------------------------------------------------
# 自检
# ---------------------------------------------------------------------------


def _http_call(host, port, method, path, body=None, headers=None, timeout=10):
    conn = http.client.HTTPConnection(host, port, timeout=timeout)
    try:
        payload = None
        send_headers = dict(headers or {})
        if body is not None:
            payload = body.encode("utf-8")
            send_headers.setdefault("Content-Type", "application/json")
        conn.request(method, path, body=payload, headers=send_headers)
        response = conn.getresponse()
        raw = response.read()
        return response.status, raw.decode("utf-8", "replace")
    finally:
        conn.close()


def self_test():
    """起一个临时服务，把 4~6 条断言跑一遍；再顺手真实编译一小段代码。"""
    key = "selftest-key"
    state = ServerState(
        api_key=key,
        lean_command=None,          # 故意模拟「没装 lean」，断言才稳定
        timeout_sec=DEFAULT_TIMEOUT,
        max_bytes=4096,
    )
    httpd, _thread = start_server("127.0.0.1", 0, state)
    port = httpd.server_address[1]
    failures = []
    results = []

    def check(name, condition, detail=""):
        results.append((name, bool(condition), detail))
        if not condition:
            failures.append(name)

    try:
        status, text = _http_call("127.0.0.1", port, "GET", "/health")
        check("GET /health -> 200 且 lean_available:false",
              status == 200 and json.loads(text).get("lean_available") is False, "HTTP %d %s" % (status, text))

        status, text = _http_call(
            "127.0.0.1", port, "POST", "/v1/check",
            body=json.dumps({"code": SELF_TEST_CODE}),
            headers={"X-API-Key": key},
        )
        data = json.loads(text) if text else {}
        check("POST /v1/check（正确 key）-> 200 且 ok:false, lean_available:false",
              status == 200 and data.get("ok") is False and data.get("lean_available") is False,
              "HTTP %d %s" % (status, text))

        status, text = _http_call(
            "127.0.0.1", port, "POST", "/v1/check",
            body=json.dumps({"code": SELF_TEST_CODE}),
        )
        check("POST /v1/check（不带 key）-> 401 unauthorized",
              status == 401 and json.loads(text).get("error") == "unauthorized",
              "HTTP %d %s" % (status, text))

        status, text = _http_call(
            "127.0.0.1", port, "POST", "/v1/check",
            body="{not json",
            headers={"X-API-Key": key},
        )
        check("POST /v1/check（坏 JSON）-> 400 invalid json",
              status == 400 and json.loads(text).get("error") == "invalid json",
              "HTTP %d %s" % (status, text))

        status, text = _http_call(
            "127.0.0.1", port, "POST", "/v1/check",
            body=json.dumps({"code": "#check 1 + 1 = 2" + " " * 5000}),
            headers={"X-API-Key": key},
        )
        check("POST /v1/check（超大 body）-> 413 code too large",
              status == 413 and json.loads(text).get("error") == "code too large",
              "HTTP %d %s" % (status, text))

        status, text = _http_call("127.0.0.1", port, "PUT", "/v1/check", body="x")
        check("PUT /v1/check -> 405 method not allowed",
              status == 405 and json.loads(text).get("error") == "method not allowed",
              "HTTP %d %s" % (status, text))
    finally:
        httpd.shutdown()
        httpd.server_close()

    print("== HTTP 协议自检 ==")
    total = len(results)
    for index, (name, ok, detail) in enumerate(results, 1):
        print("[%d/%d] %s %s" % (index, total, "PASS" if ok else "FAIL", name))
        if detail:
            print("      %s" % detail)

    # 真实编译（本机真装了 lean 时才做）
    resolved = resolve_lean("")
    print("== 真实编译自检 ==")
    if resolved:
        print("lean：%s" % " ".join(resolved))
        print("版本：%s" % (lean_version(resolved) or "未知"))
        sample = compile_code(resolved, SELF_TEST_CODE, "SelfTest.lean", DEFAULT_TIMEOUT)
        print("elapsed_ms=%d exit_code=%s ok=%s" % (sample["elapsed_ms"], sample["exit_code"], sample["ok"]))
        if sample["stdout"].strip():
            print("stdout：%s" % sample["stdout"].strip()[:500])
        if sample["stderr"].strip():
            print("stderr：%s" % sample["stderr"].strip()[:500])
        check("真实编译 #check 1 + 1 = 2", sample["ok"], "exit_code=%s" % sample["exit_code"])
    else:
        print("本机没找到 lean，跳过（跑 --install-lean 可以用 pip 装一个）。")

    if failures:
        print("自检失败：%d 项 FAIL → %s" % (len(failures), ", ".join(failures)))
        return 1
    print("自检通过：%d 项全部 PASS。" % len(results))
    return 0


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------


def print_banner(state, host, port):
    key = state.api_key
    log("=" * 62)
    log("Smath lean 编译服务 v%s" % VERSION)
    log("API Key : %s（与 --key 一致）" % mask_key(key))
    log("监听    : %s:%d" % (host, port))
    for ip in lan_addresses():
        log("局域网  : http://%s:%d" % (ip, port))
    if state.lean_command:
        log("lean    : %s" % " ".join(state.lean_command))
        log("版本    : %s" % (lean_version(state.lean_command) or "未知"))
    else:
        log("lean    : 未找到（编译请求会返回 lean_available:false）")
        log("          → 用 python tools/lean_server.py --install-lean 通过 pip 装")
    log("超时    : %d 秒；单次代码上限 %d 字节" % (state.timeout_sec, state.max_bytes))
    ip = lan_addresses()
    example_ip = ip[0] if ip else "127.0.0.1"
    log("-" * 62)
    log("示例：")
    log(
        'curl -X POST http://%s:%d/v1/check -H "X-API-Key: %s" '
        '-H "Content-Type: application/json" -d \'{"code":"#check 1 + 1 = 2"}\''
        % (example_ip, port, key)
    )
    log("=" * 62)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Smath 的局域网 Lean 编译服务（只依赖 Python 标准库）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--host", default=DEFAULT_HOST, help="监听地址，默认 0.0.0.0（局域网可访问）")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="监听端口，默认 8788")
    parser.add_argument("--key", default=os.environ.get("SMATH_LEAN_KEY", ""), help="必需的 API Key（也可用环境变量 SMATH_LEAN_KEY）")
    parser.add_argument("--lean", default="", help="lean 可执行文件路径，或「python -m xxx」这种整条命令；默认自动查找")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT, help="单次编译超时秒数，默认 30")
    parser.add_argument("--max-bytes", type=int, default=DEFAULT_MAX_BYTES, help="单次代码最大字节数，默认 200000")
    parser.add_argument("--self-test", action="store_true", help="起临时服务跑协议断言 + 真实编译，退出码 0/1")
    parser.add_argument("--install-lean", action="store_true", help="用 pip 安装 lemma-lean 并装 stable 工具链，然后退出")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    if args.install_lean:
        return install_lean()

    if not (args.key or "").strip():
        parser.print_usage(sys.stderr)
        log("错误：必须用 --key 指定 API Key（手机端要填同一串）。")
        return 2

    state = ServerState(
        api_key=args.key.strip(),
        lean_command=resolve_lean(args.lean),
        timeout_sec=max(1, args.timeout),
        max_bytes=max(1024, args.max_bytes),
    )
    try:
        httpd, _thread = start_server(args.host, args.port, state)
    except OSError as exc:
        log("端口 %d 启动失败：%s" % (args.port, exc))
        return 3

    print_banner(state, args.host, args.port)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        log("正在退出…")
    finally:
        httpd.shutdown()
        httpd.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())