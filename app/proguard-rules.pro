# ============================================================================
# R8 / 混淆保留规则（正式版）
# ============================================================================

# Shizuku 用户服务：这个类不是在 manifest 里声明的 Service，而是由 Shizuku 服务端
# 用 app_process 反射实例化（类名写死在 UserServiceArgs 里）。混淆掉类名或构造函数，
# 表现就是「用户服务启动超时」——而且只在 release 包上出现，debug 包完全正常。
-keep class com.blazkness.quarkscanextractor.shizuku.ShizukuUserService {
    public <init>();
    *;
}

# AIDL 生成的接口与 Stub/Proxy：跨进程调用 + 反射查找，事务号也不能改
-keep interface com.blazkness.quarkscanextractor.IUserService { *; }
-keep class com.blazkness.quarkscanextractor.IUserService$Stub { *; }
-keep class com.blazkness.quarkscanextractor.IUserService$Stub$Proxy { *; }

# Shizuku 自己的类（Provider / Binder 包装等）在运行时由框架和它自己的服务端引用，
# 留着它们只多几十 KB，换掉的是「release 包莫名其妙连不上 Shizuku」这类难查问题。
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
