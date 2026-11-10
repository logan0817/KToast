package com.logan.ktoast.compatibility;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import com.logan.ktoast.KToast;
import com.logan.ktoast.KToastScope;
import com.logan.ktoast.config.KToastConfig;
import com.logan.ktoast.config.KToastAnimation;
import com.logan.ktoast.config.KToastStyle;
import com.logan.ktoast.config.KToastType;
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
                KToast.setDebugMode(true);
                if (!KToast.INSTANCE.getDebugMode()) throw new AssertionError("Java debug setter did not enable debugging");
                KToast.setDebugMode(false);
                if (KToast.INSTANCE.getDebugMode()) throw new AssertionError("Java debug setter did not disable debugging");
                KToast.show("legacy Java", Toast.LENGTH_SHORT);
                KToast.cancel();
                scope = KToastScope.create(this);
                KToastConfig config = new KToastConfig();
                config.setAnimationDuration(0L);
                KToastAnimation animation = new KToastAnimation(new android.view.animation.OvershootInterpolator(4f),
                        new android.view.animation.DecelerateInterpolator(), 1.1f);
                config.setAnimation(animation);
                if (config.snapshot().getAnimation() != animation) {
                    throw new AssertionError("Java animation snapshot failed");
                }
                ToastHandle handle = scope.show("scoped Java", config);
                if (handle.getState() != ToastHandle.State.SHOWING || !handle.cancel()) {
                    throw new AssertionError("Java scope presentation or cancellation failed");
                }
                KToastStyle brand = new KToastStyle(KToastType.SUCCESS, R.style.KToastConsumerBrand);
                KToastConfig resolved = brand.resolve(this);
                if (resolved.getBackgroundColor() != 0xFF203040 || resolved.getTextColor() != 0xFFFFFFFF
                        || resolved.getPaddingHorizontal() != 19) {
                    throw new AssertionError("Java XML style resolution failed");
                }
                ToastHandle globalStyled = KToast.showStyled("styled global Java", brand);
                if (globalStyled.getState() != ToastHandle.State.SHOWING || !globalStyled.cancel()) {
                    throw new AssertionError("Java global styled overload failed");
                }
                ToastHandle styled = scope.showStyled("styled scope Java", new KToastStyle(KToastType.INFO), Toast.LENGTH_SHORT);
                if (styled.getState() != ToastHandle.State.SHOWING || !styled.cancel()) {
                    throw new AssertionError("Java scoped styled overload failed");
                }
                KotlinAssertionsKt.verifyKotlinConsumer(scope);
                scope.close();
                if (!scope.isClosed()) throw new AssertionError("Scope did not close");
                status.setText("PASS: legacy Java, scoped Java/Kotlin, queue, delayed cancellation, styled semantics/XML");
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
