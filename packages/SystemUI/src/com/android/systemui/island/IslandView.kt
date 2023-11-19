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
import android.content.pm.ApplicationInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.Region
import android.graphics.Typeface
import android.os.AsyncTask
import android.os.Bundle
import android.os.Handler
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
import android.util.IconDrawableFactory
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

import kotlin.text.Regex
import java.util.Locale;

class IslandView : ExtendedFloatingActionButton {

    private var notificationStackScroller: NotificationStackScrollLayout? = null
    private var headsUpManager: HeadsUpManager? = null

    private var subtitleColor: Int = Color.parseColor("#66000000")
    private var titleSpannable: SpannableString = SpannableString("")
    private var islandText: SpannableStringBuilder = SpannableStringBuilder()
    private var notifTitle: String = ""
    private var notifContent: String = ""
    private var notifSubContent: String = ""
    
    private var useIslandNotification = false
    private var isIslandAnimating = false
    private var isDismissed = true
    private var isTouchInsetsRemoved = true
    private var isExpanded = false
    
    private val effectClick: VibrationEffect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
    private val effectTick: VibrationEffect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)

    private var telecomManager: TelecomManager? = null
    private var vibrator: Vibrator? = null

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

    constructor(context: Context) : super(context) { init(context) }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) { init(context) }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { init(context) }

    fun init(context: Context) {
        this.visibility = View.GONE
        telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun setIslandEnabled(enable: Boolean) {
        this.useIslandNotification = enable
    }
    
    fun setScroller(scroller: NotificationStackScrollLayout?) {
        this.notificationStackScroller = scroller
    }
    
    fun setHeadsupManager(headsUp: HeadsUpManager?) {
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
        if (!useIslandNotification || expandedFraction > 0.0f) {
            return
        } else if (isIslandAnimating) {
            notificationStackScroller?.visibility = View.GONE
            return
        }
        post({
            setIslandContents(true)
            notificationStackScroller?.visibility = View.GONE
            show()
            isDismissed = false
            isIslandAnimating = true
            postOnAnimationDelayed({
                extend()
                postOnAnimationDelayed({
                    addInsetsListener()
                }, 150L)
            }, 150L)
        })
    }

    fun animateDismissIsland() {
        post({
            resetLayout()
            shrink()
            postOnAnimationDelayed({
                hide()
                isIslandAnimating = false
                isDismissed = true
                removeInsetsListener()
                postOnAnimationDelayed({
                    if (isDismissed && !isIslandAnimating && isTouchInsetsRemoved) {
                        notificationStackScroller?.visibility = View.VISIBLE
                    }
                }, 500L)
            }, 150L)
        })
    }

    fun updateIslandVisibility(expandedFraction: Float) {
        if (expandedFraction > 0.0f) {
            notificationStackScroller?.visibility = View.VISIBLE
            this.visibility = View.GONE
            removeInsetsListener()
        } else if (useIslandNotification && isIslandAnimating && expandedFraction == 0.0f) {
            notificationStackScroller?.visibility = View.GONE
            this.visibility = View.VISIBLE
            addInsetsListener()
        }
    }

    fun addInsetsListener() {
        if (!isTouchInsetsRemoved) return
        viewTreeObserver.addOnComputeInternalInsetsListener(insetsListener)
        isTouchInsetsRemoved = false
    }
    
    fun removeInsetsListener() {
        if (isTouchInsetsRemoved) return
        viewTreeObserver.removeOnComputeInternalInsetsListener(insetsListener)
        isTouchInsetsRemoved = true
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
        val sbn = headsUpManager?.getTopEntry()?.row?.entry?.sbn ?: return
        val notification = sbn.notification
        val notificationTitle = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val notificationText = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val largeBigIcon = getDrawableFromExtras(notification.extras, Notification.EXTRA_LARGE_ICON_BIG, context)
        val largeIcon = getDrawableFromExtras(notification.extras, Notification.EXTRA_LARGE_ICON, context)
        val smallIcon = getDrawableFromExtras(notification.extras, Notification.EXTRA_SMALL_ICON, context)
        val iconDrawable = largeBigIcon ?: largeIcon ?: smallIcon ?: getNotificationIcon(sbn, notification) ?: return
        if (largeBigIcon != null || largeIcon != null || smallIcon != null) {
            val packageManager = context.packageManager
            notifTitle = getAppLabel(sbn, context)
            if (notifTitle.isBlank()) return
            notifContent = if (notificationTitle.isNotBlank() && notificationText.isNotBlank()) {
                "$notificationTitle : $notificationText"
            } else {
                notificationTitle.ifBlank { notificationText }
            }
        } else {
            val extraTitle = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            if (extraTitle.isBlank()) return
            val allPhrases = linkedSetOf<String>()
            notifTitle = extraTitle.split(Regex("\\s+"))
                .filterNot { it.isBlank() }
                .mapNotNull { phrase ->
                    val alphanumericPhrase = phrase.replace(Regex("[^A-Za-z0-9]"), "").toLowerCase()
                    if (allPhrases.add(alphanumericPhrase)) phrase else null
                }
                .joinToString(" ") { it }
                .replace(Regex("(:)\\s+"), "$1 ")
                .replace(Regex("\\s+(:)"), " $1")
                .replace(Regex("\\s+(\\n)"), "$1")
                .trim()
                .removeSuffix(":")
            if (notifTitle.isBlank()) return
            notifContent = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        }
        notifSubContent = notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        titleSpannable = SpannableString(notifTitle).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, notifTitle.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        this.icon = iconDrawable
        this.iconTint = null
        this.bringToFront()
        setOnTouchListener(sbn.notification.contentIntent, sbn.packageName)
    }

    fun getApplicationInfo(sbn: StatusBarNotification): ApplicationInfo {
        return context.packageManager.getApplicationInfoAsUser(
                sbn.packageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
                sbn.getUser().getIdentifier())
    }

    fun getAppLabel(sbn: StatusBarNotification, context: Context): String {
        val packageManager = context.packageManager
        return try {
            val appLabel = packageManager.getApplicationLabel(getApplicationInfo(sbn)).toString()
            appLabel.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        } catch (e: PackageManager.NameNotFoundException) {
            sbn.packageName
        }
    }

    private fun getDrawableFromExtras(extras: Bundle, key: String, context: Context): Drawable? {
        val iconObject = extras.get(key) ?: return null
        return when (iconObject) {
            is Bitmap -> BitmapDrawable(context.resources, iconObject)
            is Drawable -> iconObject
            else -> {
                (iconObject as? Icon)?.loadDrawable(context)
            }
        }
    }

    private fun getNotificationIcon(sbn: StatusBarNotification, notification: Notification): Drawable? {
        return try {
            if ("com.android.systemui" == sbn?.packageName) {
                context.getDrawable(notification.icon)
            } else {
                val iconFactory: IconDrawableFactory = IconDrawableFactory.newInstance(context)
                iconFactory.getBadgedIcon(getApplicationInfo(sbn), sbn.getUser().getIdentifier())
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun SpannableStringBuilder.appendSpannable(spanText: String, size: Float, singleLine: Boolean) {
        if (!spanText.isBlank()) {
            val spannableText = SpannableString(spanText).apply {
                setSpan(ForegroundColorSpan(subtitleColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(size), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            append(if (!singleLine) "\n" else " ")
            append(spannableText)
        }
    }

    private fun setOnTouchListener(intent: PendingIntent, packageName: String) {
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap(intent, packageName)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                onLongPress()
            }
        })
        this.setOnTouchListener { view, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun onLongPress() {
        if (isDeviceRinging()) {
            telecomManager?.endCall()
        } else {
            setIslandContents(false)
            isExpanded = true
            postOnAnimationDelayed({
                expandIslandView()
            }, 50)
        }
        AsyncTask.execute { vibrator?.vibrate(effectClick) }
    }

    private fun onSingleTap(pendingIntent: PendingIntent, packageName: String) {
        if (isDeviceRinging()) {
            telecomManager?.acceptRingingCall()
        } else {
            val appIntent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            try {
                val options = ActivityOptions.makeBasic()
                options.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pendingIntent.send(context, 0, appIntent, null, null, null, options.toBundle())
            } catch (e: Exception) {
                try {
                    context.startActivityAsUser(appIntent, UserHandle.CURRENT)
                } catch (e: Exception) {}
            }
        }
        AsyncTask.execute { vibrator?.vibrate(effectTick) }
    }

    private fun isDeviceRinging(): Boolean {
        return telecomManager?.isRinging ?: false
    }

    private fun resetLayout() {
        if (isExpanded) {
            val params = this.layoutParams as ViewGroup.MarginLayoutParams
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT
            val margin = 0
            params.setMargins(margin, params.topMargin, margin, params.bottomMargin)
            this.layoutParams = params
        }
        removeSpans(islandText)
        isExpanded = false
    }

    fun expandIslandView() {
        TransitionManager.beginDelayedTransition(parent as ViewGroup, AutoTransition())
        val params = this.layoutParams as ViewGroup.MarginLayoutParams
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        val margin = resources.getDimensionPixelSize(R.dimen.island_side_margin)
        params.setMargins(margin, params.topMargin, margin, params.bottomMargin)
        this.layoutParams = params
    }

    private fun buildSpannableText(title: SpannableString, content: String, subContent: String, singleLine: Boolean): SpannableStringBuilder {
        return SpannableStringBuilder().apply {
            append(title as CharSequence)
            if (!content.isBlank()) {
                appendSpannable(content, 0.9f, singleLine)
            }
            if (!notifSubContent.isBlank()) {
                appendSpannable(subContent, 0.85f, singleLine)
            }
        }
    }

    private fun setIslandContents(singleLine: Boolean) {
        prepareIslandContent()
        this.apply {
            this.iconSize = if (singleLine) resources.getDimensionPixelSize(R.dimen.island_icon_size) / 2 else resources.getDimensionPixelSize(R.dimen.island_icon_size)
            this.islandText = buildSpannableText(titleSpannable, notifContent, notifSubContent, singleLine)
            if (singleLine) {
                val maxLength = 28
                val singleLineText = if (islandText.length > maxLength) {
                    val spanText = SpannableStringBuilder().append(islandText, 0, maxLength)
                    val ellipsisSpannable = SpannableString("...")
                    ellipsisSpannable.setSpan(ForegroundColorSpan(subtitleColor), 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spanText.append(ellipsisSpannable)
                } else {
                    islandText
                }
                this.text = singleLineText
            } else {
                this.text = islandText
            }
            this.isSingleLine = singleLine
            this.ellipsize = TextUtils.TruncateAt.END
            this.isSelected = singleLine         
        }
    }
        
    private fun removeSpans(builder: SpannableStringBuilder) {
        val spans = builder.getSpans(0, builder.length, Object::class.java)
        for (span in spans) { builder.removeSpan(span) }
        builder.clear()
    }
}
