package com.blazkness.quarkscanextractor;

/**
 * Shizuku UserService 接口。
 *
 * Shizuku 会通过反射实例化实现类，并把它的 Binder 返回给应用进程，
 * 该实现类的代码运行在 root(uid 0) 或 shell(uid 2000) 身份的独立进程中。
 *
 * 注意：destroy() 必须使用 Shizuku 约定的保留事务号 16777114，
 * 否则 Shizuku 无法销毁这个用户服务进程。
 */
interface IUserService {

    void destroy() = 16777114;   // 由 Shizuku 服务端调用
    void exit() = 1;
    String exec(String script) = 2;
    int getUid() = 3;
}
