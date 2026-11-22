package org.livesubtitles.android;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class Models {
    interface Progress { void update(String message, int percent); }
    static File folder(Context c) { return new File(c.getFilesDir(), "models"); }
    static File whisper(Context c) { return new File(folder(c), "whisper.bin"); }
    static File translation(Context c) { return new File(folder(c), "translation/model"); }
    static File tokenizer(Context c) { return new File(folder(c), "translation/sentencepiece.model"); }
    static final Set<String> REQUIRED = new HashSet<>(Arrays.asList(
        "model/model.bin", "model/config.json", "model/shared_vocabulary.json", "sentencepiece.model"));

    static JSONObject manifest(Context c) throws Exception {
        try (InputStream input = c.getAssets().open("models.json")) {
            return new JSONObject(new String(readAll(input), StandardCharsets.UTF_8));
        }
    }
    static boolean ready(Context c) {
        try {
            File marker = new File(folder(c), "ready.json");
            if (!marker.isFile()) return false;
            JSONObject installed = new JSONObject(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8));
            JSONObject expected = manifest(c);
            if (!installed.getString("whisper").equals(expected.getJSONObject("whisper").getString("sha256")) ||
                !installed.getString("translation").equals(expected.getJSONObject("translation").getString("sha256"))) return false;
            if (whisper(c).length() != expected.getJSONObject("whisper").getLong("bytes")) return false;
            for (String name : REQUIRED) if (!new File(folder(c), "translation/" + name).isFile()) return false;
            return true;
        } catch (Exception ignored) { return false; }
    }

    static void install(Context c, AtomicBoolean cancelled, Progress progress) throws Exception {
        if (ready(c)) return;
        if (c.getFilesDir().getUsableSpace() < 700L * 1024 * 1024) throw new IOException("Для установки нужно около 700 МБ свободного места.");
        JSONObject spec = manifest(c);
        File stage = new File(c.getFilesDir(), "models-new");
        File backup = new File(c.getFilesDir(), "models-old");
        File archive = new File(c.getCacheDir(), "translation-download.part");
        erase(stage); stage.mkdirs();
        try {
            download(spec.getJSONObject("whisper"), new File(stage, "whisper.bin"), cancelled,
                (message, value) -> progress.update("Распознавание: " + value + "%", value * 14 / 100));
            download(spec.getJSONObject("translation"), archive, cancelled,
                (message, value) -> progress.update("Перевод: " + value + "%", 14 + value * 84 / 100));
            checkCancelled(cancelled);
            progress.update("Устанавливаю модель перевода…", 98);
            extract(archive, new File(stage, "translation"), cancelled);
            JSONObject installed = new JSONObject();
            installed.put("whisper", spec.getJSONObject("whisper").getString("sha256"));
            installed.put("translation", spec.getJSONObject("translation").getString("sha256"));
            Files.write(new File(stage, "ready.json").toPath(), installed.toString().getBytes(StandardCharsets.UTF_8));
            checkCancelled(cancelled);
            erase(backup);
            File destination = folder(c);
            if (destination.exists() && !destination.renameTo(backup)) throw new IOException("Не удалось обновить модели.");
            if (!stage.renameTo(destination)) {
                if (backup.exists()) backup.renameTo(destination);
                throw new IOException("Не удалось установить модели.");
            }
            erase(backup);
            progress.update("Модели готовы. Можно запускать перевод.", 100);
        } finally { archive.delete(); erase(stage); }
    }

    private static void download(JSONObject spec, File destination, AtomicBoolean cancelled, Progress progress) throws Exception {
        URL url = new URL(spec.getString("url"));
        long expectedSize = spec.getLong("bytes");
        HttpURLConnection connection = null;
        try {
            for (int redirects = 0; redirects < 10; redirects++) {
                checkCancelled(cancelled);
                if (!url.getProtocol().equals("https")) throw new IOException("Модели загружаются только по HTTPS.");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(20000); connection.setReadTimeout(45000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "LiveSubtitles-Android/0.1");
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect(); connection = null;
                    if (location == null) throw new IOException("Сервер не указал адрес модели.");
                    url = new URL(url, location); continue;
                }
                if (code != 200) throw new IOException("Сервер моделей ответил: " + code);
                break;
            }
            if (connection == null) throw new IOException("Слишком много переадресаций.");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long read = 0; int last = -1;
            try (InputStream input = new BufferedInputStream(connection.getInputStream()); OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                byte[] buffer = new byte[64 * 1024]; int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled(cancelled);
                    read += count;
                    if (read > expectedSize) throw new IOException("Размер модели не совпал.");
                    digest.update(buffer, 0, count); output.write(buffer, 0, count);
                    int percent = (int) (read * 100 / expectedSize);
                    if (percent != last) { last = percent; progress.update("", percent); }
                }
            }
            if (read != expectedSize || !hex(digest.digest()).equals(spec.getString("sha256"))) {
                destination.delete(); throw new IOException("Проверка SHA-256 модели не прошла. Повторите загрузку.");
            }
        } finally { if (connection != null) connection.disconnect(); }
    }

    private static void extract(File archive, File output, AtomicBoolean cancelled) throws Exception {
        Set<String> found = new HashSet<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                checkCancelled(cancelled);
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                int slash = name.indexOf('/');
                if (slash < 0) continue;
                String relative = name.substring(slash + 1);
                if (!REQUIRED.contains(relative) && !relative.equals("metadata.json") && !relative.equals("README.md")) continue;
                if (!found.add(relative)) throw new IOException("Повтор файла в модели.");
                File file = new File(output, relative);
                if (!file.getCanonicalPath().startsWith(output.getCanonicalPath() + File.separator)) throw new IOException("Некорректный архив модели.");
                file.getParentFile().mkdirs();
                try (OutputStream target = new BufferedOutputStream(new FileOutputStream(file))) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        checkCancelled(cancelled); total += count;
                        if (total > 400L * 1024 * 1024) throw new IOException("Модель превышает допустимый размер.");
                        target.write(buffer, 0, count);
                    }
                }
            }
        }
        if (!found.containsAll(REQUIRED)) throw new IOException("В модели не хватает обязательных файлов.");
    }
    private static void checkCancelled(AtomicBoolean cancelled) throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Загрузка отменена.");
    }
    private static void erase(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) erase(child); }
        file.delete();
    }
    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) result.write(buffer, 0, count);
        return result.toByteArray();
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    private Models() { }
}
