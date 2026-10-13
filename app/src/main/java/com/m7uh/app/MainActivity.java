package com.m7u.downloader;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private File downloadDir;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        downloadDir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "M7U");
        if (!downloadDir.exists()) downloadDir.mkdirs();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.loadUrl("file:///android_asset/index.html");

        executor.execute(() -> {
            try {
                YoutubeDL.getInstance().init(getApplication());
                FFmpeg.getInstance().init(getApplication());
                runOnUiThread(() -> toast("✓ جاهز للتحميل"));
            } catch (Exception e) {
                runOnUiThread(() -> toast("خطأ: " + e.getMessage()));
            }
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void callJs(String code) {
        runOnUiThread(() -> webView.evaluateJavascript(code, null));
    }

    public class Bridge {
        @JavascriptInterface
        public void download(final String url, final String format) {
            if (url == null || url.trim().isEmpty()) {
                callJs("window.onError('الرابط فارغ');");
                return;
            }
            executor.execute(() -> {
                try {
                    String cleanUrl = url.trim();
                    File downloadedFile;

                    if (cleanUrl.contains("tiktok.com")) {
                        String apiUrl = "https://www.tikwm.com/api/?hd=1&url=" + cleanUrl;
                        String jsonResponse = httpGet(apiUrl);
                        JSONObject json = new JSONObject(jsonResponse);

                        if (json.optInt("code", -1) != 0) {
                            throw new Exception("فشل جلب الفيديو: " + json.optString("msg"));
                        }

                        JSONObject data = json.getJSONObject("data");
                        String videoUrl;
                        String ext;

                        if ("mp3".equals(format)) {
                            videoUrl = data.getString("music");
                            ext = ".mp3";
                        } else {
                            // نجرب HD أولاً، وإذا مو موجود نرجع للعادي
                            String hd = data.optString("hdplay", "");
                            if (hd != null && !hd.isEmpty() && !hd.equals("null")) {
                                videoUrl = hd;
                            } else {
                                videoUrl = data.getString("play");
                            }
                            ext = ".mp4";
                        }

                        // === الحل: تنظيف الاسم ثم إضافة الامتداد ===
                        String baseTitle = data.optString("title", "tiktok_video");
                        baseTitle = baseTitle.replaceAll("[^a-zA-Z0-9\\u0600-\\u06FF _-]", "_").trim();
                        // نُبقي مساحة للامتداد
                        if (baseTitle.length() > 70) baseTitle = baseTitle.substring(0, 70);
                        if (baseTitle.isEmpty()) baseTitle = "tiktok_" + System.currentTimeMillis();

                        String finalName = baseTitle + ext;
                        File tempFile = new File(downloadDir, finalName);
                        downloadFile(videoUrl, tempFile);
                        downloadedFile = tempFile;

                    } else {
                        YoutubeDLRequest request = new YoutubeDLRequest(cleanUrl);
                        request.addOption("-o", downloadDir.getAbsolutePath() + "/%(title).80s.%(ext)s");
                        request.addOption("--no-playlist");
                        request.addOption("--restrict-filenames");
                        request.addOption("--no-warnings");
                        request.addOption("--no-check-certificates");

                        if (cleanUrl.contains("instagram.com")) {
                            request.addOption("--referer", "https://www.instagram.com/");
                        } else if (cleanUrl.contains("youtube.com") || cleanUrl.contains("youtu.be")) {
                            request.addOption("--extractor-args", "youtube:player_client=android,web");
                        }

                        if ("mp3".equals(format)) {
                            request.addOption("-x");
                            request.addOption("--audio-format", "mp3");
                            request.addOption("--audio-quality", "0");
                        } else {
                            request.addOption("-f", "bv*+ba/b");
                            request.addOption("--merge-output-format", "mp4");
                        }

                        YoutubeDL.getInstance().execute(request);
                        downloadedFile = getLatestFile();
                    }

                    if (downloadedFile == null || !downloadedFile.exists()) {
                        throw new Exception("ما تم تنزيل الملف");
                    }

                    try {
                        publishToPublicDownloads(downloadedFile);
                    } catch (Exception ignored) {}

                    String name = downloadedFile.getName();
                    callJs("window.onComplete('" + name.replace("'", "") + "');");

                } catch (Exception e) {
                    String msg = e.getMessage() == null ? "فشل التحميل" : e.getMessage();
                    msg = msg.replace("'", "").replace("\n", " ");
                    if (msg.length() > 200) msg = msg.substring(0, 200);
                    callJs("window.onError('" + msg + "');");
                }
            });
        }

        @JavascriptInterface
        public String getDownloads() {
            File[] files = downloadDir.listFiles();
            if (files == null) return "[]";
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (File f : files) {
                if (f.isDirectory()) continue;
                if (f.getName().endsWith(".part")) continue;
                if (!first) sb.append(",");
                first = false;
                String name = f.getName().replace("\\", "\\\\").replace("\"", "\\\"");
                sb.append("{\"name\":\"").append(name)
                  .append("\",\"size\":").append(f.length())
                  .append(",\"path\":\"").append(f.getAbsolutePath().replace("\"", "\\\""))
                  .append("\",\"date\":").append(f.lastModified()).append("}");
            }
            sb.append("]");
            return sb.toString();
        }

        @JavascriptInterface
        public void deleteFile(String path) {
            File f = new File(path);
            if (f.exists() && f.getAbsolutePath().startsWith(downloadDir.getAbsolutePath())) {
                f.delete();
            }
        }

        @JavascriptInterface
        public void openFile(String path) {
            try {
                File f = new File(path);
                String mime = getMimeType(f.getName());
                Uri uri = FileProvider.getUriForFile(
                    MainActivity.this,
                    getPackageName() + ".fileprovider",
                    f);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, mime);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
            } catch (Exception e) {
                try {
                    File f = new File(path);
                    Uri uri = FileProvider.getUriForFile(
                        MainActivity.this,
                        getPackageName() + ".fileprovider",
                        f);
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType(getMimeType(f.getName()));
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share, "فتح باستخدام"));
                } catch (Exception e2) {
                    toast("ماكو تطبيق يفتح هذا الملف");
                }
            }
        }
    }

    private void publishToPublicDownloads(File source) throws Exception {
        String fileName = source.getName();
        String mime = getMimeType(fileName);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/M7U");

            Uri uri = getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return;

            try (OutputStream out = getContentResolver().openOutputStream(uri);
                 FileInputStream in = new FileInputStream(source)) {
                if (out == null) return;
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            }
        } else {
            File downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
            File m7uDir = new File(downloads, "M7U");
            if (!m7uDir.exists()) m7uDir.mkdirs();
            File target = new File(m7uDir, fileName);

            try (FileOutputStream out = new FileOutputStream(target);
                 FileInputStream in = new FileInputStream(source)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            }
        }
    }

    private String getMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".aac")) return "audio/aac";
        return "application/octet-stream";
    }

    private String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(20000);

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) response.append(line);
        reader.close();
        return response.toString();
    }

    private void downloadFile(String fileUrl, File outFile) throws Exception {
        URL url = new URL(fileUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(20000);

        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }

    private File getLatestFile() {
        File[] files = downloadDir.listFiles();
        if (files == null || files.length == 0) return null;
        File latest = null;
        for (File f : files) {
            if (f.isDirectory()) continue;
            if (f.getName().endsWith(".part")) continue;
            if (f.getName().endsWith(".ytdl")) continue;
            if (latest == null || f.lastModified() > latest.lastModified()) latest = f;
        }
        return latest;
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
