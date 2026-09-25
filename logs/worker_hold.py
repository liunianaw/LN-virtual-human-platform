import os
import subprocess
import sys
import time
from pathlib import Path
from inspect_worker_runtime import process_environment

root = Path(__file__).resolve().parents[1]
if len(sys.argv) > 1:
    environment = process_environment(int(sys.argv[1]))
    environment['NO_PROXY'] = '127.0.0.1,localhost,.aliyuncs.com,.myqcloud.com'
    subprocess.run(['taskkill', '/PID', sys.argv[1], '/F'], check=True, stdout=subprocess.DEVNULL)
    child = subprocess.Popen([sys.executable, __file__], env=environment, creationflags=subprocess.CREATE_NO_WINDOW,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print('Worker stopped; runtime configuration retained in holder PID', child.pid)
else:
    gate = root / 'logs/resume-worker.flag'
    while not gate.exists():
        time.sleep(1)
    with (root / 'logs/worker-traced-out.log').open('a') as out, (root / 'logs/worker-traced-err.log').open('a') as err:
        child = subprocess.Popen([sys.executable, '-m', 'ruoyi_media.worker', '--consume-outbox', '--interval', '10'],
                                 cwd=root / 'ruoyi-media', creationflags=subprocess.CREATE_NO_WINDOW,
                                 stdout=out, stderr=err)
