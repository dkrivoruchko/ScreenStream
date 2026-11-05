"""Fast local HTTP checks; no adb, Android device, or emulator is used."""

import hashlib
import importlib.util
import pathlib
import socket
import threading
import time
import unittest
from contextlib import contextmanager

spec = importlib.util.spec_from_file_location("lan_peer", pathlib.Path(__file__).with_name("lan_peer.py"))
lan_peer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lan_peer)
JPEG = b"\xff\xd8" + b"x" * 96 + b"\xff\xd9"
DIGEST = hashlib.sha256(JPEG).hexdigest()


@contextmanager
def peer(mode):
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
                                           str(len(JPEG)).encode() + b"\r\n\r\n")
                        if mode == "trickle":
                            try:
                                for byte in JPEG:
                                    if stop.wait(0.03):
                                        break
                                    connection.sendall(bytes([byte]))
                            except (BrokenPipeError, ConnectionResetError):
                                disconnected.set()
                        else:
                            connection.sendall(JPEG)
                    else:
                        body = (b"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " +
                                str(len(JPEG)).encode() + b"\r\n\r\n" + JPEG + b"\r\n")
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


def request(port, phase="granted"):
    return {"phase": phase, "deviceAddress": "127.0.0.1", "devicePort": port,
            "expectedBytes": len(JPEG), "expectedSha256": DIGEST}


class LanPeerProbeTest(unittest.TestCase):
    def test_granted_phase_preserves_real_jpeg_and_multipart_checks(self):
        with peer("normal") as (port, paths, _, _):
            result = lan_peer.run_probe("127.0.0.1", port, request(port), time.monotonic() + 5)
            self.assertEqual([b"/jpeg", b"/mjpeg"], paths)
            self.assertEqual({"jpegBytes": len(JPEG), "jpegSha256": DIGEST,
                              "multipartBytes": len(JPEG), "multipartSha256": DIGEST}, result)

    def test_denied_phase_preserves_unreachable_result(self):
        with peer("disconnect") as (port, paths, _, _):
            result = lan_peer.run_probe("127.0.0.1", port, request(port, "denied"), time.monotonic() + 5)
            self.assertEqual([b"/jpeg"], paths)
            self.assertEqual("unreachable", result["result"])
            self.assertEqual("RemoteDisconnected", result["failure"])

    def assert_phase_deadline(self, mode):
        with peer(mode) as (port, _, requested, disconnected):
            start = time.monotonic()
            with self.assertRaisesRegex(TimeoutError, "absolute deadline"):
                lan_peer.run_probe("127.0.0.1", port, request(port), start + 0.75)
            self.assertLess(time.monotonic() - start, 3)
            self.assertTrue(requested.is_set(), "The phase must reach its blocking HTTP read")
            self.assertTrue(disconnected.wait(1), "The timed-out probe must close its socket")

    def test_idle_response_is_interrupted_by_phase_deadline(self):
        self.assert_phase_deadline("idle")

    def test_progressing_response_cannot_extend_phase_deadline(self):
        self.assert_phase_deadline("trickle")


if __name__ == "__main__":
    unittest.main()
