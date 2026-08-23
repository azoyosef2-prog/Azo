package ai.admaker.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private TextToSpeech tts;
    private static final int FILE_CHOOSER_REQUEST = 9001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("ar"));
            }
        });

        webView.addJavascriptInterface(new NativeBridge(), "ADMakerNative");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    public class NativeBridge {
        @JavascriptInterface
        public void speak(String text, String languageTag, float pitch, float rate) {
            if (text == null || text.trim().isEmpty()) return;
            runOnUiThread(() -> {
                if (tts == null) return;
                try {
                    Locale locale = Locale.forLanguageTag(languageTag == null ? "ar" : languageTag);
                    tts.setLanguage(locale);
                    tts.setPitch(Math.max(0.5f, Math.min(2.0f, pitch)));
                    tts.setSpeechRate(Math.max(0.5f, Math.min(2.0f, rate)));
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "admaker-voice");
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void stopSpeech() {
            runOnUiThread(() -> {
                if (tts != null) tts.stop();
            });
        }

        @JavascriptInterface
        public void httpRequest(String method, String urlString, String jsonBody, String callbackName) {
            if (callbackName == null || !callbackName.matches("[A-Za-z0-9_]+")) return;
            if (urlString == null || !urlString.startsWith("https://")) {
                deliverCallback(callbackName, wrap(false, 0, "Backend URL must use HTTPS"));
                return;
            }

            new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(urlString);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod(method == null ? "GET" : method.toUpperCase(Locale.ROOT));
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(120000);
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setRequestProperty("X-ADMaker-Client", "android-apk");

                    if (jsonBody != null && !jsonBody.isEmpty() && !"GET".equalsIgnoreCase(method)) {
                        conn.setDoOutput(true);
                        try (OutputStream os = conn.getOutputStream()) {
                            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                        }
                    }

                    int code = conn.getResponseCode();
                    InputStream stream = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
                    String body = readAll(stream);
                    deliverCallback(callbackName, wrap(code >= 200 && code < 300, code, body));
                } catch (Exception e) {
                    deliverCallback(callbackName, wrap(false, 0, e.getMessage() == null ? "Network error" : e.getMessage()));
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }).start();
        }
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private String wrap(boolean ok, int status, String body) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("ok", ok);
            obj.put("status", status);
            obj.put("body", body == null ? "" : body);
            return obj.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"status\":0,\"body\":\"Unexpected bridge error\"}";
        }
    }

    private void deliverCallback(String callbackName, String payload) {
        runOnUiThread(() -> {
            if (webView == null) return;
            String js = "if(window['" + callbackName + "']){window['" + callbackName + "'](" + JSONObject.quote(payload) + ");}";
            webView.evaluateJavascript(js, null);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST) {
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK) {
                results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            }
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
