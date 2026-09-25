import ctypes
import json
import os
import sys
from ctypes import wintypes
from pathlib import Path


def process_environment(pid):
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    ntdll = ctypes.WinDLL('ntdll')
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.ReadProcessMemory.argtypes = [wintypes.HANDLE, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    ntdll.NtQueryInformationProcess.argtypes = [wintypes.HANDLE, wintypes.ULONG, ctypes.c_void_p, wintypes.ULONG, ctypes.c_void_p]
    handle = kernel.OpenProcess(0x410, False, pid)
    if not handle:
        raise RuntimeError('process unavailable')
    def read(address, size):
        buf = ctypes.create_string_buffer(size)
        count = ctypes.c_size_t()
        if not kernel.ReadProcessMemory(handle, address, buf, size, ctypes.byref(count)):
            raise RuntimeError('process read failed')
        return buf.raw[:count.value]
    try:
        pbi = ctypes.create_string_buffer(48)
        if ntdll.NtQueryInformationProcess(handle, 0, pbi, 48, None):
            raise RuntimeError('process query failed')
        peb = int.from_bytes(pbi.raw[8:16], 'little')
        params = int.from_bytes(read(peb + 0x20, 8), 'little')
        env = int.from_bytes(read(params + 0x80, 8), 'little')
        block = read(env, 32768).decode('utf-16-le', errors='replace').split('\0\0', 1)[0]
        return dict(item.split('=', 1) for item in block.split('\0') if '=' in item and not item.startswith('='))
    finally:
        kernel.CloseHandle(handle)


if __name__ == '__main__':
    runtime = process_environment(int(sys.argv[1]))
    local = json.loads((Path(__file__).resolve().parents[1] / 'validation/config.local.json').read_text(encoding='utf-8-sig'))
    print(json.dumps({'worker_key_present': bool(runtime.get('RUOYI_MEDIA_QWEN_API_KEY')),
                      'matches_validated_image_key': runtime.get('RUOYI_MEDIA_QWEN_API_KEY') == local.get('api_key'),
                      'endpoint': runtime.get('RUOYI_MEDIA_QWEN_ENDPOINT', 'https://dashscope.aliyuncs.com'),
                      'proxy_env_names': [key for key in runtime if 'proxy' in key.lower()]}))
