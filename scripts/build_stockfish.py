"""Build the unchanged Stockfish 19 sources for Android/arm64 with 16 KB ELF segments.

Prerequisite: official Android NDK r28c unpacked into .tools/android-ndk-r28c.
The source archive is the official sf_19 Android distribution, which includes src/.
"""
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
import hashlib
import re
import shutil
import struct
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / '.tools/stockfish19/stockfish/src'
TOOLS = ROOT / '.tools/android-ndk-r28c/toolchains/llvm/prebuilt/windows-x86_64/bin'
BUILD = ROOT / '.tools/stockfish-android16k'
BUILD.mkdir(parents=True, exist_ok=True)

network = re.search(r'nn-[0-9a-f]+\.nnue', (SOURCE / 'evaluate.h').read_text()).group()
network_file = SOURCE / network
if not network_file.exists():
    print('Downloading verified NNUE network:', network, flush=True)
    urllib.request.urlretrieve('https://github.com/official-stockfish/networks/raw/master/' + network, network_file)
network_hash = hashlib.file_digest(network_file.open('rb'), 'sha256').hexdigest()
if not network_hash.startswith(network[3:15]):
    raise RuntimeError('NNUE network checksum mismatch')

makefile = (SOURCE / 'Makefile').read_text()
source_list = re.search(r'^SRCS = (.*?)(?=\n\n)', makefile, re.M | re.S).group(1).replace('\\\n', ' ').split()
compiler = TOOLS / 'clang++.exe'
common = ['--target=aarch64-linux-android29', '-std=c++17', '-O3', '-DNDEBUG', '-DIS_64BIT',
          '-DUSE_NEON', '-DUSE_POPCNT', '-fno-exceptions', '-fPIE', '-pthread',
          '-fconstexpr-steps=500000000']

def compile_one(name):
    obj = BUILD / (name.replace('/', '_') + '.o')
    if not obj.exists() or obj.stat().st_mtime < (SOURCE / name).stat().st_mtime:
        result = subprocess.run([str(compiler), *common, '-c', name, '-o', str(obj)], cwd=SOURCE,
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (BUILD / (obj.stem + '.log')).write_text(result.stdout, encoding='utf-8')
        if result.returncode:
            raise RuntimeError(name + '\n' + result.stdout[-5000:])
    print('Compiled', name, flush=True)
    return obj

objects = []
with ThreadPoolExecutor(max_workers=4) as pool:
    for future in as_completed([pool.submit(compile_one, name) for name in source_list]):
        objects.append(future.result())

output = BUILD / 'libstockfish.so'
subprocess.run([str(compiler), *common, *map(str, sorted(objects)), '-pie', '-static-libstdc++',
                '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384', '-latomic', '-o', str(output)],
               cwd=SOURCE, check=True)
subprocess.run([str(TOOLS / 'llvm-strip.exe'), '--strip-debug', str(output)], check=True)
with output.open('rb') as f:
    header = f.read(64)
    offset = struct.unpack_from('<Q', header, 32)[0]
    entry_size, count = struct.unpack_from('<HH', header, 54)
    f.seek(offset)
    segments = [struct.unpack('<IIQQQQQQ', f.read(entry_size)) for _ in range(count)]
    alignments = [segment[-1] for segment in segments if segment[0] == 1]
if not alignments or any(value < 16384 for value in alignments):
    raise RuntimeError('Incorrect ELF LOAD alignments: ' + str(alignments))
destination = ROOT / 'app/src/main/jniLibs/arm64-v8a/libstockfish.so'
shutil.copy2(output, destination)
print('16 KB ELF LOAD alignments:', alignments)
print('Binary SHA256:', hashlib.file_digest(destination.open('rb'), 'sha256').hexdigest())
print('NNUE SHA256:', network_hash)
