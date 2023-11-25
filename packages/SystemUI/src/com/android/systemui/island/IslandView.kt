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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.Region
import android.graphics.Typeface
import android.os.UserHandle
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.android.systemui.R
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

import kotlin.text.Regex

class IslandView : ExtendedFloatingActionButton {

    private var useIslandNotification: Boolean = false
    private var isIslandAnimating: Boolean = false
    private var isDismissed: Boolean = true
    private var isTouchInsetsRemoved: Boolean = true
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

    constructor(context: Context) : super(context) {
        this.visibility = View.GONE
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        this.visibility = View.GONE
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        this.visibility = View.GONE
    }

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
        resetLayout()
        shrink()
        postOnAnimationDelayed({
            hide()
            isIslandAnimating = false
            isDismissed = true
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
        var notifTitle = ""
        var extraTitle = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        if (extraTitle.isBlank()) return
        val allPhrases = linkedSetOf<String>()
        val extraTitlePhrases = extraTitle.split(Regex("\\s+")).filterNot { it.isBlank() }
        val filteredTitleParts = extraTitlePhrases.mapNotNull { phrase ->
            val alphanumericPhrase = phrase.replace(Regex("[^A-Za-z0-9]"), "")
            if (allPhrases.contains(alphanumericPhrase.toLowerCase())) {
                null
            } else {
                allPhrases.add(alphanumericPhrase.toLowerCase())
                phrase
            }
        }
        val filteredTitle = filteredTitleParts.joinToString(" ") { it }
            .replace(Regex("(:)\\s+"), "$1 ")
            .replace(Regex("\\s+(:)"), " $1")
            .replace(Regex("\\s+(\\n)"), "$1")
            .trim()
        notifTitle = filteredTitle
        if (notifTitle.isBlank()) return
        val notifContent = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val notifSubContent = notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val islandIntent = notification.contentIntent ?: notification.fullScreenIntent
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
        this.iconTint = null
        this.text = notifText
        this.isSelected = true
        this.bringToFront()
        animateIslandText()
        setOnClickListener(sbn.packageName)
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

    private fun setOnClickListener(packageName: String) {
        this.isClickable = true
        this.isFocusable = true
        this.isFocusableInTouchMode = true
        this.setOnClickListener {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (telecomManager.isRinging) {
                telecomManager.acceptRingingCall()
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                try {
                    context.startActivityAsUser(intent, UserHandle.CURRENT)
                } catch (e: Exception) {}
            }
            hide()
        }

        this.setOnLongClickListener {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (telecomManager.isRinging) {
                telecomManager.endCall()
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                hide()
                true
            } else {
                expandIslandView()
                true
            }
        }
    }
    
    fun resetLayout() {
        val params = this.layoutParams as ViewGroup.MarginLayoutParams
        params.width = ViewGroup.LayoutParams.WRAP_CONTENT
        val margin = 0
        params.setMargins(margin, params.topMargin, margin, params.bottomMargin)
        this.layoutParams = params
    }
    
    fun animateIslandText() {
        this.iconSize = resources.getDimensionPixelSize(R.dimen.island_icon_size) / 2
        this.isSingleLine = true
        this.maxLines = 1
        this.ellipsize = TextUtils.TruncateAt.MARQUEE
        this.marqueeRepeatLimit = -1
        this.setHorizontallyScrolling(true)
    }

    fun expandIslandView() {
        TransitionManager.beginDelayedTransition(parent as ViewGroup, AutoTransition())
        this.iconSize = resources.getDimensionPixelSize(R.dimen.island_icon_size)
        this.isSingleLine = false
        this.maxLines = 4
        this.ellipsize = TextUtils.TruncateAt.END
        this.setHorizontallyScrolling(false)
        val params = this.layoutParams as ViewGroup.MarginLayoutParams
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        val margin = resources.getDimensionPixelSize(R.dimen.island_side_margin)
        params.setMargins(margin, params.topMargin, margin, params.bottomMargin)
        this.layoutParams = params
    }
}
