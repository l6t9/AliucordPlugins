/*
 * UserDetails integration for ModernProfiles
 * Based on UserDetails plugin by Juby210
 */

package com.github.lampdelivery

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.aliucord.Constants
import com.aliucord.Utils
import com.discord.databinding.UserProfileHeaderViewBinding
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.SnowflakeUtils
import com.discord.widgets.user.profile.UserProfileHeaderView
import com.lytefast.flexinput.R
import java.lang.reflect.Field
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class UserDetailsHelper {
    companion object {
        private val viewId = View.generateViewId()
        private val customStatusViewId = Utils.getResId("user_profile_header_custom_status", "id")

        private val profileHeaderBinding: Field = UserProfileHeaderView::class.java
            .getDeclaredField("binding")
            .apply { isAccessible = true }
        private val UserProfileHeaderView.binding
            get() = profileHeaderBinding[this] as UserProfileHeaderViewBinding?

        private fun toReadable(timestamp: Long, showDaysAgo: Boolean): String {
            val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)
            var readable = dateFormat.format(Date(timestamp))

            if (showDaysAgo) {
                val currentTime = System.currentTimeMillis()
                val days = TimeUnit.DAYS.convert(currentTime - timestamp, TimeUnit.MILLISECONDS)
                readable += when (days) {
                    0L -> " (Today)"
                    1L -> " (Yesterday)"
                    else -> " ($days days ago)"
                }
            }
            return readable
        }

        private fun findUserProfileHeaderView(root: View): UserProfileHeaderView? {
            fun searchForProfileHeaderView(v: View): UserProfileHeaderView? {
                if (v is UserProfileHeaderView) {
                    return v
                }
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) {
                        val found = searchForProfileHeaderView(v.getChildAt(i))
                        if (found != null) return found
                    }
                }
                return null
            }

            return searchForProfileHeaderView(root)
        }

        fun addMemberDetails(
            root: View,
            user: User,
            showCreatedAt: Boolean,
            showJoinedAt: Boolean,
            showDaysAgo: Boolean
        ) {
            try {
                val view = findUserProfileHeaderView(root) ?: return
                val binding = view.binding ?: return

                val customStatus = binding.a.findViewById<View>(customStatusViewId) ?: return
                val layout = customStatus.parent as LinearLayout
                val context = layout.context

                val detailsView = layout.findViewById(viewId) ?: TextView(
                    context,
                    null,
                    0,
                    R.i.UiKit_TextView_Semibold
                ).apply {
                    id = viewId
                    typeface = ResourcesCompat.getFont(context, Constants.Fonts.whitney_semibold)

                    try {
                        fun findAboutText(v: View): TextView? {
                            if (v is ViewGroup) {
                                for (i in 0 until v.childCount) {
                                    val c = v.getChildAt(i)
                                    try {
                                        if (c.id != View.NO_ID) {
                                            val name = c.context.resources.getResourceEntryName(c.id)
                                            if (name.contains("about_me_text") ||
                                                name.contains("aboutMeText")
                                            ) {
                                                return c as? TextView
                                            }
                                        }
                                    } catch (_: Throwable) {
                                    }

                                    if (c is TextView) {
                                        val txt = c.text?.toString()?.trim() ?: ""
                                        if (txt.isNotEmpty() && txt.length > 16) return c
                                    }

                                    val found = findAboutText(c)
                                    if (found != null) return found
                                }
                            }
                            return null
                        }

                        val aboutTextView = findAboutText(root)
                        if (aboutTextView != null) {
                            setTextColor(aboutTextView.currentTextColor)
                        } else {
                            setTextColor(0xFF72767D.toInt())
                        }
                    } catch (_: Throwable) {
                        setTextColor(0xFF72767D.toInt())
                    }

                    layout.addView(this)
                }

                val userId = user.id
                val guildId = StoreStream.getGuildSelected().selectedGuildId
                val isDM = guildId == 0L
                val text = StringBuilder()

                if (showCreatedAt) {
                    text.append("Created at: ").append(
                        toReadable(
                            SnowflakeUtils.toTimestamp(userId),
                            showDaysAgo
                        )
                    )
                }

                if (showJoinedAt && !isDM) {
                    try {
                        val guildMember = StoreStream.getGuilds().getMember(guildId, userId)
                        if (guildMember != null) {
                            try {
                                val joinedAtField = guildMember.javaClass.getDeclaredField("joinedAt")
                                joinedAtField.isAccessible = true
                                val joinedAtObj = joinedAtField.get(guildMember)

                                if (joinedAtObj != null) {
                                    var joinedTimestamp: Long? = null

                                    try {
                                        val timestampMethod = joinedAtObj.javaClass.getMethod("g")
                                        joinedTimestamp = (timestampMethod.invoke(joinedAtObj) as? Number)?.toLong()
                                    } catch (_: Throwable) {
                                        try {
                                            joinedTimestamp = (joinedAtObj as? Number)?.toLong()
                                        } catch (_: Throwable) {
                                        }
                                    }

                                    if (joinedTimestamp != null && joinedTimestamp > 0) {
                                        if (text.isNotEmpty()) text.append("\n")
                                        text.append("Joined at: ").append(
                                            toReadable(
                                                joinedTimestamp,
                                                showDaysAgo
                                            )
                                        )
                                    } else {
                                        if (text.isNotEmpty()) text.append("\n")
                                        text.append("Joined at: -")
                                    }
                                } else {
                                    if (text.isNotEmpty()) text.append("\n")
                                    text.append("Joined at: -")
                                }
                            } catch (_: Throwable) {
                                if (text.isNotEmpty()) text.append("\n")
                                text.append("Joined at: -")
                            }
                        } else {
                            if (text.isNotEmpty()) text.append("\n")
                            text.append("Joined at: -")
                        }
                    } catch (_: Throwable) {
                        if (text.isNotEmpty()) text.append("\n")
                        text.append("Joined at: -")
                    }
                }

                detailsView.text = text
            } catch (_: Throwable) {
            }
        }
    }
}
