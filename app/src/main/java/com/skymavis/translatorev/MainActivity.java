package com.skymavis.translatorev;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TranslatorEV - vo Android cho giao dien web trong assets/index.html.
 *
 * Giao dien web goi sang Java qua doi tuong "AndroidBridge":
 *   httpGet(id, url)          -> tra ve window.__net(id, ok, payload)
 *   startListening(langCode)  -> tra ve window.__stt("status"|"result"|"end"|"error", data)
 *   stopListening()
 *   speak(text, langCode) / stopSpeak()
 *   copy(text) / saveFile(name, body) / toast(msg)
 *
 * Ly do goi mang bang Java: WebView nap trang tu file:// nen fetch() tu
 * JavaScript bi chan CORS. Goi bang HttpURLConnection thi khong vuong.
 */
public class MainActivity extends android.app.Activity {

    private static final String TAG = "TranslatorEV";
    private static final int REQ_MIC = 1001;

    private WebView web;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean listening = false;
    private String pendingLang = null;          // cho toi khi nguoi dung cap quyen micro
    private boolean leaving = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    // ====================================================================
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception ignored) { }
                    return true;    // link ngoai mo bang trinh duyet
                }
                return false;
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.loadUrl("file:///android_asset/index.html");

        tts = new TextToSpeech(this, status -> ttsReady = (status == TextToSpeech.SUCCESS));
    }

    // -------------------------------------------------------- vong doi
    @Override
    protected void onDestroy() {
        try { if (recognizer != null) recognizer.destroy(); } catch (Exception ignored) { }
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception ignored) { }
        pool.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { if (tts != null) tts.stop(); } catch (Exception ignored) { }
        stopRecognizer();
    }

    /** Nut Back: hoi giao dien web truoc, no khong xu ly thi moi thoat. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && !leaving) {
            web.evaluateJavascript("(function(){try{return !!(window.__back&&window.__back())}catch(e){return false}})()",
                    value -> {
                        if (!"true".equals(value)) {
                            leaving = true;
                            finish();
                        }
                    });
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // -------------------------------------------------------- goi ve JS
    private void toJs(String js) {
        ui.post(() -> {
            try { web.evaluateJavascript(js, null); }
            catch (Exception e) { Log.w(TAG, "evaluateJavascript: " + e); }
        });
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    private void stt(String kind, String data) {
        toJs("window.__stt && window.__stt(" + q(kind) + "," + q(data) + ")");
    }

    // ====================================================================
    //  Nhan dang giong noi
    // ====================================================================
    private void startRecognizer(String langCode) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stt("error", "Máy chưa có dịch vụ nhận dạng giọng nói. "
                    + "Hãy cài hoặc bật ứng dụng Google trong CH Play.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingLang = langCode;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        stopRecognizer();

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { stt("status", "Đang nghe… hãy nói"); }
            @Override public void onBeginningOfSpeech() { stt("status", "Đang ghi âm…"); }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { stt("status", "Đang nhận dạng…"); }
            @Override public void onPartialResults(Bundle b) { }
            @Override public void onEvent(int t, Bundle b) { }

            @Override
            public void onError(int code) {
                listening = false;
                stt("error", errorText(code));
            }

            @Override
            public void onResults(Bundle b) {
                listening = false;
                ArrayList<String> hits = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (hits != null && !hits.isEmpty() && hits.get(0).trim().length() > 0) {
                    stt("result", hits.get(0));
                    stt("end", "");
                } else {
                    stt("error", "Không nghe rõ, hãy thử lại.");
                }
            }
        });

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, langCode);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langCode);
        i.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, langCode);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());

        listening = true;
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            listening = false;
            stt("error", "Không bật được micro: " + e.getMessage());
        }
    }

    private void stopRecognizer() {
        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Exception ignored) { }
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
        if (listening) {
            listening = false;
            stt("end", "");
        }
    }

    private static String errorText(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_AUDIO:              return "Lỗi thu âm.";
            case SpeechRecognizer.ERROR_CLIENT:             return "";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                                                            return "Ứng dụng chưa được cấp quyền micro.";
            case SpeechRecognizer.ERROR_NETWORK:            return "Lỗi mạng khi nhận dạng giọng nói.";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:    return "Mạng phản hồi quá chậm.";
            case SpeechRecognizer.ERROR_NO_MATCH:           return "Không nghe rõ, hãy thử lại.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:    return "Bộ nhận dạng đang bận, thử lại sau giây lát.";
            case SpeechRecognizer.ERROR_SERVER:             return "Máy chủ nhận dạng báo lỗi.";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:     return "Không nghe thấy giọng nói nào.";
            default:                                        return "Lỗi micro (mã " + code + ").";
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req != REQ_MIC) return;
        String lang = pendingLang;
        pendingLang = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && lang != null) {
            startRecognizer(lang);
        } else {
            stt("error", "Bạn chưa cho phép dùng micro. "
                    + "Vào Cài đặt → Ứng dụng → TranslatorEV → Quyền → Micro để bật.");
        }
    }

    // ====================================================================
    //  Doc to
    // ====================================================================
    private void speakNow(String text, String langCode) {
        if (tts == null || !ttsReady) {
            toastNow("Bộ đọc giọng nói chưa sẵn sàng.");
            return;
        }
        Locale loc = "vi-VN".equals(langCode) ? new Locale("vi", "VN") : Locale.US;
        int r = tts.setLanguage(loc);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            if ("vi-VN".equals(langCode)) {
                toastNow("Điện thoại chưa có giọng đọc tiếng Việt. "
                        + "Cài trong Cài đặt → Ngôn ngữ → Đầu ra chuyển văn bản thành lời nói.");
                return;
            }
            tts.setLanguage(Locale.US);
        }
        tts.setSpeechRate(1.0f);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tev");
    }

    private void toastNow(String m) {
        ui.post(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show());
    }

    // ====================================================================
    //  Tai noi dung tu mang (tranh CORS cua WebView)
    // ====================================================================
    private void httpGetAsync(String id, String url) {
        pool.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(12000);
                c.setReadTimeout(18000);
                c.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android) TranslatorEV/1.0");
                c.setRequestProperty("Accept", "application/json,text/plain,*/*");

                int code = c.getResponseCode();
                java.io.InputStream in = (code >= 200 && code < 400)
                        ? c.getInputStream() : c.getErrorStream();
                StringBuilder sb = new StringBuilder();
                if (in != null) {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(in, StandardCharsets.UTF_8));
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                }
                if (code >= 200 && code < 400) {
                    toJs("window.__net(" + q(id) + ",true," + q(sb.toString()) + ")");
                } else {
                    toJs("window.__net(" + q(id) + ",false," + q("HTTP " + code) + ")");
                }
            } catch (Exception e) {
                toJs("window.__net(" + q(id) + ",false,"
                        + q("không kết nối được (" + e.getClass().getSimpleName() + ")") + ")");
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    // ====================================================================
    //  Luu tep vao thu muc Tai ve
    // ====================================================================
    private void saveToDownloads(String name, String body) {
        pool.execute(() -> {
            try {
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                String mime = name.endsWith(".csv") ? "text/csv" : "text/plain";

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    v.put(MediaStore.Downloads.IS_PENDING, 1);
                    Uri uri = getContentResolver()
                            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new Exception("không tạo được tệp");
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    os.write(data);
                    os.close();
                    v.clear();
                    v.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, v, null, null);
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    FileOutputStream fo = new FileOutputStream(new File(dir, name));
                    fo.write(data);
                    fo.close();
                }
                toastNow("Đã lưu " + name + " vào thư mục Tải về");
            } catch (Exception e) {
                toastNow("Không lưu được tệp: " + e.getMessage());
            }
        });
    }

    // ====================================================================
    //  Cau noi JavaScript  ->  Java
    //  (cac ham nay chay o luong nen, nen viec gi dung UI phai post len ui)
    // ====================================================================
    private class Bridge {

        @JavascriptInterface
        public void startListening(String langCode) {
            final String lc = (langCode == null || langCode.isEmpty()) ? "en-US" : langCode;
            ui.post(() -> startRecognizer(lc));
        }

        @JavascriptInterface
        public void stopListening() {
            ui.post(MainActivity.this::stopRecognizer);
        }

        @JavascriptInterface
        public void speak(String text, String langCode) {
            final String t = text == null ? "" : text;
            final String lc = langCode == null ? "en-US" : langCode;
            ui.post(() -> speakNow(t, lc));
        }

        @JavascriptInterface
        public void stopSpeak() {
            ui.post(() -> { if (tts != null) tts.stop(); });
        }

        @JavascriptInterface
        public void httpGet(String id, String url) {
            httpGetAsync(id, url);
        }

        @JavascriptInterface
        public void copy(String text) {
            final String t = text == null ? "" : text;
            ui.post(() -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("TranslatorEV", t));
            });
        }

        @JavascriptInterface
        public void saveFile(String name, String body) {
            saveToDownloads(name == null ? "translatorev.txt" : name, body == null ? "" : body);
        }

        @JavascriptInterface
        public void toast(String msg) {
            toastNow(msg == null ? "" : msg);
        }
    }
}
