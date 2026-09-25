import subprocess
from pathlib import Path
from inspect_worker_runtime import process_environment

root = Path(__file__).resolve().parents[1]
environment = process_environment(32296)
subprocess.run(['taskkill', '/PID', '32296', '/F'], check=True, stdout=subprocess.DEVNULL)
with (root / 'logs/system-traced-build.log').open('w') as log:
    result = subprocess.run(['D:/Maven/apache-maven-3.9.7/bin/mvn.cmd', '-B', '-ntp', '-pl',
                             'ruoyi-modules/ruoyi-system', '-am', '-DskipTests', 'package'],
                            cwd=root / 'RuoYi-Cloud', stdout=log, stderr=subprocess.STDOUT)
if result.returncode:
    raise RuntimeError('build failed: see system-traced-build.log')
with (root / 'logs/system-traced-out.log').open('w') as out, (root / 'logs/system-traced-err.log').open('w') as err:
    child = subprocess.Popen(['D:/Java/java17/java17/bin/java.exe', '-Dfile.encoding=UTF-8', '-jar',
                              'ruoyi-modules-system.jar'], env=environment,
                             cwd=root / 'RuoYi-Cloud/ruoyi-modules/ruoyi-system/target',
                             creationflags=subprocess.CREATE_NO_WINDOW, stdout=out, stderr=err)
print('Build succeeded; system PID', child.pid)
