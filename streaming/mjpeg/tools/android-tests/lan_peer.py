#!/usr/bin/env python3
"""Run the library LAN test with a real external HTTP peer (Python standard library only).

Emulator console redirection qualifies an emulated external route to guest Ethernet,
not physical Wi-Fi, adb forwarding, or independent kernel permission enforcement.
Direct routing also remains physically unqualified without independent topology evidence.
The test must start with ACCESS_LOCAL_NETWORK denied. This runner restores that denial
only after instrumentation ends; revoking it during instrumentation can kill the test.
APKs must already be installed. This tool does not build, install, or launch emulators.
"""

import argparse
import hashlib
import http.client
import ipaddress
import json
import pathlib
import queue
import re
import shlex
import socket
import subprocess
import sys
import threading
import time
import uuid
from email.message import Message

PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
TEST_CLASS = "io.screenstream.mjpeg.device.MjpegLanPermissionTest"
MAX_JPEG = 8 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise AssertionError(message)


class EmulatorConsole:
    def __init__(self, port):
        self.socket = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.stream = None
        try:
            self.stream = self.socket.makefile("rwb", buffering=0)
            greeting = self._response()
            if "Authentication required" in greeting:
                token = (pathlib.Path.home() / ".emulator_console_auth_token").read_text().strip()
                require(token and "\n" not in token and "\r" not in token, "Invalid emulator console token")
                self.command("auth " + token)
        except BaseException:
            self.close()
            raise

    def _response(self):
        lines = []
        for _ in range(100):
            line = self.stream.readline(4097)
            require(line and len(line) <= 4096, "Invalid emulator console response")
            text = line.decode("utf-8", errors="replace").strip()
            lines.append(text)
            if text == "OK":
                return "\n".join(lines)
            if text.startswith("KO"):
                raise RuntimeError("Emulator console rejected redirection/authentication")
        raise RuntimeError("Unterminated emulator console response")

    def command(self, command):
        self.stream.write((command + "\n").encode("utf-8"))
        return self._response()

    def close(self):
        try:
            if self.stream is not None:
                self.stream.close()
        finally:
            self.socket.close()


def read_line(stream, limit=4096):
    line = stream.readline(limit + 1)
    require(line.endswith(b"\r\n") and len(line) <= limit, "Invalid HTTP/multipart header line")
    return line[:-2]


def check_jpeg(body, request):
    require(len(body) == request["expectedBytes"], "JPEG length differs from the production placeholder")
    require(body.startswith(b"\xff\xd8") and body.endswith(b"\xff\xd9"), "Body is not a complete JPEG fixture")
    digest = hashlib.sha256(body).hexdigest()
    require(digest == request["expectedSha256"], "JPEG differs from the production placeholder")
    return digest


def fetch_jpeg(host, port, request):
    connection = http.client.HTTPConnection(host, port, timeout=5)
    try:
        connection.request("GET", "/jpeg", headers={"Host": f"{request['deviceAddress']}:{request['devicePort']}"})
        response = connection.getresponse()
        require(response.status == 200, "External /jpeg did not return 200")
        require(response.getheader("Content-Type", "").split(";", 1)[0].strip() == "image/jpeg", "Wrong /jpeg content type")
        require(response.getheader("Content-Length") == str(request["expectedBytes"]), "Wrong /jpeg Content-Length")
        body = response.read(MAX_JPEG + 1)
        require(len(body) <= MAX_JPEG, "Oversized /jpeg response")
        return len(body), check_jpeg(body, request)
    finally:
        connection.close()


def fetch_multipart(host, port, request):
    connection = http.client.HTTPConnection(host, port, timeout=5)
    try:
        connection.request("GET", "/mjpeg", headers={"Host": f"{request['deviceAddress']}:{request['devicePort']}"})
        response = connection.getresponse()
        require(response.status == 200, "External /mjpeg did not return 200")
        content_type = Message()
        content_type["Content-Type"] = response.getheader("Content-Type", "")
        require(content_type.get_content_type() == "multipart/x-mixed-replace", "Wrong multipart content type")
        boundary = content_type.get_param("boundary")
        require(isinstance(boundary, str) and 0 < len(boundary) <= 200, "Missing multipart boundary")
        boundary_bytes = boundary.encode("ascii")
        require(b"\r" not in boundary_bytes and b"\n" not in boundary_bytes, "Invalid boundary")
        line = read_line(response)
        if not line:
            line = read_line(response)
        require(line == b"--" + boundary_bytes, "Multipart boundary differs from its header")
        headers = {}
        total_header_bytes = 0
        while True:
            line = read_line(response)
            total_header_bytes += len(line) + 2
            require(total_header_bytes <= 16_384, "Oversized multipart headers")
            if not line:
                break
            name, separator, value = line.partition(b":")
            require(separator, "Malformed multipart header")
            key = name.decode("ascii").lower()
            require(key not in headers, "Duplicate multipart header")
            headers[key] = value.strip().decode("ascii")
        require(headers.get("content-type") == "image/jpeg", "Wrong multipart image type")
        require(headers.get("content-length") == str(request["expectedBytes"]), "Wrong multipart Content-Length")
        body = response.read(request["expectedBytes"])
        require(response.read(2) == b"\r\n", "Missing multipart payload terminator")
        return len(body), check_jpeg(body, request)
    finally:
        connection.close()


def probe_denied(host, port, request):
    connection = http.client.HTTPConnection(host, port, timeout=3)
    try:
        connection.request("GET", "/jpeg", headers={"Host": f"{request['deviceAddress']}:{request['devicePort']}"})
        response = connection.getresponse()
        raise AssertionError(f"Denied LAN endpoint answered HTTP {response.status}")
    except (ConnectionError, TimeoutError, http.client.RemoteDisconnected) as failure:
        return type(failure).__name__
    finally:
        connection.close()


def run(args):
    require(re.fullmatch(r"[A-Za-z0-9_.]+/androidx\.test\.runner\.AndroidJUnitRunner", args.component), "Invalid instrumentation component")
    package = args.component.split("/", 1)[0]
    require(package.startswith("io.screenstream.mjpeg.") and package.endswith("test"), "Use only the library test APK")
    if args.topology == "emulator-console":
        require(args.console_port is not None and args.serial == f"emulator-{args.console_port}", "Console port must identify the selected emulator")
        require(args.interface == "eth0", "Console redirection targets guest Ethernet, not wlan0")
    else:
        require(args.console_port is None, "Direct topology must not use console redirection")
    run_id = uuid.uuid4().hex
    output = args.output / run_id
    output.mkdir(parents=True)
    adb = [args.adb, "-s", args.serial]
    command = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS,
                     "-e", "lanRunId", run_id, "-e", "lanTopology", args.topology,
                     "-e", "lanInterface", args.interface, args.component]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    lines = queue.Queue()

    def receive():
        try:
            for line in process.stdout:
                lines.put(line)
        finally:
            lines.put(None)

    threading.Thread(target=receive, daemon=True).start()
    console = None
    host_port = None
    redirection_added = False
    endpoint = None
    denied_seen = False
    completed = []
    status = {}
    class_passed = False
    runner_finished = False
    successful = False
    deadline = time.monotonic() + 180
    try:
        with (output / "instrumentation.log").open("w") as log:
            while True:
                require(time.monotonic() < deadline, "Instrumentation/peer coordination timed out")
                try:
                    line = lines.get(timeout=1)
                except queue.Empty:
                    continue
                if line is None:
                    break
                log.write(line)
                log.flush()
                print(line, end="", flush=True)
                if line.strip() == "INSTRUMENTATION_CODE: -1":
                    runner_finished = True
                if line.startswith("INSTRUMENTATION_STATUS_CODE:"):
                    code = int(line.split(":", 1)[1])
                    if status.get("class") == TEST_CLASS and status.get("test"):
                        require(code >= 0, "The LAN instrumentation test failed or was skipped")
                        if code == 0:
                            class_passed = True
                    status.clear()
                elif line.startswith("INSTRUMENTATION_STATUS: "):
                    key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].strip().partition("=")
                    if separator:
                        status[key] = value
                prefix = "INSTRUMENTATION_STATUS: mjpegLanPeer="
                if not line.startswith(prefix):
                    continue
                request = json.loads(line[len(prefix):])
                phase = request["phase"]
                require(request["version"] == 1 and request["runId"] == run_id, "Stale peer request")
                require(request["packageName"] == package and request["topology"] == args.topology, "Unexpected package/topology")
                require(phase == ("denied" if not completed else "granted") and len(completed) < 2, "Unexpected peer phase order")
                require(request["interfaceName"] == args.interface, "Unexpected native interface")
                address = ipaddress.ip_address(request["deviceAddress"])
                require(address.version == 4 and address.is_private and not address.is_loopback and not address.is_link_local, "Peer must target a real nonloopback IPv4 interface")
                require(1 <= request["devicePort"] <= 65535 and 0 < request["expectedBytes"] <= MAX_JPEG, "Invalid endpoint/fixture size")
                require(re.fullmatch(r"[a-f0-9]{64}", request["expectedSha256"]), "Invalid fixture digest")
                receipt_path = f"cache/mjpeg-lan-peer-{run_id}/{phase}.json"
                require(request["receiptPath"] == receipt_path, "Unexpected private receipt path")
                current_endpoint = (str(address), request["devicePort"], request["expectedSha256"], request["expectedBytes"])
                if phase == "denied":
                    endpoint = current_endpoint
                    denied_seen = True
                    if args.topology == "emulator-console":
                        require(str(address) == "10.0.2.15", "Console route only qualifies the discovered 10.0.2.15 Ethernet endpoint")
                        console = EmulatorConsole(args.console_port)
                        with socket.socket() as reservation:
                            reservation.bind(("127.0.0.1", 0))
                            host_port = reservation.getsockname()[1]
                        console.command(f"redir add tcp:{host_port}:{request['devicePort']}")
                        redirection_added = True
                    else:
                        host_port = request["devicePort"]
                else:
                    require(current_endpoint == endpoint, "Endpoint/fixture changed between denied and granted phases")
                host = "127.0.0.1" if console else str(address)
                result = {key: request[key] for key in ("version", "runId", "phase", "topology", "deviceAddress", "devicePort")}
                result.update(hostAddress=host, hostPort=host_port, interfaceName=args.interface,
                              physicalLan=False, probePerformed=False, passed=False)
                try:
                    result["probePerformed"] = True
                    if phase == "denied":
                        result.update(result="unreachable", failure=probe_denied(host, host_port, request))
                    else:
                        jpeg_bytes, jpeg_sha = fetch_jpeg(host, host_port, request)
                        part_bytes, part_sha = fetch_multipart(host, host_port, request)
                        result.update(jpegBytes=jpeg_bytes, jpegSha256=jpeg_sha,
                                      multipartBytes=part_bytes, multipartSha256=part_sha)
                    result["passed"] = True
                except Exception as failure:
                    result["error"] = f"{type(failure).__name__}: {failure}"
                (output / f"{phase}-request.json").write_text(json.dumps(request, indent=2) + "\n")
                encoded = json.dumps(result, separators=(",", ":")) + "\n"
                (output / f"{phase}-peer.json").write_text(json.dumps(result, indent=2) + "\n")
                temporary = receipt_path + ".tmp"
                remote = f"cat > {shlex.quote(temporary)} && mv {shlex.quote(temporary)} {shlex.quote(receipt_path)}"
                subprocess.run(adb + ["shell", "run-as", package, "sh", "-c", shlex.quote(remote)], input=encoded, text=True, check=True, timeout=10)
                require(result["passed"], result.get("error", "Peer assertion failed"))
                completed.append(phase)
        process.wait(timeout=10)
        require(process.returncode == 0 and runner_finished and class_passed and completed == ["denied", "granted"], "LAN instrumentation did not complete both peer phases successfully")
        summary = {"runId": run_id, "topology": args.topology, "physicalLan": False,
                   "result": "module deny/grant gating and external JPEG/multipart reachability; no independent kernel-blocking claim"}
        successful = True
    finally:
        original_failure = sys.exc_info()[1]
        cleanup_errors = []
        try:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            if not successful:
                subprocess.run(adb + ["shell", "am", "force-stop", package], check=True, timeout=10)
        except Exception as failure:
            cleanup_errors.append(f"test process: {failure}")
        try:
            if console is not None and redirection_added:
                console.command(f"redir del tcp:{host_port}")
        except Exception as failure:
            cleanup_errors.append(f"console redirection: {failure}")
        try:
            if console is not None:
                console.close()
        except Exception as failure:
            cleanup_errors.append(f"console close: {failure}")
        try:
            if denied_seen:
                subprocess.run(adb + ["shell", "pm", "revoke", package, PERMISSION], check=True, timeout=10)
        except Exception as failure:
            cleanup_errors.append(f"LAN grant restoration: {failure}")
        (output / "cleanup.json").write_text(json.dumps({"errors": cleanup_errors, "initialDeniedRestorationRequested": denied_seen}, indent=2) + "\n")
        if cleanup_errors:
            message = "Cleanup failed: " + "; ".join(cleanup_errors)
            if original_failure is not None:
                print(message, file=sys.stderr)
            else:
                raise RuntimeError(message)
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(f"LAN peer evidence: {output}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--component", required=True)
    parser.add_argument("--topology", choices=("emulator-console", "direct"), required=True)
    parser.add_argument("--interface", required=True)
    parser.add_argument("--console-port", type=int)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", type=pathlib.Path, required=True)
    run(parser.parse_args())


if __name__ == "__main__":
    main()
