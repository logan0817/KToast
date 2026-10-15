package com.logan.ktoast.lifecycleconsumer;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import com.logan.ktoast.KToast;
import com.logan.ktoast.lifecycle.KToastLifecycleScope;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import java.util.ArrayList;
import com.logan.ktoast.config.KToastConfig;
import com.logan.ktoast.runtime.ToastHandle;

/** 独立验证生命周期扩展的 Java/Kotlin 调用与 R8 后的停止、恢复和延时取消行为。 */
public final class ConsumerActivity extends Activity {
    private TextView status;
    private KToastLifecycleScope scope;
    private final Owner owner = new Owner();

    private static final class Owner extends Lifecycle implements LifecycleOwner {
        private State state = State.CREATED;
        private final ArrayList<DefaultLifecycleObserver> observers = new ArrayList<>();
        public Lifecycle getLifecycle() { return this; }
        public State getCurrentState() { return state; }
        public void addObserver(LifecycleObserver observer) { observers.add((DefaultLifecycleObserver) observer); }
        public void removeObserver(LifecycleObserver observer) { observers.remove(observer); }
        void start() { state = State.STARTED; for (DefaultLifecycleObserver observer : new ArrayList<>(observers)) observer.onStart(this); }
        void stop() { state = State.CREATED; for (DefaultLifecycleObserver observer : new ArrayList<>(observers)) observer.onStop(this); }
    }
    private boolean verified;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        status = new TextView(this);
        setContentView(status);
    }

    @Override public void onResume() {
        super.onResume();
        if (verified) return;
        verified = true;
        status.post(() -> {
            try {
                KToast.setDebugMode(true);
                if (!KToast.INSTANCE.getDebugMode()) throw new AssertionError("Java debug setter did not enable debugging");
                KToast.setDebugMode(false);
                if (KToast.INSTANCE.getDebugMode()) throw new AssertionError("Java debug setter did not disable debugging");
                KToast.show("legacy Java", Toast.LENGTH_SHORT);
                KToast.cancel();
                scope = KToastLifecycleScope.create(this, owner);
                if (!scope.show("before start").isCanceled()) throw new AssertionError("Created owner accepted request");
                owner.start();
                KToastConfig config = new KToastConfig();
                config.setAnimationDuration(0L);
                ToastHandle handle = scope.show("scoped Java", config);
                if (handle.getState() != ToastHandle.State.SHOWING || !handle.cancel()) {
                    throw new AssertionError("Java scope presentation or cancellation failed");
                }
                KotlinAssertionsKt.verifyKotlinConsumer(scope);
                ToastHandle delayed = scope.showDelayed("old round", 60000L);
                owner.stop();
                if (!delayed.isCanceled()) throw new AssertionError("Stop retained delayed request");
                owner.start();
                if (scope.show("new round", config).getState() != ToastHandle.State.SHOWING) throw new AssertionError("Restart failed");
                scope.close();
                if (!scope.isClosed()) throw new AssertionError("Scope did not close");
                status.setText("PASS: lifecycle Java/Kotlin, STARTED, stop/restart, delayed cancellation");
                Log.i("KToastConsumer", status.getText().toString());
            } catch (Throwable error) {
                status.setText("FAIL: " + error.getClass().getName());
                Log.e("KToastConsumer", "Consumer verification failed", error);
                throw new AssertionError("Consumer verification failed", error);
            }
        });
    }

    @Override public void onDestroy() {
        if (scope != null) scope.close();
        super.onDestroy();
    }
}
