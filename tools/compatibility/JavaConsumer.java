package compatibility;

import com.logan.ktoast.config.KToastConfig;
import com.logan.ktoast.runtime.KToastBehavior;
import com.logan.ktoast.runtime.KToastCallbacks;
import com.logan.ktoast.runtime.KToastDisplayMode;
import com.logan.ktoast.runtime.ToastHandle;
import kotlin.Unit;

/** 先对 1.0.5 编译，再使用候选 classes.jar 运行，验证已有 Java 调用的链接。 */
public final class JavaConsumer {
    public static void main(String[] args) throws Exception {
        KToastConfig config = new KToastConfig();
        config.setTextSize(16f);
        config.setContentFactory((context, message, style) -> null);
        if (config.getTextSize() != 16f || config.getMaxLines() != 4) {
            throw new AssertionError("Configuration defaults or setters changed");
        }

        KToastBehavior behavior = new KToastBehavior();
        behavior.setGroupKey("upload");
        KToastBehavior copy = behavior.copy(KToastDisplayMode.QUEUE, 500L, "upload", "java");
        if (copy.getDisplayMode() != KToastDisplayMode.QUEUE || !"upload".equals(copy.getGroupKey())) {
            throw new AssertionError("Behavior copy changed");
        }

        KToastCallbacks callbacks = new KToastCallbacks();
        callbacks.setOnDismiss((handle, reason) -> Unit.INSTANCE);
        if (callbacks.copy(null, callbacks.getOnDismiss(), null, null).getOnDismiss() == null) {
            throw new AssertionError("Callbacks copy changed");
        }
        if (ToastHandle.DismissReason.values().length != 5) {
            throw new AssertionError("Dismiss reasons changed");
        }

        // 不初始化 Android 单例，仍检查老入口的 JVM 链接与重载。
        Class<?> toast = Class.forName("com.logan.ktoast.KToast", false, JavaConsumer.class.getClassLoader());
        toast.getMethod("show", CharSequence.class, int.class);
        toast.getMethod("showHandle", CharSequence.class);
        toast.getMethod("showDelayed", CharSequence.class, long.class);
        toast.getMethod("cancelGroup", String.class);
        System.out.println("Java consumer compiled against 1.0.5 linked successfully");
    }
}
