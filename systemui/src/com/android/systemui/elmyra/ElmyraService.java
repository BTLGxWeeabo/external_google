package com.google.android.systemui.elmyra;

import android.content.Context;
import android.metrics.LogMaker;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;
import android.os.PowerManager.WakeLock;
import android.provider.Settings;
import android.text.TextUtils;
import android.os.SystemClock;
import android.os.UserHandle;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.utils.ActionHandler;
import com.android.internal.utils.ActionUtils;
import com.android.internal.utils.SmartPackageMonitor;
import com.android.internal.utils.Config.ActionConfig;
import com.android.internal.utils.SmartPackageMonitor.PackageState;
import com.google.android.systemui.elmyra.actions.Action;
import com.google.android.systemui.elmyra.actions.Action.Listener;
import com.google.android.systemui.elmyra.feedback.FeedbackEffect;
import com.google.android.systemui.elmyra.gates.Gate;
import com.google.android.systemui.elmyra.sensors.GestureSensor;
import com.google.android.systemui.elmyra.sensors.GestureSensor.DetectionProperties;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ElmyraService {
    protected final Listener mActionListener = new C15821();
    private final List<Action> mActions;
    private final Context mContext;
    private final List<FeedbackEffect> mFeedbackEffects;
    protected final Gate.Listener mGateListener = new C15832();
    private final List<Gate> mGates;
    private final GestureSensor.Listener mGestureListener = new GestureListener(this, null);
    private final GestureSensor mGestureSensor;
    private Action mLastActiveAction;
    private long mLastPrimedGesture;
    private int mLastStage;
    private final MetricsLogger mLogger;
    private final PowerManager mPowerManager;
    private final WakeLock mWakeLock;
    private final SmartPackageMonitor mPackageMonitor = new SmartPackageMonitor();

    private SmartPackageMonitor.PackageChangedListener mPackageListener = new SmartPackageMonitor.PackageChangedListener() {
        @Override
        public void onPackageChanged(String pkg, PackageState state) {
            if (state == PackageState.PACKAGE_REMOVED
                    || state == PackageState.PACKAGE_CHANGED) {
                final Context ctx = mContext;
                final Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                String shortConfig = Settings.Secure.getStringForUser(ctx.getContentResolver(),
                        Settings.Secure.SQUEEZE_SELECTION_SMART_ACTIONS, UserHandle.USER_CURRENT);
                String shortAction = ActionConfig.getActionFromDelimitedString(ctx, shortConfig,
                        ActionHandler.SYSTEMUI_TASK_NO_ACTION);
                String longConfig = Settings.Secure.getStringForUser(ctx.getContentResolver(),
                        Settings.Secure.LONG_SQUEEZE_SELECTION_SMART_ACTIONS, UserHandle.USER_CURRENT);
                String longAction = ActionConfig.getActionFromDelimitedString(ctx, longConfig,
                        ActionHandler.SYSTEMUI_TASK_NO_ACTION);
                if (!shortAction.startsWith(ActionHandler.SYSTEM_PREFIX)) {
                    if (intentActionNeedsClearing(ctx, shortAction)) {
                        ActionConfig shortActionConfig = new ActionConfig(ctx,
                                ActionHandler.SYSTEMUI_TASK_NO_ACTION);
                        Settings.Secure.putStringForUser(ctx.getContentResolver(),
                                Settings.Secure.SQUEEZE_SELECTION_SMART_ACTIONS,
                                shortActionConfig.toDelimitedString(), UserHandle.USER_CURRENT);
                    }
                }
                if (!longAction.startsWith(ActionHandler.SYSTEM_PREFIX)) {
                    if (intentActionNeedsClearing(ctx, longAction)) {
                        ActionConfig longActionConfig = new ActionConfig(ctx,
                                ActionHandler.SYSTEMUI_TASK_NO_ACTION);
                        Settings.Secure.putStringForUser(ctx.getContentResolver(),
                                Settings.Secure.LONG_SQUEEZE_SELECTION_SMART_ACTIONS,
                                longActionConfig.toDelimitedString(), UserHandle.USER_CURRENT);
                    }
                }
                    }
                });
                thread.setPriority(Process.THREAD_PRIORITY_BACKGROUND);
                thread.start();
            }
        }
    };

    private static boolean intentActionNeedsClearing(Context ctx, String action) {
        if (action.startsWith(ActionHandler.SYSTEM_PREFIX)) {
            return false;
        }
        String resolvedName = ActionUtils.getFriendlyNameForUri(ctx, action);
        if (resolvedName == null || TextUtils.equals(resolvedName, action)) {
            return true;
        }
        return false;
    }

    /* renamed from: com.google.android.systemui.elmyra.ElmyraService$1 */
    class C15821 implements Listener {
        C15821() {
        }

        @Override
        public void onActionAvailabilityChanged(Action action) {
            updateSensorListener();
        }
    }

    /* renamed from: com.google.android.systemui.elmyra.ElmyraService$2 */
    class C15832 implements Gate.Listener {
        C15832() {
        }

        @Override
        public void onGateChanged(Gate gate) {
            updateSensorListener();
        }
    }

    private class GestureListener implements GestureSensor.Listener {
        private GestureListener() {
        }

        GestureListener(ElmyraService elmyraService, C15821 c15821) {
            this();
        }

        public void onGestureDetected(GestureSensor gestureSensor, DetectionProperties detectionProperties) {
            mWakeLock.acquire(2000);
            boolean isInteractive = mPowerManager.isInteractive();
            int i = (detectionProperties == null || !detectionProperties.isHostSuspended()) ? !isInteractive ? 2 : 1 : 3;
            LogMaker latency = new LogMaker(999).setType(4).setSubtype(i).setLatency(isInteractive ? SystemClock.uptimeMillis() - mLastPrimedGesture : 0);
            mLastPrimedGesture = 0;
            Action activeAction = updateActiveAction();
            if (activeAction != null) {
                activeAction.onTrigger(detectionProperties);
                mFeedbackEffects.forEach(feedbackEff -> feedbackEff.onResolve(detectionProperties));
            }
            latency.setPackageName(activeAction.getClass().getName());
            mLogger.write(latency);
        }

        public void onGestureProgress(GestureSensor gestureSensor, float f, int i) {
            Action activeAction = updateActiveAction();
            if (activeAction != null) {
                activeAction.onProgress(f, i);
                mFeedbackEffects.forEach(feedbackEff -> feedbackEff.onProgress(f, i));
            }
            if (i != mLastStage) {
                long uptimeMillis = SystemClock.uptimeMillis();
                if (i == 2) {
                    mLogger.action(998);
                    mLastPrimedGesture = uptimeMillis;
                } else if (i == 0 && mLastPrimedGesture != 0) {
                    mLogger.write(new LogMaker(997).setType(4).setLatency(uptimeMillis - mLastPrimedGesture));
                }
                mLastStage = i;
            }
        }
    }

    public ElmyraService(Context context, ServiceConfiguration serviceConfiguration) {
        mContext = context;
        mLogger = new MetricsLogger();
        mPowerManager = (PowerManager) mContext.getSystemService("power");
        mWakeLock = mPowerManager.newWakeLock(1, "Elmyra/ElmyraService");

        mActions = new ArrayList(serviceConfiguration.getActions());
        mActions.forEach(action -> action.setListener(mActionListener));

        mFeedbackEffects = new ArrayList(serviceConfiguration.getFeedbackEffects());

        mGates = new ArrayList(serviceConfiguration.getGates());
        mGates.forEach(gate -> gate.setListener(mGateListener));
        mGestureSensor = serviceConfiguration.getGestureSensor();
        if (mGestureSensor != null) {
            mGestureSensor.setGestureListener(mGestureListener);
        }
        updateSensorListener();
        mPackageMonitor.register(mContext, new Handler());
        mPackageMonitor.addListener(mPackageListener);
    }

    private Gate getBlockingGate() {
        for (Gate gate : mGates) {
            if (gate.isBlocking()) {
                return gate;
            }
        }
        // If we are here, we haven't found a blocking gate.
        return null;
    }

    private Action firstAvailableAction() {
        // TODO: put some logic as soon as we add more actions.
        return mActions.get(0);
    }

    private void startListening() {
        if (mGestureSensor != null && !mGestureSensor.isListening()) {
            mGestureSensor.startListening();
        }
    }

    private void stopListening() {
        if (mGestureSensor != null && mGestureSensor.isListening()) {
            mGestureSensor.stopListening();
            for (int i = 0; i < mFeedbackEffects.size(); i++) {
                mFeedbackEffects.get(i).onRelease();
            }
            Action updateActiveAction = updateActiveAction();
            if (updateActiveAction != null) {
                updateActiveAction.onProgress(0.0f, 0);
            }
        }
    }

    private Action updateActiveAction() {
        Action firstAvailableAction = firstAvailableAction();
        if (!(mLastActiveAction == null || firstAvailableAction == mLastActiveAction)) {
            mLastActiveAction.onProgress(0.0f, 0);
        }
        mLastActiveAction = firstAvailableAction;
        return firstAvailableAction;
    }

    protected void updateSensorListener() {
        Action updateActiveAction = updateActiveAction();
        if (updateActiveAction == null) {
            // Deactivate gates
            mGates.forEach(gate -> gate.deactivate());
            stopListening();
            return;
        }
        // Activate gates
        mGates.forEach(gate -> gate.activate());
        Gate blockingGate = getBlockingGate();
        if (blockingGate != null) {
            stopListening();
            return;
        }
        startListening();
    }
}
