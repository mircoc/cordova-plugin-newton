package com.buongiorno.newton.cordova;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.buongiorno.newton.MetaInfo;
import com.buongiorno.newton.Newton;
import com.buongiorno.newton.NewtonError;
import com.buongiorno.newton.SimpleObject;
import com.buongiorno.newton.events.RankingEvent;
import com.buongiorno.newton.exceptions.NewtonException;
import com.buongiorno.newton.exceptions.NewtonNotInitializedException;
import com.buongiorno.newton.exceptions.OAuthException;
import com.buongiorno.newton.exceptions.PushRegistrationException;
import com.buongiorno.newton.exceptions.SimpleObjectException;
import com.buongiorno.newton.exceptions.UserAlreadyLoggedException;
import com.buongiorno.newton.exceptions.UserMetaInfoException;
import com.buongiorno.newton.interfaces.IBasicResponse;
import com.buongiorno.newton.interfaces.IMetaInfoCallBack;
import com.buongiorno.newton.interfaces.IPushCallback;
import com.buongiorno.newton.oauth.flows.CustomLoginFlow;
import com.buongiorno.newton.oauth.flows.ExternalLoginFlow;
import com.buongiorno.newton.oauth.flows.LoginBuilder;
import com.buongiorno.newton.push.PushObject;
import com.google.android.gms.common.GooglePlayServicesUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import me.leolin.shortcutbadger.ShortcutBadger;


/**
 * Created by mirco.cipriani on 20/10/16.
 */
public class NewtonPlugin extends CordovaPlugin {
    private Newton newtonEngine = null;
    public static final String LOG_TAG = "NewtonPlugin";

    private static final String META_SECRET = "newton_secret";

    private static CallbackContext pushContext;
    private static CallbackContext loginContext;
    private static CordovaWebView gWebView;
    private static boolean gForeground = false;
    private static List<PushObject> gCachedPushes = Collections.synchronizedList(new ArrayList<PushObject>());

    /**
     * Gets the application context from cordova's main activity.
     * @return the application context
     */
    private Context getApplicationContext() {
        return this.getActivity().getApplicationContext();
    }

    private enum LoginOptions {
        customData, externalID, type
    }

    private enum LoginFlowType {
        custom, external
    }

    /**
     * Gets the cordova main activity.
     * @return the activity
     */
    private Activity getActivity() {
        return this.cordova.getActivity();
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        gForeground = true;
    }

    @Override
    public boolean execute(final String action, final JSONArray data, final CallbackContext callbackContext) {
        Log.v(LOG_TAG, "execute: action=" + action);
        Log.v(LOG_TAG, "execute: data=" + data.toString());
        gWebView = this.webView;
        
        if ("init".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    pushContext = callbackContext;

                    JSONObject jo;
                    JSONObject customDataJo;

                    try {
                        jo = data.getJSONObject(0);
                        customDataJo = jo.getJSONObject("customData");

                    } catch (JSONException e) {
                        Log.e(LOG_TAG, "[action: init] parameters error:" + e.getMessage(), e);
                        callbackContext.error("Invalid parameters: "+e.getMessage());
                        return;
                    }

                    SimpleObject customData = null;
                    try {
                        customData = SimpleObject.fromJSONObject(customDataJo);
                    } catch (SimpleObjectException e) {
                        Log.e(LOG_TAG, "[action: init] SimpleObject parameters error:" + e.getMessage(), e);
                        callbackContext.error("Invalid parameters, cannot convert to SimpleObject: "+e.getMessage());
                        return;
                    }
                    // force hybrid true
                    customData.setBool("hybrid", true);

                    String newtonSecret = "";

                    // load newton conf from manifest meta data
                    try {
                        ApplicationInfo ai = getApplicationContext().getPackageManager().
                                getApplicationInfo(
                                        getApplicationContext().getPackageName(),
                                        PackageManager.GET_META_DATA);
                        Bundle bundle = ai.metaData;
                        newtonSecret = bundle.getString(META_SECRET);

                    } catch (PackageManager.NameNotFoundException e) {
                        Log.e(LOG_TAG, "Failed to load meta-data, NameNotFound: " + e.getMessage(), e);
                        callbackContext.error(e.getMessage());
                        return;
                    } catch (NullPointerException e) {
                        Log.e(LOG_TAG, "Failed to load meta-data, NullPointer: " + e.getMessage(), e);
                        callbackContext.error(e.getMessage());
                        return;
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Failed to load meta-data, Exception: " + e.getMessage(), e);
                        callbackContext.error(e.getMessage());
                        return;
                    }

                    try {
                        newtonEngine = Newton.getSharedInstanceWithConfig(getApplicationContext(), newtonSecret, customData);

                        newtonEngine.getPushManager().setPushNotificationCallback(new IPushCallback() {
                            @Override
                            public void onSuccess(PushObject push) {

                                Log.i(LOG_TAG, "Got push notification: " + push.toString());

                                boolean isPushPluginActive = NewtonPlugin.isActive();

                                NewtonPlugin.sendPushToJs(push);

                                // FIXME, verify if it works when not in foreground
                                if (!isPushPluginActive) {
                                    Log.d(LOG_TAG, "forceMainActivityReload");
                                    forceMainActivityReload();
                                }
                            }

                            /**
                             * Forces the main activity to re-launch if it's unloaded.
                             */
                            private void forceMainActivityReload() {
                                Context context = getApplicationContext();
                                PackageManager pm = context.getPackageManager();
                                Intent launchIntent = pm.getLaunchIntentForPackage(context.getPackageName());
                                context.startActivity(launchIntent);
                            }
                        });

                        newtonEngine.getPushManager().registerDevice();


                        getActivity().getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                            @Override
                            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                            }

                            @Override
                            public void onActivityStarted(Activity activity) {
                            }

                            @Override
                            public void onActivityResumed(Activity activity) {
                                try {
                                    Newton.getSharedInstance().setToForeground();
                                } catch (NewtonNotInitializedException e) {
                                    Log.e(LOG_TAG, "NewtonNotInitializedException: " + e.getMessage(), e);
                                }
                            }

                            @Override
                            public void onActivityPaused(Activity activity) {
                            }

                            @Override
                            public void onActivityStopped(Activity activity) {
                            }

                            @Override
                            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                            }

                            @Override
                            public void onActivityDestroyed(Activity activity) {
                            }
                        });

                        PluginResult pluginResult = new PluginResult(PluginResult.Status.NO_RESULT);
                        pluginResult.setKeepCallback(true);
                        pushContext.sendPluginResult(pluginResult);

                        if (!gCachedPushes.isEmpty()) {
                            Log.v(LOG_TAG, "sending cached extras");
                            synchronized(gCachedPushes) {
                                Iterator<PushObject> gCachedPushesIterator = gCachedPushes.iterator();
                                while (gCachedPushesIterator.hasNext()) {
                                    sendPushToJs(gCachedPushesIterator.next());
                                }
                            }
                            gCachedPushes.clear();
                        }

                    } catch (NewtonException e) {
                        Log.e(LOG_TAG, "NewtonException- Newton initialization error:" + e.getMessage(), e);
                        callbackContext.error("NewtonException - Newton initialization error: "+e.getMessage());

                    } catch (PushRegistrationException e) {
                        // FIXME: this should be optional ?
                        GooglePlayServicesUtil.getErrorDialog(e.getGoooglePlayCode(), null, 1).show();

                        Log.e(LOG_TAG, "PushRegistrationException - Newton initialization error:" + e.getMessage(), e);
                        callbackContext.error("PushRegistrationException - Newton initialization error: "+e.getMessage());
                    }
                }
            });
        }
        else if ("unregister".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {

                    // FIXME: what to do here ? remove reference to pushContext ?
                    pushContext = null;

                    callbackContext.success();
                }
            });
        }
        else if ("finish".equals(action)) {
            callbackContext.success();
        }
        else if ("hasPermission".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    JSONObject jo = new JSONObject();
                    try {
                        jo.put("isEnabled", PermissionUtils.hasPermission(getApplicationContext(), "OP_POST_NOTIFICATION"));
                        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, jo);
                        pluginResult.setKeepCallback(true);
                        callbackContext.sendPluginResult(pluginResult);
                    } catch (UnknownError e) {
                        callbackContext.error(e.getMessage());
                    } catch (JSONException e) {
                        callbackContext.error(e.getMessage());
                    }
                }
            });
        }
        else if ("setApplicationIconBadgeNumber".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    Log.v(LOG_TAG, "setApplicationIconBadgeNumber: data=" + data.toString());
                    try {
                        setApplicationIconBadgeNumber(getApplicationContext(), data.getJSONObject(0).getInt("badge"));
                    } catch (JSONException e) {
                        callbackContext.error(e.getMessage());
                    }
                    callbackContext.success();
                }
            });
        }
        else if ("clearAllNotifications".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    Log.v(LOG_TAG, "clearAllNotifications");
                    clearAllNotifications();
                    callbackContext.success();
                }
            });
        }
        else if ("event".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    String eventName;
                    JSONObject eventParams;
                    SimpleObject eventParamsSO;

                    try {
                        eventName = data.getString(0);
                        eventParams = data.getJSONObject(1);

                    } catch (JSONException e) {
                        Log.e(LOG_TAG, "[action: event] JSON parameters error:" + e.getMessage(), e);
                        callbackContext.error("Invalid parameters: "+e.getMessage());
                        return;
                    }

                    try {
                        eventParamsSO = SimpleObject.fromJSONObject(eventParams);
                    } catch (SimpleObjectException e) {
                        Log.e(LOG_TAG, "[action: event] SimpleObject parameters error:" + e.getMessage(), e);
                        callbackContext.error("Invalid parameters, cannot convert to SimpleObject: "+e.getMessage());
                        return;
                    }

                    try {
                        Newton.getSharedInstance().sendEvent(eventName, eventParamsSO);
                    } catch (NewtonException e) {
                        Log.e(LOG_TAG, "[action: event] Newton sendEvent error:" + e.getMessage(), e);
                        callbackContext.error("NewtonException: "+e.getMessage());
                        return;
                    }
                    callbackContext.success();
                }
            });
        }
        else if ("login".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    loginContext = callbackContext;
                    JSONObject eventParams;

                    try {
                        eventParams = data.getJSONObject(0);


                        /**
                         * 1#
                         *
                         * initialize the loginBuilder and set completion callbacks
                         * to call javascript when done
                         *
                         */
                        LoginBuilder loginBuilder = newtonEngine.getLoginBuilder();
                        LoginFlowType loginFlowType = null;

                        loginBuilder.setOnFlowCompleteCallback(new IBasicResponse() {
                            @Override
                            public void onSuccess() {
                                PluginResult pluginResult = new PluginResult(PluginResult.Status.OK);
                                pluginResult.setKeepCallback(true);
                                loginContext.sendPluginResult(pluginResult);
                            }

                            @Override
                            public void onFailure(NewtonError newtonError) {
                                Log.e(LOG_TAG, "[action: login] Flow failure:"+newtonError.getMessage());
                                JSONObject joNewtonError = new JSONObject();
                                try {
                                    joNewtonError.put("error", true);
                                    joNewtonError.put("errorDescription", newtonError.getMessage());

                                } catch (JSONException e) {
                                    Log.e(LOG_TAG, "[action: login] Flow failure error on reporting:"+e.getMessage());

                                    PluginResult pluginResult = new PluginResult(PluginResult.Status.ERROR);
                                    pluginResult.setKeepCallback(true);
                                    loginContext.sendPluginResult(pluginResult);
                                    return;
                                }

                                PluginResult pluginResult = new PluginResult(PluginResult.Status.ERROR, joNewtonError);
                                pluginResult.setKeepCallback(true);
                                loginContext.sendPluginResult(pluginResult);
                            }
                        });


                        /**
                         * 2#
                         *
                         * iterate over parameters to initialize the login builder
                         *
                         */
                        Iterator<String> it = eventParams.keys();

                        while (it.hasNext()) {
                            String key = it.next();
                            LoginOptions loginOption;

                            try {
                                loginOption = LoginOptions.valueOf(key);
                            }
                            catch (IllegalArgumentException e) {
                                Log.e(LOG_TAG, "[action: login] JSON parameters error:'"+key+"' error: "+e.getMessage(), e);
                                callbackContext.error("Invalid login option: '"+key+"' error: "+e.getMessage());
                                return;
                            }

                            switch (loginOption) {
                                case customData:
                                    JSONObject customData = eventParams.getJSONObject(key);
                                    SimpleObject customDataSO = SimpleObject.fromJSONObject(customData);
                                    loginBuilder.setCustomData(customDataSO);
                                    break;

                                case externalID:
                                    loginBuilder.setExternalID(eventParams.getString(key));
                                    break;

                                case type:
                                    String type = eventParams.getString(key);

                                    try {
                                        loginFlowType = LoginFlowType.valueOf(type);
                                    }
                                    catch (IllegalArgumentException e) {
                                        Log.e(LOG_TAG, "[action: login] JSON parameters error LoginFlowType with unknow value");
                                        callbackContext.error("Invalid login option parameter value for LoginFlowType");
                                        return;
                                    }
                                    break;
                                default:
                                    // verify that all LoginOptions enum are handled in the switch!
                                    Log.w(LOG_TAG, "option key unknown!");
                            }
                        }

                        /**
                         * 3#
                         *
                         * then start the login flow
                         *
                         */
                        switch (loginFlowType) {
                            case external:
                                loginBuilder.getExternalLoginFlow()
                                        .startLoginFlow();
                                break;
                            case custom:
                                loginBuilder.getCustomLoginFlow()
                                        .startLoginFlow();
                                break;
                            default:
                                Log.e(LOG_TAG, "[action: login] JSON parameters error LoginFlowType with unhandled value");
                        }

                        /**
                         * set the plugin result context to be persistent (keep callback)
                         */
                        PluginResult pluginResult = new PluginResult(PluginResult.Status.NO_RESULT);
                        pluginResult.setKeepCallback(true);
                        loginContext.sendPluginResult(pluginResult);

                    } catch (JSONException e) {
                        Log.e(LOG_TAG, "[action: login] JSON parameters error:" + e.getMessage(), e);
                        callbackContext.error("Invalid parameters: "+e.getMessage());
                    } catch (SimpleObjectException e) {
                        Log.e(LOG_TAG, "[action: login] JSON parameters error:" + e.getMessage(), e);
                        callbackContext.error("Invalid parameters: "+e.getMessage());
                    } catch (OAuthException e) {
                        Log.e(LOG_TAG, "[action: login] OAuth error:" + e.getMessage(), e);
                        callbackContext.error("OAuth error: "+e.getMessage());
                    } catch (NewtonException e) {
                        Log.e(LOG_TAG, "[action: login] Newton error:" + e.getMessage(), e);
                        callbackContext.error("Newton error: "+e.getMessage());
                    } catch (UserAlreadyLoggedException e) {
                        Log.e(LOG_TAG, "[action: login] UserAlreadyLogged error:" + e.getMessage(), e);
                        callbackContext.error("UserAlreadyLogged error: "+e.getMessage());
                    }
                }
            });
        }
        else if ("isUserLogged".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {

                    try {
                        boolean logged = Newton.getSharedInstance().isUserLogged();

                        JSONObject jo = new JSONObject();
                        jo.put("isUserLogged", logged);
                        callbackContext.success(jo);

                    } catch (NewtonNotInitializedException e) {
                        Log.e(LOG_TAG, "[action: isUserLogged] NewtonNotInitialized error:" + e.getMessage(), e);
                        callbackContext.error("NewtonNotInitialized: "+e.getMessage());
                    } catch (JSONException e) {
                        Log.e(LOG_TAG, "[action: isUserLogged] JSON Error:"+e.getMessage());
                        callbackContext.error("JSON Error:"+e.getMessage());
                    }
                }
            });
        }
        else if ("getEnvironmentString".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {

                    try {
                        String environmentString = Newton.getSharedInstance().getEnvironmentString();

                        JSONObject jo = new JSONObject();
                        jo.put("environmentString", environmentString);
                        callbackContext.success(jo);

                    } catch (NewtonNotInitializedException e) {
                        Log.e(LOG_TAG, "[action: getEnvironmentString] NewtonNotInitialized error:" + e.getMessage(), e);
                        callbackContext.error("NewtonNotInitialized: "+e.getMessage());
                    } catch (JSONException e) {
                        Log.e(LOG_TAG, "[action: getEnvironmentString] JSON Error:"+e.getMessage());
                        callbackContext.error("JSON Error:"+e.getMessage());
                    }
                }
            });
        }
        else if ("userLogout".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {

                    try {
                        Newton.getSharedInstance().userLogout();
                        callbackContext.success();

                    } catch (NewtonNotInitializedException e) {
                        Log.e(LOG_TAG, "[action: getEnvironmentString] NewtonNotInitialized error:" + e.getMessage(), e);
                        callbackContext.error("NewtonNotInitialized: "+e.getMessage());
                    }
                }
            });
        }
        else if ("getUserMetaInfo".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    newtonEngine.getUserMetaInfo(new IMetaInfoCallBack() {
                        @Override
                        public void onSuccess(MetaInfo metaInfo) {
                            callbackContext.success(metaInfo.toJson());
                        }

                        @Override
                        public void onFailure(UserMetaInfoException e) {
                            Log.e(LOG_TAG, "[action: getUserMetaInfo] UserMetaInfo error:"+e.getMessage());
                            callbackContext.error(e.toString());
                        }
                    });
                }
            });
        }
        else if ("getUserToken".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {

                    try {
                        String userToken = Newton.getSharedInstance().getUserToken();

                        JSONObject jo = new JSONObject();
                        jo.put("userToken", userToken);
                        callbackContext.success(jo);

                    } catch (NewtonNotInitializedException e) {
                        Log.e(LOG_TAG, "[action: getUserToken] NewtonNotInitialized error:" + e.getMessage(), e);
                        callbackContext.error("NewtonNotInitialized: "+e.getMessage());
                    } catch (JSONException e) {
                        Log.e(LOG_TAG, "[action: getUserToken] JSON Error:"+e.getMessage());
                        callbackContext.error("JSON Error:"+e.getMessage());
                    }
                }
            });
        }
        else if ("getOAuthProviders".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {

                    try {
                        List<String> oAuthProviders = Newton.getSharedInstance().getOAuthProviders();

                        JSONObject jo = new JSONObject();
                        jo.put("oAuthProviders", oAuthProviders.toArray());
                        callbackContext.success(jo);

                    } catch (NewtonNotInitializedException e) {
                        Log.e(LOG_TAG, "[action: getOAuthProviders] NewtonNotInitialized error:" + e.getMessage(), e);
                        callbackContext.error("NewtonNotInitialized: "+e.getMessage());
                    } catch (JSONException e) {
                        Log.e(LOG_TAG, "[action: getOAuthProviders] JSON Error:"+e.getMessage());
                        callbackContext.error("JSON Error:"+e.getMessage());
                    }
                }
            });
        }
        else if ("rankContent".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {

                    try {
                        String contentId = data.getString(0);
                        RankingEvent.RankingScope scope = RankingEvent.RankingScope.valueOf(data.getString(1));
                        Double multipler = data.getDouble(2);

                        Newton.getSharedInstance().rankContent(contentId, scope, multipler);
                        callbackContext.success();

                    } catch (NewtonNotInitializedException e) {
                        Log.e(LOG_TAG, "[action: getEnvironmentString] NewtonNotInitialized error:" + e.getMessage(), e);
                        callbackContext.error("NewtonNotInitialized: "+e.getMessage());
                    } catch (JSONException e) {
                        Log.e(LOG_TAG, "[action: getEnvironmentString] JSON error:" + e.getMessage(), e);
                        callbackContext.error("JSON: "+e.getMessage());
                    } catch (NewtonException e) {
                        Log.e(LOG_TAG, "[action: getEnvironmentString] Newton error:" + e.getMessage(), e);
                        callbackContext.error("Newton: "+e.getMessage());
                    }
                }
            });
        }
        else if ("timedEventStart".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {

                    try {
                        String name = data.getString(0);
                        JSONObject dataJo = data.getJSONObject(1);
                        SimpleObject dataSo = SimpleObject.fromJSONObject(dataJo);

                        Newton.getSharedInstance().timedEventStart(name, dataSo);
                        callbackContext.success();

                    } catch (NewtonNotInitializedException e) {
                        Log.e(LOG_TAG, "[action: timedEventStart] NewtonNotInitialized error:" + e.getMessage(), e);
                        callbackContext.error("NewtonNotInitialized: "+e.getMessage());
                    } catch (JSONException e) {
                        Log.e(LOG_TAG, "[action: timedEventStart] JSON error:" + e.getMessage(), e);
                        callbackContext.error("JSON: "+e.getMessage());
                    } catch (NewtonException e) {
                        Log.e(LOG_TAG, "[action: timedEventStart] Newton error:" + e.getMessage(), e);
                        callbackContext.error("Newton: "+e.getMessage());
                    } catch (SimpleObjectException e) {
                        Log.e(LOG_TAG, "[action: timedEventStart] SimpleObject error:" + e.getMessage(), e);
                        callbackContext.error("SimpleObject: "+e.getMessage());
                    }
                }
            });
        }
        else if ("timedEventStop".equals(action)) {
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {

                    try {
                        String name = data.getString(0);
                        JSONObject dataJo = data.getJSONObject(1);
                        SimpleObject dataSo = SimpleObject.fromJSONObject(dataJo);

                        Newton.getSharedInstance().timedEventStop(name, dataSo);
                        callbackContext.success();

                    } catch (NewtonNotInitializedException e) {
                        Log.e(LOG_TAG, "[action: timedEventStop] NewtonNotInitialized error:" + e.getMessage(), e);
                        callbackContext.error("NewtonNotInitialized: "+e.getMessage());
                    } catch (JSONException e) {
                        Log.e(LOG_TAG, "[action: timedEventStop] JSON error:" + e.getMessage(), e);
                        callbackContext.error("JSON: "+e.getMessage());
                    } catch (NewtonException e) {
                        Log.e(LOG_TAG, "[action: timedEventStop] Newton error:" + e.getMessage(), e);
                        callbackContext.error("Newton: "+e.getMessage());
                    } catch (SimpleObjectException e) {
                        Log.e(LOG_TAG, "[action: timedEventStop] SimpleObject error:" + e.getMessage(), e);
                        callbackContext.error("SimpleObject: "+e.getMessage());
                    }
                }
            });
        }
        else {
            Log.e(LOG_TAG, "Invalid action : " + action);
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.INVALID_ACTION));
            return false;
        }

        return true;
    }

    /*
     * Sends the pushbundle extras to the client application.
     * If the client application isn't currently active, it is cached for later processing.
     */
    public static void sendPushToJs(PushObject push) {
        if (push != null) {
            if (gWebView != null && pushContext != null) {
                PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, convertPushToJson(push));
                pluginResult.setKeepCallback(true);
                pushContext.sendPluginResult(pluginResult);
            } else {
                Log.v(LOG_TAG, "sendPushToJs: caching push to send at a later time.");
                gCachedPushes.add(push);
            }
        }
    }

    /*
     * serializes a bundle to JSON.
     */
    private static JSONObject convertPushToJson(PushObject push) {
        Log.d(LOG_TAG, "convert push to json");
        try {
            JSONObject json = new JSONObject();
            JSONObject customs = new JSONObject();

            json.put("isRemote", push.isRemote());
            json.put("isRich", push.isRich());
            json.put("isSilent", push.isSilent());
            json.put("isShown", push.isShown());
            json.put("id", push.getPushId());
            json.put("body", push.getBody());
            json.put("title", push.getTitle());


            HashMap<String, Object> customFields = push.getCustomFields();

            Iterator<String> it = customFields.keySet().iterator();

            while (it.hasNext()) {
                String key = it.next();
                Object value = customFields.get(key);

                customs.put(key, value);

                Log.d(LOG_TAG, "key = " + key);
                Log.d(LOG_TAG, "value = " + value.toString());
            }

            json.put("customs", customs);

            Log.v(LOG_TAG, "convertPushToJson: " + json.toString());

            return json;
        }
        catch( JSONException e) {
            Log.e(LOG_TAG, "extrasToJSON: JSON exception");
        }
        return null;
    }

    private void clearAllNotifications() {
        final NotificationManager notificationManager = (NotificationManager) cordova.getActivity().getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancelAll();
    }

    public static void setApplicationIconBadgeNumber(Context context, int badgeCount) {
        if (badgeCount > 0) {
            ShortcutBadger.applyCount(context, badgeCount);
        } else {
            ShortcutBadger.removeCount(context);
        }
    }

    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);
        gForeground = false;

        //SharedPreferences prefs = getApplicationContext().getSharedPreferences(COM_ADOBE_PHONEGAP_PUSH, Context.MODE_PRIVATE);
        //if (prefs.getBoolean(CLEAR_NOTIFICATIONS, true)) {
        //    clearAllNotifications();
        //}
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        gForeground = true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        gForeground = false;
        gWebView = null;
    }

    public static boolean isActive() {
        return gWebView != null;
    }

}
