/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.dashclock.api;

import com.google.android.apps.dashclock.api.internal.IExtension;
import com.google.android.apps.dashclock.api.internal.IExtensionHost;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

/**
 * Base class for a DashClock extension. Extensions are a way for other apps to show additional
 * status information within DashClock widgets that the user may add to the lockscreen or home
 * screen. A limited amount of status information is supported. See the {@link ExtensionData} class
 * for the types of information that can be displayed.
 *
 * <h3>Subclassing {@link DashClockExtension}</h3>
 *
 * Subclasses must implement at least the {@link #onUpdateData(int)} method, which will be called
 * when DashClock requests updated data to show for this extension. Once the extension has new
 * data to show, call {@link #publishUpdate(ExtensionData)} to pass the data to the main DashClock
 * process. {@link #onUpdateData(int)} will by default be called roughly once per hour, but
 * extensions can use methods such as {@link #setUpdateWhenScreenOn(boolean)} and
 * {@link #addWatchContentUris(String[])} to request more frequent updates.
 *
 * <p>
 * Subclasses can also override the {@link #onInitialize(boolean)} method to perform basic
 * initialization each time a connection to DashClock is established or re-established.
 *
 * <h3>Registering extensions</h3>
 * An extension is simply a service that the DashClock process binds to. Subclasses of this
 * base {@link DashClockExtension} class should thus be declared as <code>&lt;service&gt;</code>
 * components in the application's <code>AndroidManifest.xml</code> file.
 *
 * <p>
 * The main DashClock app discovers available extensions using Android's {@link Intent} mechanism.
 * Ensure that your <code>service</code> definition includes an <code>&lt;intent-filter&gt;</code>
 * with an action of {@link #ACTION_EXTENSION}. Also make sure to require the
 * {@link #PERMISSION_READ_EXTENSION_DATA} permission so that only DashClock can bind to your
 * service and request updates. Lastly, there are a few <code>&lt;meta-data&gt;</code> elements that
 * you should add to your service definition:
 *
 * <ul>
 * <li><code>protocolVersion</code> (required): should be <strong>1</strong>.</li>
 * <li><code>description</code> (required): should be a one- or two-sentence description
 * of the extension, as a string.</li>
 * <li><code>settingsActivity</code> (optional): if present, should be the qualified
 * component name for a configuration activity in the extension's package that DashClock can offer
 * to the user for customizing the extension.</li>
 * <li><code>worldReadable</code> (optional): if present and true (default is false), will allow
 * other apps besides DashClock to read data for this extension.</li>
 * </ul>
 *
 * <h3>Example</h3>
 *
 * Below is an example extension declaration in the manifest:
 *
 * <pre class="prettyprint">
 * &lt;service android:name=".ExampleExtension"
 *     android:icon="@drawable/ic_extension_example"
 *     android:label="@string/extension_title"
 *     android:permission="com.google.android.apps.dashclock.permission.READ_EXTENSION_DATA"&gt;
 *     &lt;intent-filter&gt;
 *         &lt;action android:name="com.google.android.apps.dashclock.Extension" /&gt;
 *     &lt;/intent-filter&gt;
 *     &lt;meta-data android:name="protocolVersion" android:value="2" /&gt;
 *     &lt;meta-data android:name="worldReadable" android:value="true" /&gt;
 *     &lt;meta-data android:name="description"
 *         android:value="@string/extension_description" /&gt;
 *     &lt;!-- A settings activity is optional --&gt;
 *     &lt;meta-data android:name="settingsActivity"
 *         android:value=".ExampleSettingsActivity" /&gt;
 * &lt;/service&gt;
 * </pre>
 *
 * If a <code>settingsActivity</code> meta-data element is present, an activity with the given
 * component name should be defined and exported in the application's manifest as well. DashClock
 * will set the {@link #EXTRA_FROM_DASHCLOCK_SETTINGS} extra to true in the launch intent for this
 * activity. An example is shown below:
 *
 * <pre class="prettyprint">
 * &lt;activity android:name=".ExampleSettingsActivity"
 *     android:label="@string/title_settings"
 *     android:exported="true" /&gt;
 * </pre>
 *
 * Finally, below is a simple example {@link DashClockExtension} subclass that shows static data in
 * DashClock:
 *
 * <pre class="prettyprint">
 * public class ExampleExtension extends DashClockExtension {
 *     protected void onUpdateData(int reason) {
 *         publishUpdate(new ExtensionData()
 *                 .visible(true)
 *                 .icon(R.drawable.ic_extension_example)
 *                 .status("Hello")
 *                 .expandedTitle("Hello, world!")
 *                 .expandedBody("This is an example.")
 *                 .clickIntent(new Intent(Intent.ACTION_VIEW,
 *                         Uri.parse("http://www.google.com"))));
 *     }
 * }
 * </pre>
 */
public abstract class DashClockExtension extends Service {
    private static final String TAG = "DashClockExtension";

    /**
     * Indicates that {@link #onUpdateData(int)} was triggered for an unknown reason. This should
     * be treated as a generic update (similar to {@link #UPDATE_REASON_PERIODIC}.
     */
    public static final int UPDATE_REASON_UNKNOWN = 0;

    /**
     * Indicates that this is the first call to {@link #onUpdateData(int)} since the connection to
     * the main DashClock app was established. Note that updates aren't requested in response to
     * reconnections after a connection is lost.
     */
    public static final int UPDATE_REASON_INITIAL = 1;

    /**
     * Indicates that {@link #onUpdateData(int)} was triggered due to a normal perioidic refresh
     * of extension data.
     */
    public static final int UPDATE_REASON_PERIODIC = 2;

    /**
     * Indicates that {@link #onUpdateData(int)} was triggered because settings for this extension
     * may have changed.
     */
    public static final int UPDATE_REASON_SETTINGS_CHANGED = 3;

    /**
     * Indicates that {@link #onUpdateData(int)} was triggered because content changed on a content
     * URI previously registered with {@link #addWatchContentUris(String[])}.
     */
    public static final int UPDATE_REASON_CONTENT_CHANGED = 4;

    /**
     * Indicates that {@link #onUpdateData(int)} was triggered because the device screen turned on
     * and the extension has called
     * {@link #setUpdateWhenScreenOn(boolean) setUpdateWhenScreenOn(true)}.
     */
    public static final int UPDATE_REASON_SCREEN_ON = 5;

    /**
     * Indicates that {@link #onUpdateData(int)} was triggered because the user explicitly requested
     * that the extension be updated.
     *
     * @since v2
     */
    public static final int UPDATE_REASON_MANUAL = 6;

    /**
     * The {@link Intent} action representing a DashClock extension. This service should
     * declare an <code>&lt;intent-filter&gt;</code> for this action in order to register with
     * DashClock.
     */
    public static final String ACTION_EXTENSION = "com.google.android.apps.dashclock.Extension";

    /**
     * Boolean extra that will be set to true when DashClock starts extension settings activities.
     * Check for this extra in your settings activity if you need to adjust your UI depending on
     * whether or not the user came from DashClock's settings screen.
     *
     * @since v2
     */
    public static final String EXTRA_FROM_DASHCLOCK_SETTINGS
            = "com.google.android.apps.dashclock.extra.FROM_DASHCLOCK_SETTINGS";

    /**
     * The permission that DashClock extensions should require callers to have before providing
     * any status updates. Permission checks are implemented automatically by the base class.
     */
    public static final String PERMISSION_READ_EXTENSION_DATA
            = "com.google.android.apps.dashclock.permission.READ_EXTENSION_DATA";

    /**
     * The protocol version with which the world readability option became available.
     *
     * @since v2
     */
    private static final int PROTOCOL_VERSION_WORLD_READABILITY = 2;

    private boolean mInitialized = false;
    private boolean mIsWorldReadable = false;
    private IExtensionHost mHost;

    private volatile Looper mServiceLooper;
    private volatile Handler mServiceHandler;

    protected DashClockExtension() {
        super();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        loadMetaData();

        HandlerThread thread = new HandlerThread(
                "DashClockExtension:" + getClass().getSimpleName());
        thread.start();

        mServiceLooper = thread.getLooper();
        mServiceHandler = new Handler(mServiceLooper);
    }

    @Override
    public void onDestroy() {
        mServiceHandler.removeCallbacksAndMessages(null); // remove all callbacks
        mServiceLooper.quit();
    }

    private void loadMetaData() {
        PackageManager pm = getPackageManager();
        try {
            ServiceInfo si = pm.getServiceInfo(
                    new ComponentName(this, getClass()),
                    PackageManager.GET_META_DATA);
            Bundle metaData = si.metaData;
            if (metaData != null) {
                int protocolVersion = metaData.getInt("protocolVersion");
                mIsWorldReadable = protocolVersion >= PROTOCOL_VERSION_WORLD_READABILITY
                        && metaData.getBoolean("worldReadable");
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Could not load metadata (e.g. world readable) for extension.");
        }
    }

    @Override
    public final IBinder onBind(Intent intent) {
        return mBinder;
    }

    private IExtension.Stub mBinder = new IExtension.Stub() {
        @Override
        public void onInitialize(IExtensionHost host, boolean isReconnect)
                throws RemoteException {
            if (!mIsWorldReadable) {
                // If not world readable, check the signature of the [first] package with the given
                // UID against the known-good official DashClock app signature.
                boolean verified = false;
                PackageManager pm = getPackageManager();
                String[] packages = pm.getPackagesForUid(getCallingUid());
                if (packages != null && packages.length > 0) {
                    try {
                        PackageInfo pi = pm.getPackageInfo(packages[0],
                                PackageManager.GET_SIGNATURES);
                        if (pi.signatures != null
                                && pi.signatures.length == 1
                                && DASHCLOCK_SIGNATURE.equals(pi.signatures[0])) {
                            verified = true;
                        }
                    } catch (PackageManager.NameNotFoundException ignored) {
                    }
                }

                if (!verified) {
                    Log.e(TAG, "Caller is not official DashClock app and this "
                            + "extension is not world-readable.");
                    throw new SecurityException("Caller is not official DashClock app and this "
                            + "extension is not world-readable.");
                }
            }

            mHost = host;

            if (!mInitialized) {
                DashClockExtension.this.onInitialize(isReconnect);
                mInitialized = true;
            }
        }

        @Override
        public void onUpdate(final int reason) throws RemoteException {
            if (!mInitialized) {
                return;
            }

            // Do this in a separate thread
            mServiceHandler.post(new Runnable() {
                @Override
                public void run() {
                    DashClockExtension.this.onUpdateData(reason);
                }
            });
        }
    };

    /**
     * Called when a connection with the main DashClock app has been established or re-established
     * after a previous one was lost. In this latter case, the parameter <code>isReconnect</code>
     * will be true. Override this method to perform basic extension initialization before calls
     * to {@link #onUpdateData(int)} are made.
     *
     * @param isReconnect Whether or not this call is being made after a connection was dropped and
     *                    a new connection has been established.
     */
    protected void onInitialize(boolean isReconnect) {
    }

    /**
     * Called when the DashClock app process is requesting that the extension provide updated
     * information to show to the user. Implementations can choose to do nothing, or more commonly,
     * provide an update using the {@link #publishUpdate(ExtensionData)} method. Note that doing
     * nothing doesn't clear existing data. To clear any existing data, call
     * {@link #publishUpdate(ExtensionData)} with <code>null</code> data.
     *
     * @param reason The reason for the update. See {@link #UPDATE_REASON_PERIODIC} and related
     *               constants for more details.
     */
    protected abstract void onUpdateData(int reason);

    /**
     * Notifies the main DashClock app that new data is available for the extension and should
     * potentially be shown to the user. Note that this call does not necessarily need to be made
     * from inside the {@link #onUpdateData(int)} method, but can be made only after
     * {@link #onInitialize(boolean)} has been called. If you only call this from within
     * {@link #onUpdateData(int)} this is already ensured.
     *
     * @param data The data to show, or <code>null</code> if existing data should be cleared (hiding
     *             the extension from view).
     */
    protected final void publishUpdate(ExtensionData data) {
        try {
            mHost.publishUpdate(data);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't publish updated extension data.", e);
        }
    }

    /**
     * Requests that the main DashClock app watch the given content URIs (using
     * {@link android.content.ContentResolver#registerContentObserver(android.net.Uri, boolean,
     * android.database.ContentObserver) ContentResolver.registerContentObserver})
     * and call this extension's {@link #onUpdateData(int)} method when changes are observed.
     * This should generally be called in the {@link #onInitialize(boolean)} method.
     *
     * @param uris The URIs to watch.
     */
    protected final void addWatchContentUris(String[] uris) {
        try {
            mHost.addWatchContentUris(uris);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't watch content URIs.", e);
        }
    }

    /**
     * Requests that the main DashClock app call (or not call) this extension's
     * {@link #onUpdateData(int)} method when the screen turns on (the phone resumes from idle).
     * This should generally be called in the {@link #onInitialize(boolean)} method.
     *
     * @see Intent#ACTION_SCREEN_ON
     * @param updateWhenScreenOn Whether or not a call to {@link #onUpdateData(int)} method when
     *                           the screen turns on.
     */
    protected final void setUpdateWhenScreenOn(boolean updateWhenScreenOn) {
        try {
            mHost.setUpdateWhenScreenOn(updateWhenScreenOn);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't set the extension to update upon ACTION_SCREEN_ON.", e);
        }
    }

    /**
     * The signature of the official DashClock app (net.nurik.roman.dashclock). Used to
     * compare caller when {@link #mIsWorldReadable} is false.
     */
    private static final Signature DASHCLOCK_SIGNATURE = new Signature(""
            + "308203523082023aa00302010202044c1132a9300d06092a864886f70d0101050500306b310b30090603"
            + "550406130255533110300e06035504081307556e6b6e6f776e3110300e06035504071307556e6b6e6f77"
            + "6e3110300e060355040a1307556e6b6e6f776e3110300e060355040b1307556e6b6e6f776e3114301206"
            + "03550403130b526f6d616e204e7572696b301e170d3130303631303138343435375a170d333731303236"
            + "3138343435375a306b310b30090603550406130255533110300e06035504081307556e6b6e6f776e3110"
            + "300e06035504071307556e6b6e6f776e3110300e060355040a1307556e6b6e6f776e3110300e06035504"
            + "0b1307556e6b6e6f776e311430120603550403130b526f6d616e204e7572696b30820122300d06092a86"
            + "4886f70d01010105000382010f003082010a02820101008906222723a4b30dca6f0702b041e6f361e38e"
            + "35105ec530bf43f4f1786737fefe6ccfa3b038a3700ea685dd185112a0a8f96327d3373de28e05859a87"
            + "bde82372baed5618082121d6946e4affbdfb6771abb782147d58a2323518b34efcce144ec3e45fb2556e"
            + "ba1c40b42ccbcc1266c9469b5447edf09d5cf8e2ed62cfb3bd902e47f48a11a815a635c3879c882eae92"
            + "3c7f73bfba4039b7c19930617e3326fa163b924eda398bacc0d6ef8643a32223ce1d767734e866553ad5"
            + "0d11fb22ac3a15ba021a6a3904a95ed65f54142256cb0db90038dd55adfeeb18d3ffb085c4380817268f"
            + "039119ecbdfca843e4b82209947fd88470b3d8c76fc15878fbc4f10203010001300d06092a864886f70d"
            + "0101050500038201010047063efdd5011adb69cca6461a57443fef59243f85e5727ec0d67513bb04b650"
            + "b1144fc1f54e09789c278171c52b9305a7265cafc13b89d91eb37ddce34a5c1f17c8c36f86c957c4e9ca"
            + "cc19e6822e0a5711f2cfba2c5913ba582ab69485548b13072bc736310b9da85a716d0418e6449450ceda"
            + "dfc1c897f93ed6189cfa0a02b893125bd4b1c4e4dd50c1ad33e221120b8488841763a3361817081e7691"
            + "1e76d3adcf94b23c758ceb955f9fdf8ef4a8351fc279867a25729f081b511209e96dfa8520225b810072"
            + "de5e8eefc1a6cc22f46857e2cc4fd1a1eaac76054f34352b63c9d53691515b42cc771f195343e61397cb"
            + "7b04ada2a627410d29c214976d13");
}
