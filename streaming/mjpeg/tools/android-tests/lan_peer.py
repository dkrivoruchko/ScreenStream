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

LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
LAN_TEST_CLASS = "io.screenstream.mjpeg.device.MjpegLanPermissionTest"
MAX_JPEG_BYTES = 8 * 1024 * 1024
PROBE_PHASE_TIMEOUT_SECONDS = 30
CONSOLE_RESPONSE_TIMEOUT_SECONDS = 5
CONSOLE_CLEANUP_TIMEOUT_SECONDS = 5


def require(condition, message):
    if not condition:
        raise AssertionError(message)


class EmulatorConsole:
    def __init__(self, port, deadline):
        connect_deadline = min(deadline, time.monotonic() + CONSOLE_RESPONSE_TIMEOUT_SECONDS)
        self.socket = socket.create_connection(("127.0.0.1", port), timeout=self._remaining_seconds(connect_deadline))
        try:
            greeting = self._read_response(deadline)
            if "Authentication required" in greeting:
                token = (pathlib.Path.home() / ".emulator_console_auth_token").read_text().strip()
                require(token and "\n" not in token and "\r" not in token, "Invalid emulator console token")
                self.send_command("auth " + token, deadline)
        except BaseException:
            self.close()
            raise

    @staticmethod
    def _remaining_seconds(deadline):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Emulator console response exceeded its absolute deadline")
        return remaining

    def _read_response(self, deadline):
        deadline = min(deadline, time.monotonic() + CONSOLE_RESPONSE_TIMEOUT_SECONDS)
        lines = []
        for _ in range(100):
            line = bytearray()
            while not line.endswith(b"\n"):
                # A readline call could keep resetting its socket timeout as bytes arrive.
                self.socket.settimeout(self._remaining_seconds(deadline))
                try:
                    block = self.socket.recv(1)
                except TimeoutError as failure:
                    raise TimeoutError("Emulator console response exceeded its absolute deadline") from failure
                self._remaining_seconds(deadline)
                require(block, "Invalid emulator console response")
                line.extend(block)
                require(len(line) <= 4096, "Invalid emulator console response")
            text = line.decode("utf-8", errors="replace").strip()
            lines.append(text)
            if text == "OK":
                return "\n".join(lines)
            if text.startswith("KO"):
                raise RuntimeError("Emulator console rejected redirection/authentication")
        raise RuntimeError("Unterminated emulator console response")

    def send_command(self, command_text, deadline):
        deadline = min(deadline, time.monotonic() + CONSOLE_RESPONSE_TIMEOUT_SECONDS)
        self.socket.settimeout(self._remaining_seconds(deadline))
        self.socket.sendall((command_text + "\n").encode("utf-8"))
        return self._read_response(deadline)

    def close(self):
        self.socket.close()


def read_crlf_line(stream, limit=4096):
    line = stream.readline(limit + 1)
    require(line.endswith(b"\r\n") and len(line) <= limit, "Invalid HTTP/multipart header line")
    return line[:-2]


def check_jpeg(body, probe_request):
    require(len(body) == probe_request["expectedBytes"], "JPEG length differs from the production placeholder")
    require(body.startswith(b"\xff\xd8") and body.endswith(b"\xff\xd9"), "Body is not a complete JPEG fixture")
    digest = hashlib.sha256(body).hexdigest()
    require(digest == probe_request["expectedSha256"], "JPEG differs from the production placeholder")
    return digest


def fetch_jpeg(host, port, probe_request):
    connection = http.client.HTTPConnection(host, port, timeout=5)
    try:
        connection.request("GET", "/jpeg", headers={"Host": f"{probe_request['deviceAddress']}:{probe_request['devicePort']}"})
        response = connection.getresponse()
        require(response.status == 200, "External /jpeg did not return 200")
        require(response.getheader("Content-Type", "").split(";", 1)[0].strip() == "image/jpeg", "Wrong /jpeg content type")
        require(response.getheader("Content-Length") == str(probe_request["expectedBytes"]), "Wrong /jpeg Content-Length")
        body = response.read(MAX_JPEG_BYTES + 1)
        require(len(body) <= MAX_JPEG_BYTES, "Oversized /jpeg response")
        return len(body), check_jpeg(body, probe_request)
    finally:
        connection.close()


def fetch_multipart(host, port, probe_request):
    connection = http.client.HTTPConnection(host, port, timeout=5)
    try:
        connection.request("GET", "/mjpeg", headers={"Host": f"{probe_request['deviceAddress']}:{probe_request['devicePort']}"})
        response = connection.getresponse()
        require(response.status == 200, "External /mjpeg did not return 200")
        content_type = Message()
        content_type["Content-Type"] = response.getheader("Content-Type", "")
        require(content_type.get_content_type() == "multipart/x-mixed-replace", "Wrong multipart content type")
        boundary = content_type.get_param("boundary")
        require(isinstance(boundary, str) and 0 < len(boundary) <= 200, "Missing multipart boundary")
        boundary_bytes = boundary.encode("ascii")
        require(b"\r" not in boundary_bytes and b"\n" not in boundary_bytes, "Invalid boundary")
        line = read_crlf_line(response)
        if not line:
            line = read_crlf_line(response)
        require(line == b"--" + boundary_bytes, "Multipart boundary differs from its header")
        headers = {}
        total_header_bytes = 0
        while True:
            line = read_crlf_line(response)
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
        require(headers.get("content-length") == str(probe_request["expectedBytes"]), "Wrong multipart Content-Length")
        body = response.read(probe_request["expectedBytes"])
        require(response.read(2) == b"\r\n", "Missing multipart payload terminator")
        return len(body), check_jpeg(body, probe_request)
    finally:
        connection.close()


def probe_denied(host, port, probe_request):
    connection = http.client.HTTPConnection(host, port, timeout=3)
    try:
        connection.request("GET", "/jpeg", headers={"Host": f"{probe_request['deviceAddress']}:{probe_request['devicePort']}"})
        response = connection.getresponse()
        raise AssertionError(f"Denied LAN endpoint answered HTTP {response.status}")
    except (ConnectionError, TimeoutError, http.client.RemoteDisconnected) as failure:
        return type(failure).__name__
    finally:
        connection.close()


def probe_endpoint(host, port, probe_request):
    if probe_request["phase"] == "denied":
        return {"result": "unreachable", "failure": probe_denied(host, port, probe_request)}
    jpeg_bytes, jpeg_sha = fetch_jpeg(host, port, probe_request)
    part_bytes, part_sha = fetch_multipart(host, port, probe_request)
    return {"jpegBytes": jpeg_bytes, "jpegSha256": jpeg_sha,
            "multipartBytes": part_bytes, "multipartSha256": part_sha}


def run_probe_with_deadline(host, port, probe_request, deadline):
    # A child bounds buffered HTTP reads even if individual socket reads keep making progress.
    remaining = min(PROBE_PHASE_TIMEOUT_SECONDS, deadline - time.monotonic())
    require(remaining > 0, "Instrumentation/peer coordination timed out before the probe")
    probe_command = [sys.executable, str(pathlib.Path(__file__).resolve()), "--probe"]
    payload = json.dumps({"host": host, "port": port, "request": probe_request})
    try:
        probe_process = subprocess.run(probe_command, input=payload, text=True, capture_output=True, timeout=remaining)
    except subprocess.TimeoutExpired as failure:
        raise TimeoutError("External peer phase exceeded its absolute deadline") from failure
    require(probe_process.returncode == 0, "External peer probe failed: " + probe_process.stderr.strip())
    require(time.monotonic() < deadline, "Instrumentation/peer coordination timed out during the probe")
    return json.loads(probe_process.stdout)


def run_lan_test(args):
    require(re.fullmatch(r"[A-Za-z0-9_.]+/androidx\.test\.runner\.AndroidJUnitRunner", args.component), "Invalid instrumentation component")
    test_package = args.component.split("/", 1)[0]
    require(test_package.startswith("io.screenstream.mjpeg.") and test_package.endswith("test"), "Use only the library test APK")
    if args.topology == "emulator-console":
        require(args.console_port is not None and args.serial == f"emulator-{args.console_port}", "Console port must identify the selected emulator")
        require(args.interface == "eth0", "Console redirection targets guest Ethernet, not wlan0")
    else:
        require(args.console_port is None, "Direct topology must not use console redirection")
    run_id = uuid.uuid4().hex
    run_directory = args.output / run_id
    run_directory.mkdir(parents=True)
    adb = [args.adb, "-s", args.serial]
    instrumentation_command = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", LAN_TEST_CLASS,
                     "-e", "lanRunId", run_id, "-e", "lanTopology", args.topology,
                     "-e", "lanInterface", args.interface, args.component]
    process = subprocess.Popen(instrumentation_command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    instrumentation_lines = queue.Queue()

    def read_instrumentation_output():
        try:
            for line in process.stdout:
                instrumentation_lines.put(line)
        finally:
            instrumentation_lines.put(None)

    threading.Thread(target=read_instrumentation_output, daemon=True).start()
    console = None
    host_port = None
    redirection_added = False
    expected_endpoint = None
    denied_seen = False
    completed_phases = []
    instrumentation_status = {}
    class_passed = False
    runner_finished = False
    successful = False
    deadline = time.monotonic() + 180
    try:
        with (run_directory / "instrumentation.log").open("w") as log:
            while True:
                require(time.monotonic() < deadline, "Instrumentation/peer coordination timed out")
                try:
                    line = instrumentation_lines.get(timeout=min(1, max(0, deadline - time.monotonic())))
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
                    if instrumentation_status.get("class") == LAN_TEST_CLASS and instrumentation_status.get("test"):
                        require(code >= 0, "The LAN instrumentation test failed or was skipped")
                        if code == 0:
                            class_passed = True
                    instrumentation_status.clear()
                elif line.startswith("INSTRUMENTATION_STATUS: "):
                    key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].strip().partition("=")
                    if separator:
                        instrumentation_status[key] = value
                peer_status_prefix = "INSTRUMENTATION_STATUS: mjpegLanPeer="
                if not line.startswith(peer_status_prefix):
                    continue
                probe_request = json.loads(line[len(peer_status_prefix):])
                phase = probe_request["phase"]
                require(probe_request["version"] == 1 and probe_request["runId"] == run_id, "Stale peer request")
                require(probe_request["packageName"] == test_package and probe_request["topology"] == args.topology, "Unexpected package/topology")
                require(phase == ("denied" if not completed_phases else "granted") and len(completed_phases) < 2, "Unexpected peer phase order")
                require(probe_request["interfaceName"] == args.interface, "Unexpected native interface")
                device_address = ipaddress.ip_address(probe_request["deviceAddress"])
                require(device_address.version == 4 and device_address.is_private and not device_address.is_loopback and not device_address.is_link_local, "Peer must target a real nonloopback IPv4 interface")
                require(1 <= probe_request["devicePort"] <= 65535 and 0 < probe_request["expectedBytes"] <= MAX_JPEG_BYTES, "Invalid endpoint/fixture size")
                require(re.fullmatch(r"[a-f0-9]{64}", probe_request["expectedSha256"]), "Invalid fixture digest")
                receipt_path = f"cache/mjpeg-lan-peer-{run_id}/{phase}.json"
                require(probe_request["receiptPath"] == receipt_path, "Unexpected private receipt path")
                current_endpoint = (str(device_address), probe_request["devicePort"], probe_request["expectedSha256"], probe_request["expectedBytes"])
                if phase == "denied":
                    expected_endpoint = current_endpoint
                    denied_seen = True
                    if args.topology == "emulator-console":
                        require(str(device_address) == "10.0.2.15", "Console route only qualifies the discovered 10.0.2.15 Ethernet endpoint")
                        console = EmulatorConsole(args.console_port, deadline)
                        with socket.socket() as reservation:
                            reservation.bind(("127.0.0.1", 0))
                            host_port = reservation.getsockname()[1]
                        console.send_command(f"redir add tcp:{host_port}:{probe_request['devicePort']}", deadline)
                        redirection_added = True
                    else:
                        host_port = probe_request["devicePort"]
                else:
                    require(current_endpoint == expected_endpoint, "Endpoint/fixture changed between denied and granted phases")
                host = "127.0.0.1" if console else str(device_address)
                receipt = {key: probe_request[key] for key in ("version", "runId", "phase", "topology", "deviceAddress", "devicePort")}
                receipt.update(hostAddress=host, hostPort=host_port, interfaceName=args.interface,
                              physicalLan=False, probePerformed=False, passed=False)
                try:
                    receipt["probePerformed"] = True
                    receipt.update(run_probe_with_deadline(host, host_port, probe_request, deadline))
                    receipt["passed"] = True
                except Exception as failure:
                    receipt["error"] = f"{type(failure).__name__}: {failure}"
                (run_directory / f"{phase}-request.json").write_text(json.dumps(probe_request, indent=2) + "\n")
                encoded_receipt = json.dumps(receipt, separators=(",", ":")) + "\n"
                (run_directory / f"{phase}-peer.json").write_text(json.dumps(receipt, indent=2) + "\n")
                temporary_receipt_path = receipt_path + ".tmp"
                receipt_write_command = f"cat > {shlex.quote(temporary_receipt_path)} && mv {shlex.quote(temporary_receipt_path)} {shlex.quote(receipt_path)}"
                remaining = deadline - time.monotonic()
                require(remaining > 0, "Instrumentation/peer coordination timed out before the receipt")
                subprocess.run(adb + ["shell", "run-as", test_package, "sh", "-c", shlex.quote(receipt_write_command)], input=encoded_receipt, text=True, check=True, timeout=min(10, remaining))
                require(receipt["passed"], receipt.get("error", "Peer assertion failed"))
                completed_phases.append(phase)
        process.wait(timeout=10)
        require(process.returncode == 0 and runner_finished and class_passed and completed_phases == ["denied", "granted"], "LAN instrumentation did not complete both peer phases successfully")
        summary = {"runId": run_id, "topology": args.topology, "physicalLan": False,
                   "result": "denied/granted permission UI and external JPEG/multipart reachability; no independent kernel-blocking claim"}
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
                subprocess.run(adb + ["shell", "am", "force-stop", test_package], check=True, timeout=10)
        except Exception as failure:
            cleanup_errors.append(f"test process: {failure}")
        try:
            if console is not None and redirection_added:
                console.send_command(f"redir del tcp:{host_port}", time.monotonic() + CONSOLE_CLEANUP_TIMEOUT_SECONDS)
        except Exception as failure:
            cleanup_errors.append(f"console redirection: {failure}")
        try:
            if console is not None:
                console.close()
        except Exception as failure:
            cleanup_errors.append(f"console close: {failure}")
        try:
            if denied_seen:
                subprocess.run(adb + ["shell", "pm", "revoke", test_package, LOCAL_NETWORK_PERMISSION], check=True, timeout=10)
        except Exception as failure:
            cleanup_errors.append(f"LAN grant restoration: {failure}")
        (run_directory / "cleanup.json").write_text(json.dumps({"errors": cleanup_errors, "initialDeniedRestorationRequested": denied_seen}, indent=2) + "\n")
        if cleanup_errors:
            message = "Cleanup failed: " + "; ".join(cleanup_errors)
            if original_failure is not None:
                print(message, file=sys.stderr)
            else:
                raise RuntimeError(message)
    (run_directory / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(f"LAN peer evidence: {run_directory}", flush=True)


def main():
    if sys.argv[1:] == ["--probe"]:
        payload = json.load(sys.stdin)
        print(json.dumps(probe_endpoint(payload["host"], payload["port"], payload["request"])))
        return
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--component", required=True)
    parser.add_argument("--topology", choices=("emulator-console", "direct"), required=True)
    parser.add_argument("--interface", required=True)
    parser.add_argument("--console-port", type=int)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", type=pathlib.Path, required=True)
    run_lan_test(parser.parse_args())


if __name__ == "__main__":
    main()
