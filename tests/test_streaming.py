import sys
from pathlib import Path
import struct
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
from streaming import Segmenter, FRAME_BYTES, translation_route

SILENCE = bytes(FRAME_BYTES)
VOICE = struct.pack('<3200h', *([4000, -4000] * 1600))


class StreamingTests(unittest.TestCase):
    def test_silence_never_produces_work(self):
        s = Segmenter()
        for _ in range(1000):
            self.assertIsNone(s.push(SILENCE))
        self.assertEqual(len(s.preroll), 1)
        self.assertEqual(len(s.frames), 0)

    def test_phrase_flushes_after_silence_and_keeps_preroll(self):
        s = Segmenter()
        s.push(SILENCE)
        for frame in [VOICE, VOICE, VOICE, SILENCE, SILENCE]:
            self.assertIsNone(s.push(frame))
        segment = s.push(SILENCE)
        self.assertEqual(segment, SILENCE + VOICE * 3 + SILENCE * 3)

    def test_continuous_speech_is_bounded(self):
        s = Segmenter()
        results = [s.push(VOICE) for _ in range(72)]
        self.assertEqual([len(x) for x in results if x], [24 * FRAME_BYTES] * 3)

    def test_reset_discards_pre_seek_audio(self):
        s = Segmenter()
        s.push(VOICE)
        s.push(VOICE)
        s.reset()
        for _ in range(4):
            self.assertIsNone(s.push(SILENCE))

    def test_short_click_is_ignored(self):
        s = Segmenter()
        for frame in [VOICE, SILENCE, SILENCE, SILENCE]:
            self.assertIsNone(s.push(frame))

    def test_bad_pcm_rejected(self):
        with self.assertRaises(ValueError):
            Segmenter().push(b'bad')

    def test_installed_route_needs_no_download(self):
        self.assertEqual(translation_route({('en', 'ru')}, set(), 'en', 'ru'), [])
        self.assertEqual(translation_route({('ja', 'en'), ('en', 'ru')}, set(), 'ja', 'ru'), [])

    def test_missing_pivot_only_downloads_missing_pair(self):
        self.assertEqual(translation_route({('en', 'ru')}, {('ja', 'en')}, 'ja', 'ru'), [('ja', 'en')])

    def test_direct_route_preferred(self):
        self.assertEqual(translation_route(set(), {('ja', 'ru'), ('ja', 'en'), ('en', 'ru')}, 'ja', 'ru'), [('ja', 'ru')])

    def test_same_language_needs_no_model(self):
        self.assertEqual(translation_route(set(), set(), 'ru', 'ru'), [])

    def test_unavailable_route_fails_clearly(self):
        with self.assertRaisesRegex(ValueError, 'Argos'):
            translation_route(set(), {('ja', 'en')}, 'ja', 'ru')


if __name__ == '__main__':
    unittest.main()
