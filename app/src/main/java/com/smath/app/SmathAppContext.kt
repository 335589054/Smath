package com.smath.app

import android.content.Context

/**
 * 极简的应用级 Context 持有者：ViewModel 没有 Context，但生成期间需要
 * 启动/停止前台服务，所以在 MainActivity 里把 applicationContext 挂进来。
 */
object SmathAppContext {
    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): Context? = appContext
}