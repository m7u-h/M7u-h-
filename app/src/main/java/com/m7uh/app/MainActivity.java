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

import java.io.File;
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
                    YoutubeDLRequest request = new YoutubeDLRequest(url.trim());
                    request.addOption("-o", downloadDir.getAbsolutePath() + "/%(title).80s.%(ext)s");
                    request.addOption("--no-playlist");
                    request.addOption("--restrict-filenames");
                    request.addOption("--no-warnings");

                    if ("mp3".equals(format)) {
                        request.addOption("-x");
                        request.addOption("--audio-format", "mp3");
                        request.addOption("--audio-quality", "0");
                    } else {
                        request.addOption("-f",
                            "best[ext=mp4]/bestvideo[ext=mp4]+bestaudio[ext=m4a]/best");
                        request.addOption("--merge-output-format", "mp4");
                    }

                    YoutubeDL.getInstance().execute(request, (progress, etaInSeconds) -> {
                        callJs("window.onProgress(" + progress + "," + etaInSeconds + ");");
                    });

                    File latest = getLatestFile();
                    String name = latest != null ? latest.getName() : "الملف";
                    callJs("window.onComplete('" + name.replace("'", "") + "');");

                } catch (Exception e) {
                    String msg = e.getMessage() == null ? "فشل التحميل" : e.getMessage();
                    msg = msg.replace("'", "").replace("\n", " ");
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
