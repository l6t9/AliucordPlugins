package com.github.lampdelivery

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.SettingsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.settings.delegate
import com.aliucord.widgets.BottomSheet
import com.discord.views.CheckedSetting
import java.util.WeakHashMap

@AliucordPlugin
class CleanChannels : Plugin() {

    private val guardedViews = WeakHashMap<TextView, Boolean>()
    
    private var hideSymbols by settings.delegate(true)
    private var capitalizeCategories by settings.delegate(true)
    private var removeEmojis by settings.delegate(true)

    private var categoryId = 0
    private var inputId = 0
    private val targetIds = mutableSetOf<Int>()

    init {
        settingsTab = SettingsTab(PluginSettings::class.java, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings)
    }

    override fun start(context: Context) {
        categoryId = getResId("channels_item_category_name")
        inputId = getResId("text_input")
        
        val names = listOf(
            "channels_item_channel_name",
            "channels_item_voice_channel_name",
            "channels_list_item_private_name",
            "stage_channel_item_voice_channel_name",
            "channels_item_thread_name",
            "channels_item_category_name",
            "toolbar_title",
            "member_list_item_group_name",
            "member_list_group_name",
            "channel_members_list_item_header_name",
            "member_list_header_text",
            "member_list_channel_name",
            "guild_channel_side_bar_header_title",
            "chat_side_panel_header_title",
            "widget_chat_side_panel_header_title",
            "channel_topic_name",
            "channel_topic_title",
            "channels_list_item_text_actions_title",
            "name",
            "title",
            "text_input"
        )
        names.forEach { name ->
            val id = getResId(name)
            if (id != 0) targetIds.add(id)
        }

        try {
            val appFragmentClass = Class.forName("com.discord.app.AppFragment")
            patcher.patch(appFragmentClass.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java), Hook { cf ->
                val root = cf.args?.getOrNull(0) as? View ?: return@Hook
                root.post { walkAndClean(root) }
            })
        } catch (_: Throwable) {}

        try {
            val appBottomSheetClass = Class.forName("com.discord.app.AppBottomSheet")
            patcher.patch(appBottomSheetClass.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java), Hook { cf ->
                val root = cf.args?.getOrNull(0) as? View ?: return@Hook
                root.post { walkAndClean(root) }
            })
        } catch (_: Throwable) {}

        try {
            val setTextHook = Hook { param ->
                val tv = param.thisObject as? TextView ?: return@Hook
                if (targetIds.contains(tv.id) || isHeader(tv)) {
                    applyCleaningSafely(tv)
                    attachGuard(tv)
                }
            }
            TextView::class.java.declaredMethods.filter { it.name == "setText" }.forEach { method ->
                if (method.parameterTypes.isNotEmpty() && CharSequence::class.java.isAssignableFrom(method.parameterTypes[0])) {
                    patcher.patch(method, setTextHook)
                }
            }
        } catch (_: Throwable) {}

        val headerClasses = listOf(
            "com.discord.widgets.home.WidgetHome",
            "com.discord.widgets.chat.sidepanel.WidgetChatSidePanel",
            "com.discord.widgets.channels.WidgetChannelTopic",
            "com.discord.app.AppActivity",
            "com.discord.widgets.channels.list.WidgetChannelsListItemChannelActions"
        )
        headerClasses.forEach { className ->
            try {
                val clazz = Class.forName(className)
                clazz.declaredMethods.forEach { method ->
                    if (method.name == "setActionBarTitle" && method.parameterTypes.size == 1 && method.parameterTypes[0] == CharSequence::class.java) {
                        patcher.patch(method, Hook { cf ->
                            val title = cf.args[0] as? CharSequence ?: return@Hook
                            cf.args[0] = cleanName(title, isCategory = false, isHint = false, isHeader = true)
                        })
                    } else if (method.name == "configure" || method.name == "configureUI" || method.name == "updateUI") {
                        patcher.patch(method, Hook { cf ->
                            val obj = cf.thisObject ?: return@Hook
                            if (obj is androidx.fragment.app.Fragment) {
                                obj.view?.let { root -> root.post { walkAndClean(root) } }
                            } else {
                                findAndCleanView(obj)
                            }
                        })
                    }
                }
            } catch (_: Throwable) {}
        }

        try {
            val widgetChatInputClass = Class.forName("com.discord.widgets.chat.input.WidgetChatInput")
            val getHintMethods = widgetChatInputClass.declaredMethods.filter { it.name == "getHint" }
            getHintMethods.forEach { method ->
                patcher.patch(method, Hook { param ->
                    val res = param.result as? CharSequence ?: return@Hook
                    param.result = cleanName(res, isCategory = false, isHint = true, isHeader = false)
                })
            }
        } catch (_: Throwable) {}
    }

    private fun getResId(name: String): Int {
        val id = Utils.getResId(name, "id")
        if (id != 0) return id
        val ctx = Utils.appContext ?: return 0
        var fallbackId = ctx.resources.getIdentifier(name, "id", "com.discord")
        if (fallbackId == 0) fallbackId = ctx.resources.getIdentifier(name, "id", "com.discord.app")
        return fallbackId
    }

    private fun findAndCleanView(obj: Any) {
        try {
            obj.javaClass.declaredFields.forEach { f ->
                if (View::class.java.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    (f.get(obj) as? View)?.let { root -> root.post { walkAndClean(root) } }
                } else if (f.name == "binding") {
                    f.isAccessible = true
                    val binding = f.get(obj)
                    binding?.javaClass?.getDeclaredMethod("getRoot")?.let { getRoot ->
                        (getRoot.invoke(binding) as? View)?.let { root -> root.post { walkAndClean(root) } }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun walkAndClean(root: View) {
        if (root is TextView) {
            if (targetIds.contains(root.id) || isHeader(root)) {
                applyCleaningSafely(root)
                attachGuard(root)
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) walkAndClean(root.getChildAt(i))
        }
    }

    private fun isHeader(tv: TextView): Boolean {
        val id = tv.id
        if (id == 0 || id == View.NO_ID) return isInsideDrawer(tv)
        return try {
            val res = tv.context?.resources ?: tv.resources ?: return false
            val idName = res.getResourceEntryName(id) ?: ""
            idName.contains("toolbar") || idName.contains("header") || idName.contains("topic") || 
            idName.contains("side_bar") || idName.contains("side_panel") || idName.contains("channel_name") ||
            idName.contains("actions_title") || idName == "name" || idName == "title"
        } catch (_: Throwable) { isInsideDrawer(tv) }
    }

    private fun isInsideDrawer(v: View): Boolean {
        var parent = v.parent
        while (parent != null && parent is View) {
            val id = parent.id
            if (id != 0 && id != View.NO_ID) {
                val idName = try { parent.resources.getResourceEntryName(id) } catch (_: Throwable) { "" }
                if (idName.contains("drawer") || idName.contains("panel") || idName.contains("side_bar")) return true
            }
            parent = parent.parent
        }
        return false
    }

    private fun applyCleaningSafely(tv: TextView) {
        val id = tv.id
        val isHint = id == inputId
        val original = (if (isHint) tv.hint else tv.text) ?: return
        if (original.isEmpty()) return
        
        val cleaned = cleanName(original, id == categoryId, isHint, isHeader(tv))
        if (cleaned != original.toString()) {
            if (isHint) tv.hint = cleaned else tv.text = cleaned
        }
    }

    private fun attachGuard(tv: TextView) {
        if (guardedViews.containsKey(tv)) return
        guardedViews[tv] = true

        tv.addTextChangedListener(object : TextWatcher {
            private var isRunning = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isRunning) return
                isRunning = true
                applyCleaningSafely(tv)
                isRunning = false
            }
        })
    }

    private fun isEmoji(cp: Int): Boolean {
        return (cp in 0x1F300..0x1F9FF) || (cp in 0x2600..0x27BF) || (cp in 0x1F000..0x1F0FF) || (cp in 0x1F600..0x1F64F) || (cp in 0x1F680..0x1F6FF) || (cp in 0xFE00..0xFE0F)
    }

    private fun isDash(cp: Int): Boolean {
        if (cp == '-'.code || cp == 0x2D || cp == 45) return true
        val type = Character.getType(cp).toByte()
        return type == Character.DASH_PUNCTUATION || cp == '\u2212'.code || 
               cp == 0x2010 || cp == 0x2011 || cp == 0x2012 || cp == 0x2013 || cp == 0x2014 || cp == 0x2015
    }

    private fun cleanName(name: CharSequence, isCategory: Boolean, isHint: Boolean, isHeader: Boolean): String {
        val str = name.toString()
        val cleaned = StringBuilder()
        
        var i = 0
        while (i < str.length) {
            val cp = str.codePointAt(i)
            val charCount = Character.charCount(cp)
            
            val dash = isDash(cp)
            val emoji = isEmoji(cp)
            
            val keep = when {
                dash -> true
                emoji -> !removeEmojis
                hideSymbols -> {
                    val isAlphaNumeric = (cp in 'a'.code..'z'.code) || (cp in 'A'.code..'Z'.code) || (cp in '0'.code..'9'.code)
                    if (isHint || isHeader) {
                        isAlphaNumeric || cp == ' '.code || cp == '#'.code || cp == '@'.code || dash
                    } else if (isCategory) {
                        isAlphaNumeric || cp == ' '.code || dash
                    } else {
                        isAlphaNumeric || dash
                    }
                }
                else -> {
                    Character.isLetterOrDigit(cp) || cp == ' '.code || cp == '_'.code || 
                    cp == '#'.code || cp == '.'.code || cp == '@'.code || dash
                }
            }
            
            if (keep) cleaned.append(str.substring(i, i + charCount))
            i += charCount
        }
        
        var result = cleaned.toString().replace(Regex("\\s+"), " ")
        result = result.trim { it == ' ' }

        if (isCategory && capitalizeCategories && result.isNotEmpty()) {
            result = result.lowercase().replaceFirstChar { it.uppercase() }
        }
        
        return result
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    class PluginSettings(private val settings: SettingsAPI) : BottomSheet() {
        override fun onViewCreated(view: View, bundle: Bundle?) {
            super.onViewCreated(view, bundle)
            val context = view.context

            addView(Utils.createCheckedSetting(context, CheckedSetting.ViewType.SWITCH, "Remove emojis", "Toggle removal of emojis from channel names.").apply {
                isChecked = settings.getBool("removeEmojis", true)
                setOnCheckedListener { settings.setBool("removeEmojis", it) }
            })

            addView(Utils.createCheckedSetting(context, CheckedSetting.ViewType.SWITCH, "Hide symbols", "Restricts channel names to letters, digits and - only.").apply {
                isChecked = settings.getBool("hideSymbols", true)
                setOnCheckedListener { settings.setBool("hideSymbols", it) }
            })

            addView(Utils.createCheckedSetting(context, CheckedSetting.ViewType.SWITCH, "Capitalize categories", "Uniformly capitalize categories (e.g. Category).").apply {
                isChecked = settings.getBool("capitalizeCategories", true)
                setOnCheckedListener { settings.setBool("capitalizeCategories", it) }
            })
        }
    }
}
