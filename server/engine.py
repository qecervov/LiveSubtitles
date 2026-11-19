"""Offline recognition and translation. Downloads models only when missing."""
import os
from pathlib import Path
from streaming import translation_route

DATA = Path(os.environ.get('LIVESUBTITLES_DATA', Path(__file__).parent / 'data')).resolve()
DATA.mkdir(parents=True, exist_ok=True)
# Keep model caches and settings inside this app rather than the user's home.
for name, folder in [('XDG_DATA_HOME', 'share'), ('XDG_CONFIG_HOME', 'config'),
                     ('XDG_CACHE_HOME', 'cache'), ('HF_HOME', 'huggingface'),
                     ('ARGOS_PACKAGES_DIR', 'packages')]:
    os.environ[name] = str(DATA / folder)
os.environ['ARGOS_CHUNK_TYPE'] = 'NONE'  # Short speech chunks need no sentence model.
os.environ['ARGOS_DEVICE_TYPE'] = 'cpu'
os.environ['ARGOS_COMPUTE_TYPE'] = 'int8'
os.environ['ARGOS_BEAM_SIZE'] = '2'
os.environ['HF_HUB_DISABLE_TELEMETRY'] = '1'
os.environ['DO_NOT_TRACK'] = '1'
os.environ['HF_HUB_DISABLE_IMPLICIT_TOKEN'] = '1'
os.environ.setdefault('HF_HUB_DISABLE_SYMLINKS_WARNING', '1')


class Engine:
    def __init__(self):
        self.status = 'Подготовка моделей… Первый запуск требует интернета.'
        self.error = None
        self.ready = False
        self.model = None
        self.available = None
        self.routes = {}
        self.translators = {}
        self.model_size = os.environ.get('WHISPER_MODEL', 'base')

    def load(self):
        from faster_whisper import WhisperModel
        from faster_whisper.utils import download_model
        self.status = f'Загрузка распознавания Whisper {self.model_size}…'
        cache = str(DATA / 'whisper')
        try:
            model_path = download_model(self.model_size, cache_dir=cache, local_files_only=True)
            model_folder = Path(model_path)
            if not all((model_folder / name).is_file() for name in ['model.bin', 'config.json', 'tokenizer.json']) or not list(model_folder.glob('vocabulary.*')):
                raise FileNotFoundError('Incomplete cached model')
        except Exception:
            model_path = download_model(self.model_size, cache_dir=cache)
        self.model = WhisperModel(model_path, device='cpu', compute_type='int8',
                                  cpu_threads=min(8, os.cpu_count() or 4))
        self.ensure_translation('en', 'ru')
        self.status = 'Готово. Обработка звука выполняется локально.'
        self.ready = True

    def ensure_translation(self, source, target):
        if source == target or (source, target) in self.routes:
            return
        import ctranslate2
        from argostranslate import package
        installed = {(p.from_code, p.to_code) for p in package.get_installed_packages()}
        # Use an installed pivot route without accessing the network.
        try:
            required = translation_route(installed, set(), source, target)
        except ValueError:
            if self.available is None:
                self.status = 'Получаю каталог языковых моделей Argos…'
                package.update_package_index()
                self.available = {(p.from_code, p.to_code): p for p in package.get_available_packages()}
            required = translation_route(installed, set(self.available), source, target)
        for pair in required:
            self.status = f'Скачиваю модель перевода {pair[0]} → {pair[1]}…'
            package.install_from_path(self.available[pair].download())
        self.status = f'Подготовка перевода {source} → {target}…'
        packages = {(p.from_code, p.to_code): p for p in package.get_installed_packages() if p.type == 'translate'}
        path = [(source, target)] if (source, target) in packages else [(source, 'en'), ('en', target)]
        path = [pair for pair in path if pair[0] != pair[1]]
        for pair in path:
            if pair not in self.translators:
                pkg = packages[pair]
                translator = ctranslate2.Translator(str(pkg.package_path / 'model'),
                    device='cpu', compute_type='int8', inter_threads=1,
                    intra_threads=min(4, os.cpu_count() or 4))
                self.translators[pair] = (pkg, translator)
        self.routes[(source, target)] = path
        self.translate_text('Hello.' if source == 'en' else '.', source, target)

    def translate_text(self, text, source, target):
        # Speech has already been split into short chunks. Run Argos model
        # packages directly with CTranslate2 to avoid extra sentence-model downloads.
        if source == target:
            return text
        for pair in self.routes[(source, target)]:
            pkg, translator = self.translators[pair]
            prefix = [[pkg.target_prefix]] if pkg.target_prefix else None
            result = translator.translate_batch([pkg.tokenizer.encode(text)],
                target_prefix=prefix, replace_unknowns=True, beam_size=2,
                num_hypotheses=1, length_penalty=0.2)[0]
            text = pkg.tokenizer.decode(result.hypotheses[0]).strip()
            if pkg.target_prefix and text.startswith(pkg.target_prefix):
                text = text[len(pkg.target_prefix):].strip()
        return text

    def process(self, pcm, source, target):
        import numpy as np
        audio = np.frombuffer(pcm, dtype='<i2').astype(np.float32) / 32768.0
        segments, info = self.model.transcribe(
            audio, language=None if source == 'auto' else source,
            beam_size=1, best_of=1, temperature=0,
            vad_filter=True, vad_parameters={'min_silence_duration_ms': 300},
            condition_on_previous_text=False,
        )
        text = ' '.join(s.text.strip() for s in segments
                        if s.no_speech_prob < 0.7 and s.avg_logprob > -1.2).strip()
        if not text:
            return None
        language = info.language
        self.ensure_translation(language, target)
        translated = self.translate_text(text, language, target)
        return {'type': 'subtitle', 'text': translated, 'original': text, 'language': language}
