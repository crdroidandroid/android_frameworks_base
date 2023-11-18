/*
 * Copyright (C) 2023 the risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.island

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.Region
import android.graphics.Typeface
import android.service.notification.StatusBarNotification
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import com.android.systemui.R
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class IslandView : ExtendedFloatingActionButton {

    private var useIslandNotification: Boolean = false
    private var isIslandAnimating: Boolean = false
    private var isDismissed: Boolean = true
    private var isTouchInsetsRemoved: Boolean = true
    private var islandText: String = "";
    private var notificationStackScroller: NotificationStackScrollLayout? = null
    private var headsUpManager: HeadsUpManagerPhone? = null
    private var subtitleColor = Color.parseColor("#66000000")

    private val insetsListener = ViewTreeObserver.OnComputeInternalInsetsListener { internalInsetsInfo ->
        internalInsetsInfo.touchableRegion.setEmpty()
        internalInsetsInfo.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
        val mainLocation = IntArray(2)
        getLocationOnScreen(mainLocation)
        internalInsetsInfo.touchableRegion.set(Region(
            mainLocation[0],
            mainLocation[1],
            mainLocation[0] + width,
            mainLocation[1] + height
        ))
    }

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    fun setIslandEnabled(enable: Boolean) {
        this.useIslandNotification = enable
    }
    
    fun setScroller(scroller: NotificationStackScrollLayout?) {
        this.notificationStackScroller = scroller
    }
    
    fun setHeadsupManager(headsUp: HeadsUpManagerPhone?) {
        this.headsUpManager = headsUp
    }

    fun showIsland(show: Boolean, expandedFraction: Float) {
        if (show) { 
            animateShowIsland(expandedFraction)
        } else {
            animateDismissIsland()
        }
    }

    fun animateShowIsland(expandedFraction: Float) {
        if (!useIslandNotification || isIslandAnimating || expandedFraction > 0.0f) {
            return
        }
        prepareIslandContent()
        notificationStackScroller?.visibility = View.GONE
        show()
        postOnAnimationDelayed({
            extend()
            postOnAnimationDelayed({
                isDismissed = false
                isIslandAnimating = true
                if (isTouchInsetsRemoved) {
                    viewTreeObserver.addOnComputeInternalInsetsListener(insetsListener)
                    isTouchInsetsRemoved = false
                }
            }, 150L)
        }, 150L)
    }

    fun animateDismissIsland() {
        shrink()
        postOnAnimationDelayed({
            hide()
            isIslandAnimating = false
            isDismissed = true
            this.isSelected = false
            if (!isTouchInsetsRemoved) {
                viewTreeObserver.removeOnComputeInternalInsetsListener(insetsListener)
                isTouchInsetsRemoved = true
            }
            postOnAnimationDelayed({
                if (isDismissed && !isIslandAnimating && isTouchInsetsRemoved) {
                    notificationStackScroller?.visibility = View.VISIBLE
                }
            }, 500L)
        }, 150L)
    }

    fun updateIslandVisibility(expandedFraction: Float) {
        if (expandedFraction > 0.0f && !isTouchInsetsRemoved) {
            notificationStackScroller?.visibility = View.VISIBLE
            visibility = View.GONE
            viewTreeObserver.removeOnComputeInternalInsetsListener(insetsListener)
            isTouchInsetsRemoved = true
        } else if (useIslandNotification && isIslandAnimating && expandedFraction == 0.0f && isTouchInsetsRemoved) {
            notificationStackScroller?.visibility = View.GONE
            visibility = View.VISIBLE
            viewTreeObserver.addOnComputeInternalInsetsListener(insetsListener)
            isTouchInsetsRemoved = false
        }
    }

    fun setIslandBackgroundColorTint(dark: Boolean) {
        this.backgroundTintList = if (dark) {
            ColorStateList.valueOf(context.getColor(R.color.island_background_color_dark))
        } else {
            ColorStateList.valueOf(context.getColor(R.color.island_background_color_light))
        }
        val textColor = if (dark) {
            ColorStateList.valueOf(context.getColor(R.color.island_background_color_light))
        } else {
            ColorStateList.valueOf(context.getColor(R.color.island_background_color_dark))
        }
        setTextColor(textColor)
        subtitleColor = if (dark) {
            Color.parseColor("#89ffffff")
        } else {
            Color.parseColor("#66000000")
        }
    }

    private fun prepareIslandContent() {
        val sbn = headsUpManager?.topEntry?.row?.entry?.sbn ?: return
        val notification = sbn.notification
        val icon = getNotificationIcon(sbn, notification) ?: return
        val notifTitle = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        if (notifTitle.isEmpty()) return
        val notifContent = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val notifSubContent = notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val islandIntent = notification.contentIntent ?: notification.fullScreenIntent
        setOnClickListenerForIsland(islandIntent)
        val titleSpannable = SpannableString(notifTitle).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, notifTitle.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val notifText = SpannableStringBuilder().apply {
            append(titleSpannable)
            if (notifContent.isNotEmpty()) {
                val contentSpannable = SpannableString(notifContent).apply {
                    setSpan(ForegroundColorSpan(subtitleColor), 0, notifContent.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(RelativeSizeSpan(0.9f), 0, notifContent.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                append("\n")
                append(contentSpannable)
            }
            if (notifSubContent.isNotEmpty()) {
                val subContentSpannable = SpannableString(notifSubContent).apply {
                    setSpan(ForegroundColorSpan(subtitleColor), 0, notifSubContent.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(RelativeSizeSpan(0.85f), 0, notifSubContent.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                append("\n")
                append(subContentSpannable)
            }
        }
        this.icon = icon
        this.iconSize = resources.getDimensionPixelSize(R.dimen.island_icon_size)
        this.iconTint = null
        this.isSingleLine = false
        this.maxLines = 4
        this.ellipsize = android.text.TextUtils.TruncateAt.END
        this.text = notifText
    }

    private fun getNotificationIcon(sbn: StatusBarNotification, notification: Notification): Drawable? {
        return try {
            val pkgname = sbn.packageName
            if ("com.android.systemui" == pkgname) {
                context.getDrawable(notification.icon)
            } else {
                context.packageManager.getApplicationIcon(pkgname)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun setOnClickListenerForIsland(islandIntent: PendingIntent?) {
        islandIntent?.let {
            this.setOnClickListener {
                try {
                    val options = ActivityOptions.makeBasic()
                    options.setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    islandIntent.send(options.toBundle());
               } catch (e: PendingIntent.CanceledException) {}
            }
        }
    }
}
