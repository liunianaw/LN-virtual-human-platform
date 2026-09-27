"""Install only the DEV-08 Nginx include on the known acceptance server."""

import hashlib
import json
import os
import pathlib
import uuid
import warnings

warnings.filterwarnings("ignore", category=DeprecationWarning, module="paramiko")
import paramiko


HOST = "124.220.61.136"
TARGET = "/www/server/panel/vhost/nginx/extension/124.220.61.136/dev08-relay.conf"
SOURCE = pathlib.Path(__file__).with_name("dev08-nginx.conf")


def remote(client, command):
    _, stdout, stderr = client.exec_command(command, timeout=15)
    output = stderr.read().decode("utf-8", "replace")[-1000:]
    code = stdout.channel.recv_exit_status()
    return code, output


def main():
    password = pathlib.Path(os.environ["DEV08_SSH_PASSWORD_FILE"]).read_text(
        encoding="utf-8-sig"
    ).strip()
    if not password or "\n" in password or "\r" in password:
        raise RuntimeError("invalid SSH password file")
    data = SOURCE.read_bytes()
    client = paramiko.SSHClient()
    client.load_host_keys(str(pathlib.Path.home() / ".ssh" / "known_hosts"))
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    try:
        client.connect(HOST, username="root", password=password, look_for_keys=False,
                       allow_agent=False, timeout=8, auth_timeout=8, banner_timeout=8)
    except Exception:
        client.close()
        raise
    sftp = client.open_sftp()
    temporary = TARGET + ".tmp-" + uuid.uuid4().hex
    installed = False
    try:
        try:
            with sftp.open(TARGET, "rb") as existing:
                if hashlib.sha256(existing.read()).digest() == hashlib.sha256(data).digest():
                    print(json.dumps({"result": "already-installed", "target": TARGET}))
                    return
                raise RuntimeError("DEV-08 target exists with different content")
        except IOError as error:
            if getattr(error, "errno", None) != 2:
                raise
        baseline, diagnostics = remote(client, "nginx -t")
        if baseline:
            raise RuntimeError("baseline nginx -t failed: " + diagnostics)
        with sftp.open(temporary, "wb") as file:
            file.write(data)
        sftp.chmod(temporary, 0o644)
        sftp.rename(temporary, TARGET)
        installed = True
        valid, diagnostics = remote(client, "nginx -t")
        if valid:
            raise RuntimeError("new nginx -t failed: " + diagnostics)
        reloaded, diagnostics = remote(client, "systemctl reload nginx")
        if reloaded:
            raise RuntimeError("nginx reload failed: " + diagnostics)
        installed = False
        print(json.dumps({"result": "installed", "target": TARGET,
                          "nginxTest": "passed", "reload": "passed"}))
    finally:
        if installed:
            sftp.remove(TARGET)
            remote(client, "nginx -t")
            remote(client, "systemctl reload nginx")
        try:
            sftp.remove(temporary)
        except IOError:
            pass
        sftp.close()
        client.close()


if __name__ == "__main__":
    main()
