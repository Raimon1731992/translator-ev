package com.skymavis.translatorev;

import android.Manifest;
import android.app.Activity;
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
import android.speech.ModelDownloadListener;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TranslatorEV 1.1 - vo Android cho giao dien web trong assets/index.html.
 *
 * Cau noi JavaScript (doi tuong "AndroidBridge"):
 *   httpGet(id, url)                    -> window.__net(id, ok, payload)
 *   httpPost(id, url, headersJson, body)-> window.__net(id, ok, payload)
 *   startListening(langCode)            -> window.__stt(kind, data)
 *   stopListening()
 *   speak(text, langCode) / stopSpeak()
 *   copy(text) / saveFile(name, body) / toast(msg)
 *   checkLanguage(langCode)             -> window.__lang(langCode, state, detail)
 *
 * GHI CHU VE LOI MICRO MA 11 (ERROR_SERVER_DISCONNECTED):
 *   Ban 1.0 huy (destroy) roi tao lai SpeechRecognizer sau moi luot nghe.
 *   Viec ket noi lai dich vu nhan dang ngay lap tuc gay tranh chap va bao
 *   loi 11. Ban nay GIU MOT INSTANCE dung lai suot vong doi, chi cancel()
 *   giua cac luot, va chi huy khi that su gap loi 11 roi thu lai mot lan.
 *   Neu van hong thi chuyen sang hop thoai nhan dang san co cua Google.
 */
public class MainActivity extends Activity {

    private static final String TAG = "TranslatorEV";
    private static final int REQ_MIC = 1001;
    private static final int REQ_SPEECH_UI = 1002;

    private WebView web;
    private SpeechRecognizer recognizer;      // dung lai, khong huy moi luot
    private TextToSpeech tts;
    private boolean ttsReady = false;

    private boolean listening = false;
    private String currentLang = "en-US";     // ngon ngu cua luot nghe hien tai
    private int retryLeft = 0;                // so lan con duoc thu lai
    private String pendingPermissionLang = null;
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
                    return true;
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
        destroyRecognizer();
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception ignored) { }
        pool.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { if (tts != null) tts.stop(); } catch (Exception ignored) { }
        cancelListening();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && !leaving) {
            web.evaluateJavascript(
                "(function(){try{return !!(window.__back&&window.__back())}catch(e){return false}})()",
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
    //  NHAN DANG GIONG NOI
    // ====================================================================

    /** Tao instance mot lan roi dung lai; chi tao lai khi da bi huy. */
    private void ensureRecognizer() {
        if (recognizer != null) return;
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { stt("status", "Đang nghe… hãy nói"); }
            @Override public void onBeginningOfSpeech()      { stt("status", "Đang ghi âm…"); }
            @Override public void onRmsChanged(float v)      { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech()            { stt("status", "Đang nhận dạng…"); }
            @Override public void onPartialResults(Bundle b) { }
            @Override public void onEvent(int t, Bundle b)   { }

            @Override
            public void onError(int code) {
                listening = false;
                handleRecognitionError(code);
            }

            @Override
            public void onResults(Bundle b) {
                listening = false;
                retryLeft = 0;
                ArrayList<String> hits = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String best = pickBest(b, hits);
                if (best != null && best.trim().length() > 0) {
                    stt("result", best);
                    stt("end", "");
                } else {
                    stt("error", "Không nghe rõ, hãy thử lại.");
                }
            }
        });
    }

    /** Chon phuong an co do tin cay cao nhat, khong co thi lay phuong an dau. */
    private static String pickBest(Bundle b, ArrayList<String> hits) {
        if (hits == null || hits.isEmpty()) return null;
        try {
            float[] conf = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
            if (conf != null && conf.length == hits.size()) {
                int bi = 0;
                for (int i = 1; i < conf.length; i++) if (conf[i] > conf[bi]) bi = i;
                return hits.get(bi);
            }
        } catch (Exception ignored) { }
        return hits.get(0);
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.cancel(); }  catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
        listening = false;
    }

    /** Dung luot nghe nhung GIU instance - day la mau chot tranh loi 11. */
    private void cancelListening() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
        }
        if (listening) {
            listening = false;
            stt("end", "");
        }
    }

    private void startRecognizer(String langCode, boolean isRetry) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // Khong co dich vu nen -> di thang sang hop thoai cua he thong
            launchSpeechUi(langCode);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingPermissionLang = langCode;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }

        currentLang = langCode;
        if (!isRetry) retryLeft = 1;      // moi luot duoc thu lai mot lan

        ensureRecognizer();
        try { recognizer.cancel(); } catch (Exception ignored) { }

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                   RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, langCode);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        // Cho nguoi noi them thoi gian truoc khi cat cau
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L);

        listening = true;
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            listening = false;
            stt("error", "Không bật được micro: " + e.getMessage());
        }
    }

    private void handleRecognitionError(int code) {
        // 1) Loi tam thoi -> huy instance, tao lai, thu lai mot lan
        boolean transient_ = (code == SpeechRecognizer.ERROR_SERVER_DISCONNECTED)   // 11
                          || (code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)       // 8
                          || (code == SpeechRecognizer.ERROR_CLIENT);               // 5
        if (transient_ && retryLeft > 0) {
            retryLeft--;
            final String lang = currentLang;
            destroyRecognizer();
            stt("status", "Đang kết nối lại bộ nhận dạng…");
            ui.postDelayed(() -> startRecognizer(lang, true), 450);
            return;
        }

        // 2) Thieu goi ngon ngu -> tai goi ve va dung hop thoai he thong
        if (code == 12 /* ERROR_LANGUAGE_NOT_SUPPORTED */
         || code == 13 /* ERROR_LANGUAGE_UNAVAILABLE  */) {
            requestLanguageModel(currentLang);
            stt("error", "Máy chưa có gói nhận dạng " + niceLang(currentLang)
                    + ". Đang thử tải về và chuyển sang bộ nhận dạng của hệ thống.");
            ui.postDelayed(() -> launchSpeechUi(currentLang), 600);
            return;
        }

        // 3) Het cach voi SpeechRecognizer -> hop thoai he thong
        if (transient_) {
            stt("status", "Chuyển sang bộ nhận dạng của hệ thống…");
            ui.postDelayed(() -> launchSpeechUi(currentLang), 300);
            return;
        }

        stt("error", errorText(code));
    }

    private static String niceLang(String code) {
        return "vi-VN".equals(code) ? "tiếng Việt" : "tiếng Anh";
    }

    /**
     * Du phong: mo hop thoai "Speak now" san co cua Google.
     * On dinh hon SpeechRecognizer tren nhieu may Xiaomi / Oppo / Samsung.
     */
    private void launchSpeechUi(String langCode) {
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                       RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, langCode);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_PROMPT,
                       "vi-VN".equals(langCode) ? "Hãy nói tiếng Việt" : "Speak in English");
            currentLang = langCode;
            startActivityForResult(i, REQ_SPEECH_UI);
        } catch (Exception e) {
            stt("error", "Máy chưa có ứng dụng nhận dạng giọng nói. "
                    + "Hãy cài hoặc bật lại ứng dụng Google trong CH Play.");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SPEECH_UI) return;
        listening = false;
        if (res == Activity.RESULT_OK && data != null) {
            ArrayList<String> hits =
                    data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (hits != null && !hits.isEmpty() && hits.get(0).trim().length() > 0) {
                stt("result", hits.get(0));
                stt("end", "");
                return;
            }
        }
        stt("end", "");
    }

    /** Android 13+: nho he thong tai goi nhan dang cho ngon ngu con thieu. */
    private void requestLanguageModel(String langCode) {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, langCode);
            SpeechRecognizer r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            r.triggerModelDownload(i, pool, new ModelDownloadListener() {
                @Override public void onProgress(int p) { }
                @Override public void onSuccess() {
                    toastNow("Đã tải xong gói nhận dạng " + niceLang(langCode));
                }
                @Override public void onScheduled() {
                    toastNow("Máy đang tải gói nhận dạng " + niceLang(langCode) + " về nền.");
                }
                @Override public void onError(int error) { }
            });
        } catch (Throwable t) {
            Log.w(TAG, "triggerModelDownload: " + t);
        }
    }

    /** Bao cho giao dien biet may co nhan dang duoc ngon ngu nay khong. */
    private void checkLanguageSupport(String langCode) {
        if (Build.VERSION.SDK_INT < 33) {
            toJs("window.__lang && window.__lang(" + q(langCode) + "," + q("unknown") + ","
                 + q("Máy chạy Android dưới 13 nên không kiểm tra được.") + ")");
            return;
        }
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, langCode);
            SpeechRecognizer r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            r.checkRecognitionSupport(i, pool, new RecognitionSupportCallback() {
                @Override
                public void onSupportResult(RecognitionSupport support) {
                    List<String> installed = support.getInstalledOnDeviceLanguages();
                    List<String> avail = support.getSupportedOnDeviceLanguages();
                    boolean ok = contains(installed, langCode);
                    boolean can = ok || contains(avail, langCode);
                    String state = ok ? "installed" : (can ? "downloadable" : "unsupported");
                    toJs("window.__lang && window.__lang(" + q(langCode) + "," + q(state) + ","
                         + q("Đã cài: " + installed + " · Tải được: " + avail) + ")");
                    if (!ok && can) requestLanguageModel(langCode);
                }
                @Override
                public void onError(int error) {
                    toJs("window.__lang && window.__lang(" + q(langCode) + "," + q("unknown")
                         + "," + q("Không kiểm tra được (mã " + error + ").") + ")");
                }
            });
        } catch (Throwable t) {
            toJs("window.__lang && window.__lang(" + q(langCode) + "," + q("unknown") + ","
                 + q(String.valueOf(t.getMessage())) + ")");
        }
    }

    private static boolean contains(List<String> list, String code) {
        if (list == null) return false;
        String want = code.toLowerCase(Locale.US);
        String shortWant = want.split("-")[0];
        for (String x : list) {
            if (x == null) continue;
            String v = x.toLowerCase(Locale.US).replace('_', '-');
            if (v.equals(want) || v.split("-")[0].equals(shortWant)) return true;
        }
        return false;
    }

    private static String errorText(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:  return "Mạng phản hồi quá chậm.";
            case SpeechRecognizer.ERROR_NETWORK:          return "Lỗi mạng khi nhận dạng giọng nói.";
            case SpeechRecognizer.ERROR_AUDIO:            return "Lỗi thu âm. Kiểm tra xem có ứng dụng khác đang chiếm micro không.";
            case SpeechRecognizer.ERROR_SERVER:           return "Máy chủ nhận dạng báo lỗi.";
            case SpeechRecognizer.ERROR_CLIENT:           return "";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:   return "Không nghe thấy giọng nói nào.";
            case SpeechRecognizer.ERROR_NO_MATCH:         return "Không nghe rõ, hãy nói chậm và rõ hơn.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:  return "Bộ nhận dạng đang bận, thử lại sau giây lát.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                                                          return "Ứng dụng chưa được cấp quyền micro.";
            case 10: return "Gọi dịch vụ nhận dạng quá nhiều lần, nghỉ một lát rồi thử lại.";
            case 11: return "Mất kết nối tới dịch vụ nhận dạng.";
            case 12: return "Máy chưa hỗ trợ ngôn ngữ này.";
            case 13: return "Gói ngôn ngữ chưa được tải về máy.";
            case 14: return "Không kiểm tra được khả năng nhận dạng.";
            default: return "Lỗi micro (mã " + code + ").";
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req != REQ_MIC) return;
        String lang = pendingPermissionLang;
        pendingPermissionLang = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && lang != null) {
            startRecognizer(lang, false);
        } else {
            stt("error", "Bạn chưa cho phép dùng micro. "
                    + "Vào Cài đặt → Ứng dụng → TranslatorEV → Quyền → Micro để bật.");
        }
    }

    // ====================================================================
    //  DOC TO
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
                toastNow("Điện thoại chưa có giọng đọc tiếng Việt. Cài trong Cài đặt → "
                        + "Ngôn ngữ → Đầu ra chuyển văn bản thành lời nói.");
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
    //  MANG  (goi bang Java de khong vuong CORS cua WebView)
    // ====================================================================
    private void httpAsync(String id, String url, String headersJson, String body) {
        pool.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(12000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android) TranslatorEV/1.1");
                c.setRequestProperty("Accept", "application/json,text/plain,*/*");

                if (headersJson != null && headersJson.length() > 2) {
                    JSONObject h = new JSONObject(headersJson);
                    for (java.util.Iterator<String> it = h.keys(); it.hasNext(); ) {
                        String k = it.next();
                        c.setRequestProperty(k, h.optString(k));
                    }
                }

                if (body != null) {
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    byte[] out = body.getBytes(StandardCharsets.UTF_8);
                    c.setFixedLengthStreamingMode(out.length);
                    OutputStream os = c.getOutputStream();
                    os.write(out);
                    os.flush();
                    os.close();
                } else {
                    c.setRequestMethod("GET");
                }

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
                    String msg = "HTTP " + code;
                    String bodyTxt = sb.toString();
                    if (bodyTxt.length() > 0) msg += " · " + bodyTxt.substring(0, Math.min(200, bodyTxt.length()));
                    toJs("window.__net(" + q(id) + ",false," + q(msg) + ")");
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
    //  LUU TEP
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
    //  CAU NOI JAVASCRIPT -> JAVA  (chay o luong nen)
    // ====================================================================
    private class Bridge {

        @JavascriptInterface
        public void startListening(String langCode) {
            final String lc = (langCode == null || langCode.isEmpty()) ? "en-US" : langCode;
            ui.post(() -> startRecognizer(lc, false));
        }

        @JavascriptInterface
        public void stopListening() {
            ui.post(MainActivity.this::cancelListening);
        }

        @JavascriptInterface
        public void checkLanguage(String langCode) {
            final String lc = (langCode == null || langCode.isEmpty()) ? "vi-VN" : langCode;
            ui.post(() -> checkLanguageSupport(lc));
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
            httpAsync(id, url, null, null);
        }

        @JavascriptInterface
        public void httpPost(String id, String url, String headersJson, String body) {
            httpAsync(id, url, headersJson, body == null ? "" : body);
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
