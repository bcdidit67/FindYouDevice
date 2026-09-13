package com.mimo.findyoudevice

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/** 主界面两页适配器：0 = 首页（随模式为主机/客户端），1 = 设置 */
class MainPagerAdapter(private val activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> ClientFragment() // 二合一：主页包含客户端 + 本机主机能力
        1 -> SettingsTabFragment()
        else -> Fragment()
    }
}
