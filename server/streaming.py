"""Bounded PCM segmentation; no ML imports, so the protocol is easy to test."""
from array import array
from collections import deque
import math
import sys

RATE = 16000
FRAME_BYTES = 6400  # 200 ms, signed little-endian int16 mono


class Segmenter:
    def __init__(self, threshold=0.008, max_frames=24):
        self.threshold = threshold
        self.max_frames = max_frames
        self.reset()

    def reset(self):
        self.frames = []
        self.preroll = deque(maxlen=1)
        self.silence = 0
        self.voiced = 0

    def push(self, frame):
        if len(frame) != FRAME_BYTES:
            raise ValueError('Ожидается 200 мс PCM 16 кГц mono int16.')
        samples = array('h')
        samples.frombytes(frame)
        if sys.byteorder != 'little':
            samples.byteswap()
        rms = math.sqrt(sum(x * x for x in samples) / len(samples)) / 32768
        voice = rms >= self.threshold
        if not self.frames and not voice:
            self.preroll.append(frame)
            return None
        if not self.frames:
            self.frames.extend(self.preroll)
            self.preroll.clear()
        self.frames.append(frame)
        self.voiced += int(voice)
        self.silence = 0 if voice else self.silence + 1
        if len(self.frames) >= self.max_frames or self.silence >= 3:
            result = b''.join(self.frames) if self.voiced >= 2 else None
            self.reset()
            return result
        return None


def translation_route(installed, available, source, target):
    """Return the missing direct packages, preferring a direct translation."""
    if source == target or (source, target) in installed:
        return []
    if (source, target) in available:
        return [(source, target)]
    route = [(source, 'en'), ('en', target)]
    route = [pair for pair in route if pair[0] != pair[1]]
    if all(pair in installed or pair in available for pair in route):
        return [pair for pair in route if pair not in installed]
    raise ValueError(f'Для перевода {source} → {target} пока нет локальной модели Argos. Выберите другой язык.')
