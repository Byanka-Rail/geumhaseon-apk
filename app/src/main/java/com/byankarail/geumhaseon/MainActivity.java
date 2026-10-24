package com.byankarail.geumhaseon;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "geumhaseon_updater";
    private static final String CURRENT_HTML = "current.html";
    private static final String BACKUP_HTML = "backup.html";

    private static final int BUNDLED_BUILD = 18601;
    private static final String BUNDLED_VERSION = "1.86.1";

    private static final String RAW_BASE =
            "https://raw.githubusercontent.com/Byanka-Rail/geumhaseon-apk/main/";
    private static final String UPDATE_JSON = RAW_BASE + "update.json";
    private static final long AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private static final int FILE_CHOOSER_REQUEST = 9011;

    private WebView webView;
    private SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ValueCallback<Uri[]> fileChooserCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(6, 7, 10));
        getWindow().setNavigationBarColor(Color.rgb(6, 7, 10));

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prepareBundledGame();
        setupWebView();
        loadGame();
        maybeAutoCheckUpdate();
    }

    private void prepareBundledGame() {
        File current = new File(getFilesDir(), CURRENT_HTML);
        File backup = new File(getFilesDir(), BACKUP_HTML);
        boolean hasCurrent = current.exists() && current.length() > 1000;
        int currentBuild = prefs.getInt("current_build", 0);

        // Keep a downloaded/current build when it is already this bundled build or newer.
        if (hasCurrent && currentBuild >= BUNDLED_BUILD) return;

        try {
            // When an APK update carries a newer bundled game, preserve the previous HTML
            // as the normal one-step rollback target before promoting the bundled build.
            if (hasCurrent) {
                copyFile(current, backup);
                prefs.edit()
                        .putInt("backup_build", currentBuild)
                        .putString("backup_version",
                                prefs.getString("current_version", "이전 버전"))
                        .apply();
            }

            try (InputStream in = getAssets().open("game.html");
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(current))) {
                copy(in, out);
            }

            prefs.edit()
                    .putInt("current_build", BUNDLED_BUILD)
                    .putString("current_version", BUNDLED_VERSION)
                    .apply();
        } catch (Exception e) {
            Toast.makeText(this, "내장 게임 준비 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void setupWebView() {
        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    fileChooserCallback = null;
                    Toast.makeText(MainActivity.this, "파일 선택기를 열 수 없습니다.", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("geumhaseon.local".equalsIgnoreCase(u.getHost()) && "/game.html".equals(u.getPath())) {
                    try {
                        return new WebResourceResponse(
                                "text/html", "UTF-8",
                                new BufferedInputStream(new FileInputStream(new File(getFilesDir(), CURRENT_HTML))));
                    } catch (Exception e) {
                        return new WebResourceResponse("text/plain", "UTF-8",
                                new ByteArrayInputStream(("게임 파일 읽기 실패: " + e.getMessage())
                                        .getBytes(StandardCharsets.UTF_8)));
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectDownloadBridge();
            }
        });
    }

    private void loadGame() {
        webView.loadUrl("https://geumhaseon.local/game.html");
    }

    private void injectDownloadBridge() {
        String js = "(function(){" +
                "if(window.__GEUMHASEON_ANDROID_DL)return;window.__GEUMHASEON_ANDROID_DL=1;" +
                "var old=HTMLAnchorElement.prototype.click;" +
                "HTMLAnchorElement.prototype.click=function(){" +
                "try{var a=this;if(a.download&&a.href&&a.href.indexOf('blob:')===0){" +
                "fetch(a.href).then(function(r){return r.blob()}).then(function(b){" +
                "var fr=new FileReader();fr.onloadend=function(){" +
                "var x=String(fr.result||'');var p=x.indexOf(',');" +
                "AndroidBridge.saveBase64(a.download||'GEUMHASEON_export.bin',b.type||'application/octet-stream',p>=0?x.substring(p+1):'');" +
                "};fr.readAsDataURL(b);});return;} }catch(e){}" +
                "return old.apply(this,arguments);};" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    private void maybeAutoCheckUpdate() {
        long last = prefs.getLong("last_check_ms", 0L);
        if (System.currentTimeMillis() - last >= AUTO_CHECK_INTERVAL_MS) {
            checkForUpdates(false);
        }
    }

    private void checkForUpdates(boolean manual) {
        io.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(UPDATE_JSON).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(10000);
                c.setUseCaches(false);
                c.setRequestProperty("Cache-Control", "no-cache");
                c.setRequestProperty("User-Agent", "GEUMHASEON-Android-Updater/1.0");

                String json;
                try (InputStream in = c.getInputStream()) {
                    json = new String(readAll(in, 1024 * 1024), StandardCharsets.UTF_8);
                }

                JSONObject o = new JSONObject(json);
                UpdateInfo info = new UpdateInfo();
                info.version = o.optString("version", "");
                info.build = o.optInt("build", 0);
                info.file = o.optString("file", "");
                info.notes = o.optString("notes", "");
                info.sha256 = o.optString("sha256", "");
                info.mandatory = o.optBoolean("mandatory", false);

                prefs.edit().putLong("last_check_ms", System.currentTimeMillis()).apply();
                int currentBuild = prefs.getInt("current_build", BUNDLED_BUILD);

                runOnUiThread(() -> {
                    if (info.build > currentBuild && !info.file.isEmpty()) {
                        showUpdateDialog(info);
                    } else if (manual) {
                        Toast.makeText(this, "현재 게임이 최신 버전입니다.", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                if (manual) runOnUiThread(() ->
                        Toast.makeText(this, "업데이트 확인 실패: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showUpdateDialog(UpdateInfo info) {
        String current = prefs.getString("current_version", BUNDLED_VERSION);
        String msg = "현재 " + current + "  →  새 버전 " + info.version;
        if (!info.notes.isEmpty()) msg += "\n\n" + info.notes;

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("금하선 업데이트")
                .setMessage(msg)
                .setPositiveButton("업데이트", (d, w) -> downloadAndApply(info));
        if (!info.mandatory) b.setNegativeButton("나중에", null);
        b.setCancelable(!info.mandatory);
        b.show();
    }

    private void downloadAndApply(UpdateInfo info) {
        Toast.makeText(this, "업데이트 다운로드 중…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            File current = new File(getFilesDir(), CURRENT_HTML);
            File backup = new File(getFilesDir(), BACKUP_HTML);
            File tmp = new File(getFilesDir(), "update.tmp");
            try {
                String url = info.file.startsWith("https://") ? info.file : RAW_BASE + encodePath(info.file);
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(30000);
                c.setUseCaches(false);
                c.setRequestProperty("Cache-Control", "no-cache");
                c.setRequestProperty("User-Agent", "GEUMHASEON-Android-Updater/1.0");

                long total = 0;
                try (InputStream in = new BufferedInputStream(c.getInputStream());
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        total += n;
                        if (total > 30L * 1024L * 1024L) throw new Exception("업데이트 파일이 너무 큽니다.");
                        out.write(buf, 0, n);
                    }
                }

                if (tmp.length() < 1000) throw new Exception("다운로드 파일이 비정상적으로 작습니다.");
                if (!looksLikeHtml(tmp)) throw new Exception("HTML 파일 형식이 아닙니다.");
                if (!info.sha256.isEmpty()) {
                    String got = sha256(tmp);
                    if (!got.equalsIgnoreCase(info.sha256.trim())) throw new Exception("SHA-256 검증 실패");
                }

                if (backup.exists()) backup.delete();
                copyFile(current, backup);

                prefs.edit()
                        .putInt("backup_build", prefs.getInt("current_build", BUNDLED_BUILD))
                        .putString("backup_version", prefs.getString("current_version", BUNDLED_VERSION))
                        .apply();

                if (current.exists() && !current.delete()) throw new Exception("기존 파일 교체 준비 실패");
                copyFile(tmp, current);
                tmp.delete();

                prefs.edit()
                        .putInt("current_build", info.build)
                        .putString("current_version", info.version)
                        .apply();

                runOnUiThread(() -> {
                    Toast.makeText(this, "업데이트 완료 · " + info.version, Toast.LENGTH_LONG).show();
                    webView.clearCache(false);
                    loadGame();
                });
            } catch (Exception e) {
                tmp.delete();
                runOnUiThread(() ->
                        Toast.makeText(this, "업데이트 실패 · 기존 버전을 유지합니다.\n" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showAppMenu() {
        String ver = prefs.getString("current_version", BUNDLED_VERSION);
        boolean hasBackup = new File(getFilesDir(), BACKUP_HTML).exists();
        String[] items = hasBackup
                ? new String[]{"게임으로 돌아가기", "업데이트 확인", "이전 버전 복구", "앱 종료"}
                : new String[]{"게임으로 돌아가기", "업데이트 확인", "앱 종료"};

        new AlertDialog.Builder(this)
                .setTitle("사격임무: 금하선 · " + ver)
                .setItems(items, (d, which) -> {
                    String item = items[which];
                    if (item.equals("업데이트 확인")) checkForUpdates(true);
                    else if (item.equals("이전 버전 복구")) confirmRollback();
                    else if (item.equals("앱 종료")) finish();
                })
                .show();
    }

    private void confirmRollback() {
        String prev = prefs.getString("backup_version", "이전 버전");
        new AlertDialog.Builder(this)
                .setTitle("이전 버전 복구")
                .setMessage(prev + " 버전으로 되돌릴까요?\n현재 다운로드판은 백업과 교환됩니다.")
                .setPositiveButton("복구", (d, w) -> rollback())
                .setNegativeButton("취소", null)
                .show();
    }

    private void rollback() {
        io.execute(() -> {
            File current = new File(getFilesDir(), CURRENT_HTML);
            File backup = new File(getFilesDir(), BACKUP_HTML);
            File swap = new File(getFilesDir(), "swap.html");
            try {
                if (!backup.exists()) throw new Exception("복구할 버전이 없습니다.");
                copyFile(current, swap);
                if (current.exists()) current.delete();
                copyFile(backup, current);
                backup.delete();
                copyFile(swap, backup);
                swap.delete();

                int cb = prefs.getInt("current_build", BUNDLED_BUILD);
                String cv = prefs.getString("current_version", BUNDLED_VERSION);
                int bb = prefs.getInt("backup_build", BUNDLED_BUILD);
                String bv = prefs.getString("backup_version", BUNDLED_VERSION);
                prefs.edit()
                        .putInt("current_build", bb).putString("current_version", bv)
                        .putInt("backup_build", cb).putString("backup_version", cv)
                        .apply();

                runOnUiThread(() -> {
                    Toast.makeText(this, "이전 버전 복구 완료", Toast.LENGTH_LONG).show();
                    webView.clearCache(false);
                    loadGame();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "복구 실패: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else showAppMenu();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileChooserCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileChooserCallback.onReceiveValue(result);
            fileChooserCallback = null;
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidBridge");
            webView.destroy();
        }
        super.onDestroy();
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void saveBase64(String filename, String mime, String base64) {
            io.execute(() -> {
                try {
                    byte[] data = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                    saveToDownloads(sanitizeFilename(filename), mime, data);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "다운로드/GEUMHASEON에 저장했습니다.", Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "파일 저장 실패: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        }
    }

    private void saveToDownloads(String filename, String mime, byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, mime == null || mime.isEmpty() ? guessMime(filename) : mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GEUMHASEON");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("Downloads 항목 생성 실패");
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new Exception("Downloads 쓰기 실패");
                out.write(data);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
        } else {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "GEUMHASEON");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("저장 폴더 생성 실패");
            try (OutputStream out = new FileOutputStream(new File(dir, filename))) {
                out.write(data);
            }
        }
    }

    private static String sanitizeFilename(String s) {
        String x = (s == null || s.trim().isEmpty()) ? "GEUMHASEON_export.bin" : s.trim();
        return x.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String guessMime(String name) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(name);
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
        return m == null ? "application/octet-stream" : m;
    }

    private static String encodePath(String path) throws Exception {
        String[] parts = path.split("/");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) b.append('/');
            b.append(URLEncoder.encode(parts[i], "UTF-8").replace("+", "%20"));
        }
        return b.toString();
    }

    private static boolean looksLikeHtml(File f) throws Exception {
        byte[] first = new byte[(int) Math.min(8192, f.length())];
        try (InputStream in = new FileInputStream(f)) {
            int n = in.read(first);
            if (n <= 0) return false;
            String s = new String(first, 0, n, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            return s.contains("<!doctype html") || s.contains("<html");
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte b : md.digest()) s.append(String.format(Locale.ROOT, "%02x", b));
        return s.toString();
    }

    private static byte[] readAll(InputStream in, int max) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0, n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > max) throw new Exception("응답이 너무 큽니다.");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
    }

    private static void copyFile(File from, File to) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(from));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(to))) {
            copy(in, out);
        }
    }

    private static class UpdateInfo {
        String version;
        int build;
        String file;
        String notes;
        String sha256;
        boolean mandatory;
    }
}
