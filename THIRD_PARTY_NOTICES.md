# Сторонние компоненты

Лицензия MIT в `LICENSE` относится к исходному коду LiveSubtitles. Библиотеки, которые устанавливает локальный сервер, и скачиваемые модели распространяются на условиях их собственных лицензий.

Основные компоненты:

- [faster-whisper](https://github.com/SYSTRAN/faster-whisper) — распознавание речи.
- [Whisper](https://github.com/openai/whisper) — исходные модели распознавания; используются преобразованные модели faster-whisper.
- [CTranslate2](https://github.com/OpenNMT/CTranslate2) — локальный запуск моделей.
- [Argos Translate](https://github.com/argosopentech/argos-translate) — каталог, установка и токенизация моделей перевода.
- [Модели Argos](https://github.com/argosopentech/argospm-index) — условия зависят от конкретного пакета. Проверяйте README и метаданные скачанной модели в `server/data/packages`.
- [FastAPI](https://github.com/fastapi/fastapi), [Starlette](https://github.com/encode/starlette), [Uvicorn](https://github.com/encode/uvicorn), [websockets](https://github.com/python-websockets/websockets) — локальный сервер и соединение с расширением.
- [uv](https://github.com/astral-sh/uv) — установка отдельного Python и зависимостей.

Полный список устанавливаемых версий находится в `server/requirements.txt`. Лицензии зависимостей доступны в их проектах и установленном окружении. Архив релиза содержит исходники приложения: сторонние библиотеки, Python, модели и пользовательские данные в него не входят.

LiveSubtitles не связан с Google, Amazon или Prime Video и не является их официальным продуктом.
