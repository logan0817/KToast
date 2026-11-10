package compatibility;

import android.app.Activity;
import androidx.lifecycle.LifecycleOwner;
import com.logan.ktoast.lifecycle.KToastLifecycleScope;
import com.logan.ktoast.runtime.KToastRuntimePolicy;
import com.logan.ktoast.runtime.ToastDropReason;
import com.logan.ktoast.runtime.ToastHandle;

/** 以正式 1.2.0 双模块编译，再保持字节码不变链接新候选。 */
public final class JavaConsumer120 {
    public static void main(String[] args) throws Exception {
        KToastRuntimePolicy policy = new KToastRuntimePolicy(10, 1000L, 20);
        KToastRuntimePolicy copy = policy.copy(5, policy.getMaxQueueWaitMillis(), policy.getMaxDedupeEntries());
        if (copy.getMaxPendingRequests() != 5 || copy.getMaxQueueWaitMillis() != 1000L) {
            throw new AssertionError("Runtime policy changed");
        }
        if (ToastDropReason.valueOf("EXPIRED") != ToastDropReason.EXPIRED) {
            throw new AssertionError("Drop reason changed");
        }
        ToastHandle.class.getMethod("getDropReason");
        KToastLifecycleScope.class.getMethod("create", Activity.class, LifecycleOwner.class);
        KToastLifecycleScope.class.getMethod("show", CharSequence.class);
        KToastLifecycleScope.class.getMethod("showDelayed", CharSequence.class, long.class);
        KToastLifecycleScope.class.getMethod("close");
        System.out.println("PASS: Java consumer compiled against 1.2.0 core/lifecycle");
    }
}
