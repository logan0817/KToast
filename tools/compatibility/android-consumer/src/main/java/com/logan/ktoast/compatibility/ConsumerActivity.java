package com.logan.ktoast.compatibility;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import com.logan.ktoast.KToast;
import com.logan.ktoast.KToastScope;
import com.logan.ktoast.config.KToastConfig;
import com.logan.ktoast.runtime.ToastHandle;

/** 无 AppCompat、Compose 或测试框架依赖的低门槛消费者，也用于 R8 设备冒烟。 */
public final class ConsumerActivity extends Activity {
    private TextView status;
    private KToastScope scope;
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
                KToast.show("legacy Java", Toast.LENGTH_SHORT);
                KToast.cancel();
                scope = KToastScope.create(this);
                KToastConfig config = new KToastConfig();
                config.setAnimationDuration(0L);
                ToastHandle handle = scope.show("scoped Java", config);
                if (handle.getState() != ToastHandle.State.SHOWING || !handle.cancel()) {
                    throw new AssertionError("Java scope presentation or cancellation failed");
                }
                KotlinAssertionsKt.verifyKotlinConsumer(scope);
                scope.close();
                if (!scope.isClosed()) throw new AssertionError("Scope did not close");
                status.setText("PASS: legacy Java, scoped Java/Kotlin, queue, delayed cancellation");
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
