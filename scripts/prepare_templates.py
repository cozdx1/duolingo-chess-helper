"""Extract recognition features from the authorized, native-resolution bot screenshot."""
from pathlib import Path
import struct
import gzip
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
image = np.asarray(Image.open(ROOT / 'artifacts/duolingo-live-black.png').convert('RGB'))
size, top, n = image.shape[1], 1099, 32
samples = [('P', 1, 0), ('p', 6, 0)]
samples += [(piece, 0, col) for col, piece in enumerate('RNBKQ')]
samples += [(piece, 7, col) for col, piece in enumerate('rnbkq')]
with (ROOT / 'app/src/main/assets/pieces.bin').open('wb') as out:
    out.write(struct.pack('>III', 0x43545032, n, len(samples)))
    for name, row, col in samples:
        cell = size / 8
        x, y = col * cell, top + row * cell
        bg = image[int(y + cell * .035), int(x + cell * .035)].astype(int)
        xs = (x + (np.arange(n) + .5) * cell / n).astype(int)
        ys = (y + (np.arange(n) + .5) * cell / n).astype(int)
        feature = image[ys[:, None], xs].copy()
        feature[np.max(abs(feature.astype(int) - bg), axis=2) <= 16] = 0
        out.write(name.encode('ascii'))
        out.write(feature.tobytes())
print('Prepared 12 piece templates from native screenshot.')
fixtures = ROOT / 'app/src/test/resources'
fixtures.mkdir(parents=True, exist_ok=True)
source = Image.fromarray(image)
for name, picture in [('duo-black-e4', source), ('duo-black-e4-half', source.resize((720, 1544)))]:
    with gzip.open(fixtures / f'{name}.rgb.gz', 'wb') as out:
        out.write(struct.pack('>II', picture.width, picture.height))
        out.write(picture.tobytes())

selection_path = ROOT / 'artifacts/tip-022-live.png'
if selection_path.exists():
    selection = Image.open(selection_path).convert('RGB')
    for name, picture in [('duo-black-selected', selection), ('duo-black-selected-half', selection.resize((720, 1544)))]:
        with gzip.open(fixtures / f'{name}.rgb.gz', 'wb') as out:
            out.write(struct.pack('>II', picture.width, picture.height))
            out.write(picture.tobytes())

for piece in 'pnbrqk':
    path = ROOT / f'artifacts/tip-023-clean-selected-{piece}.png'
    if not path.exists():
        continue
    selection = Image.open(path).convert('RGB')
    for suffix, picture in [('', selection), ('-half', selection.resize((720, 1544)))]:
        with gzip.open(fixtures / f'duo-black-selected-{piece}{suffix}.rgb.gz', 'wb') as out:
            out.write(struct.pack('>II', picture.width, picture.height))
            out.write(picture.tobytes())

capture_selection = ROOT / 'artifacts/tip-024-queen-live.png'
if capture_selection.exists():
    picture = Image.open(capture_selection).convert('RGB')
    for suffix, sample in [('', picture), ('-half', picture.resize((720, 1544)))]:
        with gzip.open(fixtures / f'duo-black-selected-q-captures{suffix}.rgb.gz', 'wb') as out:
            out.write(struct.pack('>II', sample.width, sample.height))
            out.write(sample.tobytes())

pawn_selection = ROOT / 'artifacts/auto-drag-black-e7-selected.png'
if pawn_selection.exists():
    picture = Image.open(pawn_selection).convert('RGB')
    for suffix, sample in [('', picture), ('-half', picture.resize((720, 1544)))]:
        with gzip.open(fixtures / f'duo-black-e7-selected{suffix}.rgb.gz', 'wb') as out:
            out.write(struct.pack('>II', sample.width, sample.height))
            out.write(sample.tobytes())
