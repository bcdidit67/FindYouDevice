package com.mimo.findyoudevice

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.mimo.findyoudevice.databinding.ActivityOnboardingBinding
import com.mimo.findyoudevice.databinding.ItemOnboardingPageBinding

/**
 * OOBE 首启引导页（ViewPager2 分页 + 指示点 + 跳过/下一步）。
 *
 * 首次安装且引导未完成时由 MainActivity 调起；翻完最后一页或点击"跳过"
 * 均写入完成标记（Prefs.markOobeDone），随后：
 *  - 老用户（已有 mode 键，如升级安装）→ 直接进 MainActivity；
 *  - 新用户 → 进入 ModeSelectionActivity 选择主机/客户端模式。
 * 本页不申请任何运行时权限，仅做产品功能讲解。
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private data class Page(val iconRes: Int, val title: String, val desc: String)

    /** 页面数据（lazy：延迟到 attach 之后才能安全取字符串资源） */
    private val pages: List<Page> by lazy {
        listOf(
            Page(
                R.drawable.ic_search,
                getString(R.string.oobe_p1_title),
                getString(R.string.oobe_p1_desc)
            ),
            Page(
                R.drawable.ic_desktop,
                getString(R.string.oobe_p2_title),
                getString(R.string.oobe_p2_desc)
            ),
            Page(
                R.drawable.ic_bell,
                getString(R.string.oobe_p3_title),
                getString(R.string.oobe_p3_desc)
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.vpOnboarding.adapter = PagerAdapter()
        binding.vpOnboarding.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                renderDots(position)
                binding.btnNext.setText(
                    if (position == pages.lastIndex) R.string.oobe_start else R.string.oobe_next
                )
            }
        })

        binding.btnSkip.setOnClickListener { finishOobe() }
        binding.btnNext.setOnClickListener {
            val cur = binding.vpOnboarding.currentItem
            if (cur < pages.lastIndex) {
                binding.vpOnboarding.setCurrentItem(cur + 1, true)
            } else {
                finishOobe()
            }
        }

        renderDots(0)
    }

    /** 重绘指示点：当前页主色大点（dot_active），其余灰色小点 */
    private fun renderDots(current: Int) {
        binding.dotsContainer.removeAllViews()
        pages.indices.forEach { i ->
            val dot = ImageView(this)
            dot.setImageResource(if (i == current) R.drawable.dot_active else R.drawable.dot_inactive)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (i > 0) lp.marginStart = dp(6)
            binding.dotsContainer.addView(dot, lp)
        }
    }

    /** 标记 OOBE 完成并按是否已有 mode 分流 */
    private fun finishOobe() {
        Prefs.markOobeDone(this)
        val target = if (getSharedPreferences(MainActivity.SP_NAME, MODE_PRIVATE)
                .contains(MainActivity.KEY_MODE)
        ) {
            Intent(this, MainActivity::class.java)
        } else {
            Intent(this, ModeSelectionActivity::class.java)
        }
        startActivity(target)
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** ViewPager2 分页适配器 */
    private inner class PagerAdapter : RecyclerView.Adapter<PagerAdapter.VH>() {

        inner class VH(val b: ItemOnboardingPageBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemOnboardingPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = pages[position]
            holder.b.pageIcon.setImageResource(p.iconRes)
            holder.b.pageTitle.text = p.title
            holder.b.pageDesc.text = p.desc
        }

        override fun getItemCount(): Int = pages.size
    }
}
