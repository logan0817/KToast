# 保持配置类不被混淆，确保 DSL 属性名可用
-keep class com.logan.ktoast.config.KToastConfig { *; }

# 保持扩展函数可见
-keep class com.logan.ktoast.ToastExtensionsKt { *; }

# 如果将来有 Java 用户调用，保持单例
-keep class com.logan.ktoast.KToast { *; }