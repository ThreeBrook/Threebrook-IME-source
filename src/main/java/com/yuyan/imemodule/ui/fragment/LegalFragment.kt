package com.yuyan.imemodule.ui.fragment

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment

/**
 * 显示 assets/legal/ 下的法律文本（隐私政策、软件许可协议、开源许可），
 * 页面由 tools/gen_legal_html.py 从 docs/legal 下的 Markdown 生成。文中的链接交给系统浏览器打开。
 */
class LegalFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val page = requireArguments().getString(ARG_PAGE) ?: PAGE_PRIVACY
        return WebView(requireContext()).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false // 不影响读取 file:///android_asset
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (_: ActivityNotFoundException) { }
                    return true
                }
            }
            loadUrl("file:///android_asset/legal/$page")
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.title = requireArguments().getString(ARG_TITLE)
    }

    companion object {
        const val ARG_PAGE = "page"
        const val ARG_TITLE = "title"
        const val PAGE_PRIVACY = "privacy.html"
        const val PAGE_EULA = "eula.html"
        const val PAGE_LICENSES = "licenses.html"

        fun args(page: String, title: String) = bundleOf(ARG_PAGE to page, ARG_TITLE to title)
    }
}
