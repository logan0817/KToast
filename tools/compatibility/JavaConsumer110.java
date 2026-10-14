package compatibility;

import com.logan.ktoast.KToastScope;
import com.logan.ktoast.KToastScopeOptions;
import com.logan.ktoast.config.KToastConfig;
import com.logan.ktoast.runtime.ToastHandle;

/** 编译时使用正式 1.1.0；运行时只替换库，保护页面 API 的二进制链接。 */
public final class JavaConsumer110 {
    public static void main(String[] args) throws Exception {
        KToastScopeOptions options = new KToastScopeOptions();
        KToastScopeOptions copy = options.copy(false, true, false, true, null);
        if (copy.getAvoidSystemBars() || !copy.getAvoidIme() || !copy.getFollowHostTheme()) {
            throw new AssertionError("Scope options changed");
        }
        KToastScope.class.getMethod("show", CharSequence.class, KToastConfig.class);
        KToastScope.class.getMethod("showDelayed", CharSequence.class, long.class);
        KToastScope.class.getMethod("close");
        ToastHandle.class.getMethod("getState");
        System.out.println("PASS: Java consumer compiled against 1.1.0");
    }
}
