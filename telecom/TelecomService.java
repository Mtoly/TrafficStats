package com.unexpected.ts;

import android.content.ContentValues;
import android.database.Cursor;
import android.text.SpannableStringBuilder;
import android.util.Log;
import androidx.core.content.ContentResolverCompat;
import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.Observer;
import io.reactivex.Scheduler;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import java.io.File;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import xrg.function.SingleEntity;
import xrg.io.FileIOUtils;
import xrg.lang.JSONCompat;
import xrg.lang.StringUtils;
import xrg.net.OkHttpUtils;
import xrg.util.CodecUtils;
import xrg.util.DateTimeUtils;
import xrg.util.DisposableUtils;
import xrg.util.UnitUtils;


public class TelecomService extends TrafficService {
    private String[] mUser;
    private String mCookie;
    private DisposableUtils disposableUtils;

    @Override
    public void connecte() {
        super.connecte();
        try(Cursor cursor = ContentResolverCompat.query(getContentResolver(), MyContentProvider.SETTING_URI, new String[]{MyContentProvider.USER,MyContentProvider.COOKIE}, null, null, null, null)){
            cursor.moveToFirst();
            mUser = CodecUtils.rsaDecode(Utils.DECODE_KEY, cursor.getString(cursor.getColumnIndexOrThrow(MyContentProvider.USER))).split(",");
            mCookie = CodecUtils.rsaDecode(Utils.DECODE_KEY, cursor.getString(cursor.getColumnIndexOrThrow(MyContentProvider.COOKIE)));
        }
        mOkHttpClientBuilder.followRedirects(false);
        disposableUtils = new DisposableUtils();
    }

    @Override
    public void stopRunner(boolean stopService) {
        if(disposableUtils != null) {
            disposableUtils.clear();
            disposableUtils = null;
        }
        super.stopRunner(stopService);
    }

    @Override
    public void queryTraffic() {
        super.queryTraffic();
        if(!StringUtils.isEmpty(mCookie)) {
            checkLogin(mOkHttpClientBuilder, mScheduler, mCookie).subscribe(new Consumer<SingleEntity<Boolean>>(){
                    @Override
                    public void accept(SingleEntity<Boolean> p1) throws Exception {
                        if(mSimpleDateFormat != null) {
                            if(p1.body()) {
                                update();
                            } else {
                                mCookie = null;
                                ContentValues values = new ContentValues(2);
                                values.put(MyContentProvider.COOKIE, "");
                                values.put(MyContentProvider.STATE, 0);
                                getContentResolver().update(MyContentProvider.SETTING_URI, values, null, null);
                                mError = true;
                                setText("Cookie失效");
                            }
                        }
                    }
                });
        } else {
            mError = true;
            setText("Cookie失效");
        }
    }

    @Override
    protected SpannableStringBuilder getFormatStyle(String style) {
        final String mainUser = mUser[0];
        style = style.replace("[ST]", mFlowInfo.optString("startTime"))
            .replace("[time]", mFlowInfo.optString("time"))
            .replace("[number]", mFlowInfo.optString("number"));
        final JSONObject mainJo = mFlowInfo.optJSONObject(mainUser);
        style = style.replace("[name]", mainJo.optString("name"))
            .replace("[card]", mainJo.optString("card"))
            .replace("[snumber]", mainJo.optString("snumber"))
            .replace("[fnumber]", mainJo.optString("fnumber"))
            .replace("[TY]", UnitUtils.getUnit(mainJo.optString("total", "0")))
            .replace("[TS]", UnitUtils.getUnit(mainJo.optString("remain", "0")))
            .replace("[TD]", UnitUtils.getUnit(mainJo.optString("currentUse", "0")))
            .replace("[sum]", UnitUtils.getUnit(mainJo.optString("sum", "0")))
            .replace("[free]", UnitUtils.getUnit(mainJo.optString("freeUse", "0")))
            .replace("[FU]", UnitUtils.getUnit(mainJo.optString("currentFreeUse", "0")));
        return super.getFormatStyle(style);
    }

    public void update() {
        for(final String user : mUser) {
            disposableUtils.add(new OkHttpUtils.FormBodyBuilder(mOkHttpClientBuilder)
                .url("https://service.sh.189.cn/service/service/authority/query/packages")
                .cookie(mCookie)
                .addData("devNo", user)
                .buildPost()
                .asyncExecute(new OkHttpUtils.Callback<Boolean>(){
                        @Override
                        protected Boolean onResponse(Response response) throws Exception {
                            if(mSimpleDateFormat != null) {
                                final String body = response.body().string();
                                final int requestCode = response.code();
                                FileIOUtils.writeFileFromString(new File(getExternalCacheDir(), "request.json"),
                                    String.format(Locale.ENGLISH, "时间: %s\n状态码: %d\n%s", mSimpleDateFormat.format(System.currentTimeMillis()), requestCode, body));
                                if(requestCode == 200) {
                                    final JSONObject jo = JSONCompat.isJSONObject(body);
                                    if(jo != null) {
                                        FileIOUtils.writeFileFromString(new File(getExternalCacheDir(), "info.json"), jo.toString(2));
                                        final JSONObject joo = JSONCompat.optJSONObject(mFlowInfo, user);
                                        final DateTimeUtils dtu = DateTimeUtils.getInstance(Locale.CHINA);
                                        if(!joo.has("startTime")) {
                                            joo.put("startTime", dtu.getTime());
                                        }
                                        joo.put("time", dtu.getTime());
                                        joo.put("total", 0);
                                        joo.put("remain", 0);
                                        joo.put("currentUse", 0);
                                        final JSONArray details = jo.optJSONArray("details");
                                        if(details != null && details.length() > 0) {
                                            setTrafficInfo(details, joo, true, dtu);
                                        }
                                        joo.put("snumber", joo.optLong("snumber") + 1l);
                                        joo.put("fnumber", mFlowInfo.optLong("number") - mFlowInfo.optLong("snumber"));
                                        return true;
                                    }
                                }
                            }
                            return false;
                        }

                        @Override
                        protected void onPost(Boolean result) {
                            if(result) {
                                updateView();
                                updateNotification();
                                alert();
                            } else {
                                mError = true;
                                setText("查询失败");
                            }
                        }

                        @Override
                        protected void onFailure(Throwable tr) {
                            mError = true;
                            if(tr instanceof SocketTimeoutException) {
                                setText("请求超时");
                            } else if(tr instanceof UnknownHostException) {
                                setText("网络异常");
                            } else {
                                setText("未知错误");
                                FileIOUtils.writeFileFromString(new File(getExternalCacheDir(), "error.text"),
                                    String.format(Locale.ENGLISH, "时间: %s\n%s", mSimpleDateFormat.format(System.currentTimeMillis()), Log.getStackTraceString(tr)));
                            }
                        }
                    }));
            disposableUtils.add(new OkHttpUtils.FormBodyBuilder(mOkHttpClientBuilder)
            .url("https://service.sh.189.cn/service/service/authority/queryInfo/getMsgByDeviceId.do")
                .cookie(mCookie)
                .addData("DeviceId", user)
                .buildPost()
                .asyncExecute(new OkHttpUtils.Callback<Void>(){
                        @Override
                        protected Void onResponse(Response response) throws Exception {
                            if(response.code() == 200) {
                                final JSONObject jo = JSONCompat.isJSONObject(response.body().string());
                                if(jo != null) {
                                    final JSONObject joo = JSONCompat.optJSONObject(mFlowInfo, user);
                                    final String name = jo.optString("parentPromotionProductName");
                                    joo.put("name", name);
                                    joo.put("card", name.substring(0, name.indexOf("套")));
                                }
                            }
                            return null;
                        }
                    }));
        }
        if(mAccountBalance && mAccountBalanceLimit < 2) {
            for(final String user : mUser) {
                disposableUtils.add(new OkHttpUtils.FormBodyBuilder(mOkHttpClientBuilder)
                    .url("https://service.sh.189.cn/service/balanceQuery/balance")
                    .cookie(mCookie)
                    .addData("serialNum", user)
                    .buildPost()
                    .asyncExecute(new OkHttpUtils.Callback<Void>(){
                            @Override
                            protected Void onResponse(Response response) throws Exception {
                                if(response.code() == 200) {
                                    final JSONObject jo = JSONCompat.isJSONObject(response.body().string());
                                    if(jo != null && jo.optInt("code") == 0) {
                                        if(mAccountBalanceLimit == 1) {
                                            mAccountBalanceLimit = 2;
                                        }
                                        final JSONObject balance = JSONCompat.optJSONObject(mFlowInfo, "balance");
                                        final JSONObject joo = JSONCompat.optJSONObject(balance, user);
                                        joo.put("current", jo.optString("balance"));
                                        joo.put("totla", jo.optString("totalBalance"));
                                    }
                                }
                                return null;
                            }
                        }));
            }
        }
        if(mPoints && mPointsLimit < 2) {
            for(final String user : mUser) {
                disposableUtils.add(new OkHttpUtils.FormBodyBuilder(mOkHttpClientBuilder)
                    .url("https://service.sh.189.cn/service/service/authority/queryInfo/getMyPointsByCrmid.do")
                    .cookie(mCookie)
                    .addData("devNo", user)
                    .buildPost()
                    .asyncExecute(new OkHttpUtils.Callback<Void>(){
                            @Override
                            protected Void onResponse(Response response) throws Exception {
                                if(response.code() == 200) {
                                    final JSONObject jo = JSONCompat.isJSONObject(response.body().string());
                                    if(jo != null && jo.optInt("errorCode") == 0) {
                                        if(mPointsLimit == 1) {
                                            mPointsLimit = 2;
                                        }
                                        final JSONObject points = JSONCompat.optJSONObject(mFlowInfo, "points");
                                        final JSONObject joo = JSONCompat.optJSONObject(points, user);
                                        joo.put("total", jo.optString("useablePoints"));
                                        joo.put("use", jo.optString("usedPoints"));
                                        joo.put("currMonthPoints", jo.optString("currMonthPoints"));
                                        joo.put("score", jo.optString("score"));
                                        joo.put("sum", jo.optString("sumPoints"));
                                    }
                                }
                                return null;
                            }
                        }));
            }
        }
        compositeDisposable.add(new OkHttpUtils.FormBodyBuilder(mOkHttpClientBuilder)
            .url("https://service.sh.189.cn/service/service/authority/query/getUserStar.do")
            .cookie(mCookie)
            .buildPost()
            .asyncExecute(new OkHttpUtils.Callback<Void>(){
                    @Override
                    protected Void onResponse(Response response) throws Exception {
                        if(response.code() == 200) {
                            JSONObject jo = JSONCompat.isJSONObject(response.body().string());
                            if(jo != null) {
                                mFlowInfo.put("star", jo.optString("result"));
                            }
                        }
                        return null;
                    }
                }));
        disposableUtils.isDisposedAll().subscribeOn(mScheduler)
            .observeOn(AndroidSchedulers.mainThread()).subscribe(new Consumer<Boolean>(){
                @Override
                public void accept(Boolean p1) throws Exception {
                    if(mSimpleDateFormat != null) {
                        mFlowInfo.put("updateTime", mSimpleDateFormat.format(System.currentTimeMillis()));
                        FileIOUtils.writeFileFromString(new File(getExternalCacheDir(), "flow.json"), mFlowInfo.toString(4));
                    }
                }
            });
    }

    private void setTrafficInfo(final JSONArray details, final JSONObject toJson, final boolean isTy, final DateTimeUtils dateTimeUtils) throws JSONException {
        BigDecimal total = new BigDecimal(0);
        BigDecimal remain = new BigDecimal(0);
        BigDecimal use = new BigDecimal(0);
        BigDecimal dateUse = new BigDecimal(0);
        BigDecimal freeUse = new BigDecimal(0);
        for(int i=0,len=details.length();i < len;i++) {
            final JSONObject detail = details.optJSONObject(i);
            if(detail.optInt("judgeNum") == 12 || detail.optString("packageName").contains("定向")) {
                freeUse = freeUse.add(UnitUtils.getBigDecimal(detail.optString("used"), UnitUtils.UNIT_BYTES_1, new BigDecimal(1024)));
            } else {
                total = total.add(UnitUtils.getBigDecimal(detail.optString("total"), UnitUtils.UNIT_BYTES_1, new BigDecimal(1024)));
                remain = remain.add(UnitUtils.getBigDecimal(detail.optString("surplus"), UnitUtils.UNIT_BYTES_1, new BigDecimal(1024)));
                final BigDecimal u = UnitUtils.getBigDecimal(detail.optString("used"), UnitUtils.UNIT_BYTES_1, new BigDecimal(1024));
                //获取流量包到期时间
                final String[] endDate = detail.optString("endDate").split("\\D");
                if(endDate.length == 3) {//流量包有指定到期时间
                    if(Integer.parseInt(endDate[0]) == dateTimeUtils.getYear() 
                        && Integer.parseInt(endDate[1]) == dateTimeUtils.getMonthCompat()
                        && Integer.parseInt(endDate[2]) == dateTimeUtils.getDate()) {//判断是不是今天到期
                        if(total.compareTo(u) > 0) {
                            dateUse = dateUse.add(u);
                            continue;
                        }
                    }
                }
                use = use.add(u);//已用
            }
        }
        if(!toJson.has("oldDateUse")) {
            toJson.put("oldDateUse", dateUse);
        }
        if(!toJson.has("oldUse")) {
            toJson.put("oldUse", use);
        }
        if(!toJson.has("oldFreeUse")) {
            toJson.put("oldFreeUse", freeUse);
        }
        toJson.put("use", use);
        toJson.put("total", total);
        toJson.put("remain", remain);
        BigDecimal currentUse = use.subtract(new BigDecimal(toJson.optString("oldUse")));
        if(dateUse.compareTo(BigDecimal.ZERO) > 0) {
            currentUse = currentUse.add(dateUse).subtract(new BigDecimal(toJson.optString("oldDateUse")));
        }
        toJson.put("sum", use.add(freeUse));
        toJson.put("currentUse", currentUse);
        toJson.put("freeUse", freeUse);
        toJson.put("currentFreeUse", freeUse.subtract(new BigDecimal(toJson.optString("oldFreeUse"))));
        if(isTy) {
            toJson.put("total", total.add(new BigDecimal(toJson.optString("total", "0"))));
            toJson.put("remain", remain.add(new BigDecimal(toJson.optString("remain", "0"))));
            toJson.put("currentUse", currentUse.add(new BigDecimal(toJson.optString("currentUse", "0"))));
        }
    }

    public static Observable<SingleEntity<Boolean>> checkLogin(OkHttpClient.Builder clientBuilder, Scheduler scheduler, String cookie) {
        return new OkHttpUtils.Builder(clientBuilder)
            .url("https://service.sh.189.cn/service/service/authority/query/checkCrmidLogin")
            .cookie(cookie)
            .build()
            .createExecute(new OkHttpUtils.Callback<Boolean>(){
                @Override
                protected Boolean onResponse(Response response) throws Exception {
                    final JSONObject jo = JSONCompat.isJSONObject(response.body().string());
                    if(jo != null && jo.optInt("CrmID") == 1) {
                        return true;
                    }
                    return false;
                }
            }).subscribeOn(scheduler);
    }
}
