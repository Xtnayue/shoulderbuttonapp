package com.shoulderbutton.mapper.privilege

import rikka.shizuku.Shizuku
import java.lang.reflect.Method

object ShizukuProcessHelper {

    private val newProcessMethod: Method by lazy {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        m
    }

    /**
     * 在 Shizuku 环境下派生远程 Shell 进程
     */
    fun newProcess(cmd: Array<String>, env: Array<String>? = null, dir: String? = null): Process {
        return newProcessMethod.invoke(null, cmd, env, dir) as Process
    }
}
