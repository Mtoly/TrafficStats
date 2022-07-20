package com.unexpected.ts;

import android.content.ContentValues;
import android.net.http.SslError;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;
import androidx.webkit.WebViewClientCompat;
import com.unexpected.ts.MyContentProvider;
import com.unexpected.ts.TelecomFragment;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Consumer;
import java.util.ArrayList;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import xrg.base.BaseWebViewFragment;
import xrg.function.SingleEntity;
import xrg.lang.StringUtils;
import xrg.net.OkHttpUtils;
import xrg.util.CodecUtils;
import xrg.util.LogUtils;
import xrg.util.RegexUtils;
import okhttp3.FormBody;
import okhttp3.RequestBody;
import okhttp3.MediaType;


public class TelecomFragment extends BaseWebViewFragment<MainActivity> {
    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(null);
        cookieManager.removeSessionCookies(null);
        mWebView.clearCache(true);
        mWebView.clearHistory();
        initWebView();
        mWebView.loadUrl("https://service.sh.189.cn/service/account");
    }

    @Override
    public void onRefresh() {
        super.onRefresh();
        mWebView.getSettings().setBlockNetworkImage(false);
        mWebView.getSettings().setBlockNetworkLoads(false);
        mWebView.setNetworkAvailable(true);
        mWebView.reload();
    }

    private void initWebView() {
        WebSettings webSettings = mWebView.getSettings();
        webSettings.setDomStorageEnabled(true);
        webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.61 Safari/537.36");
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        mWebView.setWebChromeClient(new BaseWebChromeClient());
        mWebView.setWebViewClient(new WebViewClientCompat() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest request) {
                    final String url = request.getUrl().toString();
                    if(!login(webView, url)) {
                        webView.loadUrl(url);
                    }
                    return true;
                }

                @Override
                public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    super.onReceivedError(view, errorCode, description, failingUrl);
                    stopRefreshing();
                }

                @Override
                public void onReceivedSslError(WebView webView, SslErrorHandler handler, SslError error) {
                    handler.proceed();
                    stopRefreshing();
                }
            });
    }

    private boolean login(WebView webView, String url) {
        final CookieManager cookieManager = CookieManager.getInstance();
        final String cookie = cookieManager.getCookie(url);
        LogUtils.f(url);
        LogUtils.f(cookie);
        if(url.startsWith("https://service.sh.189.cn/service/mytelecom/bindIndex")
            || (url.startsWith("http://service.sh.189.cn/service") && cookie.contains("uname1") && cookie.contains("uname2"))) {
            login(cookie).observeOn(AndroidSchedulers.mainThread()).subscribe(new Consumer<SingleEntity<ContentValues>>(){
                    @Override
                    public void accept(SingleEntity<ContentValues> p1) throws Exception {
                        final ContentValues values = p1.body();
                        if(values != null && values.containsKey(MyContentProvider.USER_ID)) {
                            mActivity.remove(TelecomFragment.this, false);
                            mActivity.updateEfab(StringUtils.replaceWithMask(values.getAsString(MyContentProvider.USER_ID), 2, 2));

                            values.remove(MyContentProvider.USER_ID);
                            getContentResolver().update(MyContentProvider.SETTING_URI, values, null, null);
                        } else {
                            Toast.makeText(mActivity.getApplicationContext(), R.string.login_failed, Toast.LENGTH_LONG).show();
                        }
                    }
                }, new Consumer<Throwable>(){
                    @Override
                    public void accept(Throwable p1) throws Exception {
                        xrg.util.LogUtils.f(p1);
                        Toast.makeText(mActivity.getApplicationContext(), p1.toString(), Toast.LENGTH_LONG).show();
                    }
                });
            webView.getSettings().setBlockNetworkImage(true);
            webView.getSettings().setBlockNetworkLoads(true);
            webView.setNetworkAvailable(false);
            webView.stopLoading();
            return true;
        }
        return false;
    }

    public static Observable<SingleEntity<ContentValues>> login(final String cookie) {
        return new OkHttpUtils.Builder()
            .url("https://service.sh.189.cn/service/mytelecom/bindIndex")
            .noneHostnameVerifier()
            .noneSSLSocketFactory()
            .followRedirects(false)
            .cookie(cookie)
            .build()
            .createExecute(new OkHttpUtils.Callback<ContentValues>(){
                @Override
                protected ContentValues onResponse(Response response) throws Exception {
                    if(!response.isRedirect()) {
                        final String body = response.body().string();
                        final String userId;
                        if((userId = RegexUtils.find("=\\W{0,}'(\\d{3,12})';", body, 1)) != null) {
                            final ArrayList<String> list = new ArrayList<>();
                            String regex;
                            if((regex = RegexUtils.find("'\\[(\\[(.*?)\\])\\]'", body, 1)) != null) {
                                final JSONArray ja = new JSONArray(regex);
                                for(int i=0,len=ja.length();i < len;i++) {
                                    final JSONObject jo = ja.optJSONObject(i);
                                    if(jo.optBoolean("cardType")) {
                                        list.add(jo.optString("SerialNum"));
                                    }
                                }
                            } else {
                                list.add(userId);
                            }
                            if(list.size() > 0) {
                                final ContentValues values = new ContentValues(5);
                                if(userId != null) {
                                    list.remove(userId);
                                    list.add(0, userId);
                                    values.put(MyContentProvider.USER_ID, StringUtils.replaceWithMask(userId, 2, 2));
                                }
                                values.put(MyContentProvider.USER, CodecUtils.rsaEncode(Utils.ENCODE_KEY, TextUtils.join(",", list)));
                                values.put(MyContentProvider.COOKIE, CodecUtils.rsaEncode(Utils.ENCODE_KEY, cookie));
                                values.put(MyContentProvider.STATE, true);
                                LogUtils.f("运行");
                                values.put(MyContentProvider.USER_TYPE, 2);
                                return values;
                            }
                        }
                    }
                    return null;
                }
            });
    }
}
