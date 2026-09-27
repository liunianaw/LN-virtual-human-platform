"""Temporary localhost-to-server reverse SSH tunnel for the DEV-08 acceptance adapter."""

import os
import pathlib
import socket
import threading
import time
import warnings

warnings.filterwarnings("ignore", category=DeprecationWarning, module="paramiko")
import paramiko


REMOTE_HOST = "124.220.61.136"
REMOTE_PORT = 18030
LOCAL_PORT = 8030


def relay(source, destination):
    try:
        while chunk := source.recv(65536):
            destination.sendall(chunk)
    except (OSError, EOFError):
        pass
    finally:
        try:
            destination.shutdown(socket.SHUT_WR)
        except (OSError, EOFError):
            pass


def incoming(channel, origin, server):
    try:
        local = socket.create_connection(("127.0.0.1", LOCAL_PORT), timeout=5)
        local.settimeout(None)
    except OSError:
        channel.close()
        return

    def serve():
        try:
            upstream = threading.Thread(target=relay, args=(local, channel), daemon=True)
            upstream.start()
            relay(channel, local)
            upstream.join(timeout=5)
        finally:
            local.close()
            channel.close()

    threading.Thread(target=serve, daemon=True).start()


def main():
    password_file = pathlib.Path(os.environ["DEV08_SSH_PASSWORD_FILE"])
    password = password_file.read_text(encoding="utf-8-sig").strip()
    if not password or "\n" in password or "\r" in password:
        raise RuntimeError("invalid SSH password file")
    client = paramiko.SSHClient()
    client.load_host_keys(str(pathlib.Path.home() / ".ssh" / "known_hosts"))
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    try:
        client.connect(REMOTE_HOST, username="root", password=password,
                       look_for_keys=False, allow_agent=False, timeout=8,
                       auth_timeout=8, banner_timeout=8)
        transport = client.get_transport()
        transport.set_keepalive(30)
        transport.request_port_forward("127.0.0.1", REMOTE_PORT, incoming)
        print(f"DEV-08 SSH tunnel ready on server loopback port {REMOTE_PORT}", flush=True)
        while transport.is_active():
            time.sleep(1)
        raise RuntimeError("SSH tunnel disconnected")
    finally:
        client.close()


if __name__ == "__main__":
    main()
