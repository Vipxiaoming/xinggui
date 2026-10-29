package vip.wangxiaoming.music.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import vip.wangxiaoming.music.R
import vip.wangxiaoming.music.data.model.UserPrefs
import vip.wangxiaoming.music.databinding.FragmentSettingsBinding
import vip.wangxiaoming.music.di.AppContainer
import vip.wangxiaoming.music.ui.common.HostActions
import vip.wangxiaoming.music.ui.common.UiKit

/**
 * 设置页。
 *
 * 与原版 view_settings_page 的差异：下载路径项已裁剪（MediaStore 方案不允许自定义目录），
 * 但该 item 在 XML 里仍在，这里显式 GONE 并说明原因，避免出现「点了没反应」的死项。
 *
 * 四个可写项全部落到 PrefsRepository（DataStore），UI 只读 Flow，不自存状态。
 */
class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    private val host: HostActions get() = requireActivity() as HostActions
    private val container: AppContainer get() = AppContainer.get()

    private var bindingSwitch = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val page = b.settingsPageContent

        page.btnBackFromSettings.visibility = View.VISIBLE
        page.btnBackFromSettings.setOnClickListener { host.closeSecondary() }

        // 下载路径：MediaStore 方案下由系统决定，不支持自定义，直接隐藏并保留 XML 以便回退
        page.itemSettingDownloadPath.visibility = View.GONE

        page.itemSettingQuality.setOnClickListener { pickQuality() }
        page.itemSettingMaxConcurrentDownloads.setOnClickListener { pickConcurrent() }
        page.itemSettingBatchDownloadBtn.setOnClickListener {
            val now = page.switchSettingBatchDownload.isChecked
            viewLifecycleOwner.lifecycleScope.launch {
                container.prefsRepository.setShowBatchDownloadBtn(!now)
            }
        }
        page.itemSettingLocalSongs.setOnClickListener {
            UiKit.toast(requireContext(), "本地文件扫描属 P7（MediaStore 需读权限）")
        }

        page.switchSettingBatchDownload.setOnCheckedChangeListener { _, checked ->
            if (bindingSwitch) return@setOnCheckedChangeListener
            viewLifecycleOwner.lifecycleScope.launch {
                container.prefsRepository.setShowBatchDownloadBtn(checked)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            container.prefsRepository.userPrefs.collectLatest { prefs -> render(prefs) }
        }
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    private fun render(prefs: UserPrefs) {
        val page = _b?.settingsPageContent ?: return
        page.tvSettingQualitySub.text = when (prefs.quality) {
            "128k" -> "标准音质 (128Kbps)"
            "flac" -> "无损音质 (FLAC)"
            else -> "较高音质 (320Kbps)"
        }
        page.tvSettingConcurrentCountSub.text =
            "同时下载 " + prefs.maxConcurrentDownloads + " 首 (可设置 1-10 个)"
        page.tvSettingBatchDownloadSub.text = if (prefs.showBatchDownloadBtn) {
            "已开启：歌单与歌曲列表显示多选下载按钮"
        } else {
            "已关闭：列表不显示多选下载按钮"
        }
        bindingSwitch = true
        page.switchSettingBatchDownload.isChecked = prefs.showBatchDownloadBtn
        bindingSwitch = false
    }

    private fun pickQuality() {
        val labels = arrayOf("标准 128K", "较高 320K", "无损 FLAC")
        val values = arrayOf("128k", "320k", "flac")
        viewLifecycleOwner.lifecycleScope.launch {
            val current = container.prefsRepository.userPrefs.first().quality
            val checked = values.indexOf(current).coerceAtLeast(0)
            AlertDialog.Builder(requireContext())
                .setTitle("音质偏好")
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        container.prefsRepository.setQuality(values[which])
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun pickConcurrent() {
        val values = (1..10).toList()
        val labels = values.map { it.toString() + " 首" }.toTypedArray()
        viewLifecycleOwner.lifecycleScope.launch {
            val current = container.prefsRepository.userPrefs.first()
                .maxConcurrentDownloads
            AlertDialog.Builder(requireContext())
                .setTitle("同时下载数量")
                .setSingleChoiceItems(labels, (current - 1).coerceIn(0, 9)) { dialog, which ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        container.prefsRepository.setMaxConcurrentDownloads(values[which])
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}