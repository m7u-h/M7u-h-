package com.m7u.downloader;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
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
                    File outFile;

                    if (cleanUrl.contains("tiktok.com")) {
                        // === تيك توك: استخدام خادم TikWM API ===
                        String apiUrl = "https://www.tikwm.com/api/?url=" + cleanUrl;
                        String jsonResponse = httpGet(apiUrl);

                        JSONObject json = new JSONObject(jsonResponse);
                        if (json.getInt("code") != 0) {
                            throw new Exception("فشل جلب الفيديو: " + json.optString("msg"));
                        }

                        JSONObject data = json.getJSONObject("data");
                        String videoUrl;
                        String title;

                        if ("mp3".equals(format)) {
                            videoUrl = data.getString("music");
                            title = data.getString("title") + ".mp3";
                        } else {
                            videoUrl = data.getString("play"); // MP4 بدون علامة مائية
                            title = data.getString("title") + ".mp4";
                        }

                        title = title.replaceAll("[^a-zA-Z0-9\\u0600-\\u06FF. ]", "_");
                        if (title.length() > 80) title = title.substring(0, 80);
                        if (title.trim().isEmpty()) title = "tiktok_" + System.currentTimeMillis() + ("mp3".equals(format) ? ".mp3" : ".mp4");

                        outFile = new File(downloadDir, title);
                        downloadFile(videoUrl, outFile);

                    } else {
                        // === يوتيوب + إنستكرام: استخدام yt-dlp ===
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
                        outFile = getLatestFile();
                    }

                    String name = outFile != null ? outFile.getName() : "الملف";
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
                Uri uri = FileProvider.getUriForFile(
                    MainActivity.this,
                    getPackageName() + ".fileprovider",
                    f);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "*/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
            } catch (Exception e) {
                toast("ماكو تطبيق يفتح هذا الملف");
            }
        }
    }

    // === دوال مساعدة ===

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
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
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
        File latest = files[0];
        for (File f : files) {
            if (f.lastModified() > latest.lastModified()) latest = f;
        }
        return latest;
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
    }
