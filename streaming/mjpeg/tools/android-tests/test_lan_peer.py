"""Local HTTP and console checks; no adb, Android device, or emulator is used."""

import hashlib
import importlib.util
import pathlib
import socket
import threading
import time
import unittest
from contextlib import contextmanager
from unittest import mock

module_spec = importlib.util.spec_from_file_location("lan_peer", pathlib.Path(__file__).with_name("lan_peer.py"))
lan_peer = importlib.util.module_from_spec(module_spec)
module_spec.loader.exec_module(lan_peer)
JPEG_BYTES = b"\xff\xd8" + b"x" * 96 + b"\xff\xd9"
JPEG_SHA256 = hashlib.sha256(JPEG_BYTES).hexdigest()


@contextmanager
def http_peer(mode):
    stop = threading.Event()
    requested = threading.Event()
    disconnected = threading.Event()
    errors = []
    paths = []
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen()
    listener.settimeout(0.1)
    port = listener.getsockname()[1]

    def serve():
        try:
            while not stop.is_set():
                try:
                    connection, _ = listener.accept()
                except TimeoutError:
                    continue
                with connection:
                    connection.settimeout(0.1)
                    headers = b""
                    while b"\r\n\r\n" not in headers:
                        block = connection.recv(4096)
                        if not block:
                            break
                        headers += block
                    if not headers:
                        continue
                    paths.append(headers.split(b" ", 2)[1])
                    requested.set()
                    if mode == "disconnect":
                        continue
                    if mode == "idle":
                        while not stop.is_set():
                            try:
                                if not connection.recv(1):
                                    disconnected.set()
                                    break
                            except TimeoutError:
                                continue
                        continue
                    if paths[-1] == b"/jpeg":
                        connection.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " +
                                           str(len(JPEG_BYTES)).encode() + b"\r\n\r\n")
                        if mode == "trickle":
                            try:
                                for byte in JPEG_BYTES:
                                    if stop.wait(0.03):
                                        break
                                    connection.sendall(bytes([byte]))
                            except (BrokenPipeError, ConnectionResetError):
                                disconnected.set()
                        else:
                            connection.sendall(JPEG_BYTES)
                    else:
                        body = (b"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " +
                                str(len(JPEG_BYTES)).encode() + b"\r\n\r\n" + JPEG_BYTES + b"\r\n")
                        connection.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n" + body)
        except Exception as failure:
            errors.append(failure)

    worker = threading.Thread(target=serve)
    worker.start()
    try:
        yield port, paths, requested, disconnected
    finally:
        stop.set()
        worker.join(timeout=2)
        listener.close()
        if worker.is_alive():
            raise AssertionError("Local peer thread did not stop")
        if errors:
            raise AssertionError(f"Local peer failed: {errors}")


def probe_request(port, phase="granted"):
    return {"phase": phase, "deviceAddress": "127.0.0.1", "devicePort": port,
            "expectedBytes": len(JPEG_BYTES), "expectedSha256": JPEG_SHA256}


class LanPeerHttpProbeTest(unittest.TestCase):
    def test_granted_phase_returns_matching_jpeg_and_multipart_payload_receipts(self):
        with http_peer("normal") as (port, paths, _, _):
            result = lan_peer.run_probe_with_deadline("127.0.0.1", port, probe_request(port), time.monotonic() + 5)
            self.assertEqual([b"/jpeg", b"/mjpeg"], paths)
            self.assertEqual({"jpegBytes": len(JPEG_BYTES), "jpegSha256": JPEG_SHA256,
                              "multipartBytes": len(JPEG_BYTES), "multipartSha256": JPEG_SHA256}, result)

    def test_denied_phase_reports_disconnected_http_endpoint_as_unreachable(self):
        with http_peer("disconnect") as (port, paths, _, _):
            result = lan_peer.run_probe_with_deadline("127.0.0.1", port, probe_request(port, "denied"), time.monotonic() + 5)
            self.assertEqual([b"/jpeg"], paths)
            self.assertEqual("unreachable", result["result"])
            self.assertEqual("RemoteDisconnected", result["failure"])

    def assert_http_probe_deadline(self, mode):
        with http_peer(mode) as (port, _, requested, disconnected):
            start = time.monotonic()
            with self.assertRaisesRegex(TimeoutError, "absolute deadline"):
                lan_peer.run_probe_with_deadline("127.0.0.1", port, probe_request(port), start + 0.75)
            self.assertLess(time.monotonic() - start, 3)
            self.assertTrue(requested.is_set(), "The phase must reach its blocking HTTP read")
            self.assertTrue(disconnected.wait(1), "The timed-out probe must close its socket")

    def test_idle_http_response_is_interrupted_by_probe_deadline(self):
        self.assert_http_probe_deadline("idle")

    def test_progressing_jpeg_response_cannot_extend_probe_deadline(self):
        self.assert_http_probe_deadline("trickle")


class EmulatorConsoleTest(unittest.TestCase):
    def create_console_pair(self):
        client, peer = socket.socketpair()
        self.addCleanup(client.close)
        self.addCleanup(peer.close)
        peer.settimeout(0.25)
        console = lan_peer.EmulatorConsole.__new__(lan_peer.EmulatorConsole)
        console.socket = client
        return console, peer

    def test_authentication_and_close_preserve_console_protocol(self):
        console, peer = self.create_console_pair()
        peer.sendall(b"Android Console: Authentication required\r\nOK\r\nOK\r\n")
        with mock.patch.object(lan_peer.socket, "create_connection", return_value=console.socket), \
                mock.patch.object(lan_peer.pathlib.Path, "read_text", return_value="test-token\n"):
            authenticated = lan_peer.EmulatorConsole(5554, time.monotonic() + 1)
        self.assertEqual(b"auth test-token\n", peer.recv(100))
        authenticated.close()
        self.assertEqual(b"", peer.recv(1))

    def assert_console_response_deadline(self, payload):
        console, peer = self.create_console_pair()
        stop = threading.Event()
        progressing = threading.Event()
        errors = []

        def send():
            try:
                for index in range(60):
                    peer.sendall(payload)
                    if index == 2:
                        progressing.set()
                    if stop.wait(0.04):
                        return
                peer.sendall(b"\nOK\n")
            except (BrokenPipeError, ConnectionResetError) as failure:
                if not stop.is_set():
                    errors.append(failure)
            except Exception as failure:
                errors.append(failure)
            finally:
                peer.close()

        worker = threading.Thread(target=send, daemon=True)
        worker.start()
        try:
            self.assertTrue(progressing.wait(2), "Peer must make progress before the response budget starts")
            start = time.monotonic()
            with self.assertRaisesRegex(TimeoutError, "console response exceeded its absolute deadline"):
                console._read_response(start + 0.25)
            self.assertLess(time.monotonic() - start, 1.5)
        finally:
            stop.set()
            console.close()
            worker.join(timeout=2)
            self.assertFalse(worker.is_alive(), "Console peer thread did not stop")
            self.assertFalse(errors, f"Console peer failed: {errors}")

    def test_progressing_bytes_cannot_extend_console_deadline(self):
        self.assert_console_response_deadline(b"x")

    def test_progressing_lines_cannot_extend_console_deadline(self):
        self.assert_console_response_deadline(b"progress\n")

    def test_cleanup_command_can_use_a_fresh_budget_after_outer_deadline(self):
        console, peer = self.create_console_pair()
        with self.assertRaisesRegex(TimeoutError, "absolute deadline"):
            console.send_command("redir add tcp:1234:5678", time.monotonic() - 1)
        peer.sendall(b"OK\r\n")
        self.assertEqual("OK", console.send_command("redir del tcp:1234", time.monotonic() + 1))
        self.assertEqual(b"redir del tcp:1234\n", peer.recv(100))


if __name__ == "__main__":
    unittest.main()
