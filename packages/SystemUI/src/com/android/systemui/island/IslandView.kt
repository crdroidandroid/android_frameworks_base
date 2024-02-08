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

import android.app.ActivityTaskManager
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.TaskStackListener
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.Region
import android.graphics.Typeface
import android.media.AudioManager
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
import android.util.Log
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.android.systemui.R
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

import com.android.settingslib.drawable.CircleFramedDrawable

import kotlin.text.Regex
import java.util.Locale

class IslandView : ExtendedFloatingActionButton {

    private var notificationStackScroller: NotificationStackScrollLayout? = null
    private var headsUpManager: HeadsUpManagerPhone? = null

    private var subtitleColor: Int = Color.parseColor("#66000000")
    private var titleSpannable: SpannableString = SpannableString("")
    private var islandText: SpannableStringBuilder = SpannableStringBuilder()
    private var notifTitle: String = ""
    private var notifContent: String = ""
    private var notifSubContent: String = ""
    private var notifPackage: String = ""
    
    private var useIslandNotification = false
    private var isIslandAnimating = false
    private var isDismissed = true
    private var isTouchInsetsRemoved = true
    private var isExpanded = false
    private var isNowPlaying = false
    private var isPostPoned = false
    private var isStackRegistered = false
    
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
        if (!useIslandNotification || expandedFraction > 0.0f) {
            return
        }
        post({
            notificationStackScroller?.visibility = View.GONE
            setIslandContents(true)
            if (!shouldShowIslandNotification() || this.icon == null && this.text.isBlank()) {
                isPostPoned = true
                return@post
            }
            if (isIslandAnimating) {
                isPostPoned = true
                shrink()
                postOnAnimationDelayed({
                    hide()
                }, 150L)
                return@post
            }
            show()
            isDismissed = false
            isIslandAnimating = true
            postOnAnimationDelayed({
                extend()
                isPostPoned = false
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
                    if (isDismissed && !isIslandAnimating && isTouchInsetsRemoved && !isPostPoned) {
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
        val darkColor = context.getColor(R.color.island_background_color_dark)
        val lightColor = context.getColor(R.color.island_background_color_light)
        this.backgroundTintList = ColorStateList.valueOf(if (dark) darkColor else lightColor)
        setTextColor(ColorStateList.valueOf(if (dark) lightColor else darkColor))
        subtitleColor = Color.parseColor(if (dark) "#89ffffff" else "#66000000")
    }

    private fun prepareIslandContent() {
        val sbn = headsUpManager?.topEntry?.row?.entry?.sbn ?: return
        val notification = sbn.notification
        val extraTitle = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val extraText = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val (islandTitle, islandText) = prepareNotificationContent(extraTitle, extraText)
        val iconDrawable = sequenceOf(
            Notification.EXTRA_LARGE_ICON_BIG,
            Notification.EXTRA_LARGE_ICON,
            Notification.EXTRA_SMALL_ICON
        ).map { key -> getDrawableFromExtras(notification.extras, key, context) }
         .firstOrNull { it != null }
         ?: getNotificationIcon(sbn, notification) ?: return
        val appLabel = getAppLabel(getActiveAppVolumePackage(), context)
        isNowPlaying = sbn?.packageName == "com.android.systemui" && 
                       islandTitle.toLowerCase(Locale.ENGLISH).equals(
                           context.getString(R.string.now_playing_on, appLabel).toLowerCase(Locale.ENGLISH)
                       )
        val isSystem = sbn?.packageName == "android" || sbn?.packageName == "com.android.systemui"
        notifTitle = when {
            isNowPlaying -> 
                { islandText.takeIf { it.isNotBlank() } ?: return } // island now playing 
            isSystem && !isNowPlaying -> { "" } // USB debugging notification etc
            else -> {
                islandTitle.takeIf { it.isNotBlank() } ?: return // normal apps
            }
        }
        notifContent = when {
            isNowPlaying -> { "" }
            else -> { islandText.takeIf { it.isNotBlank() } ?: "" } // normal apps
        }
        notifSubContent = notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        titleSpannable = SpannableString(notifTitle.ifEmpty { notifContent }).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val resources = context.resources
        val bitmap = drawableToBitmap(iconDrawable)
        val roundedIcon = CircleFramedDrawable(bitmap, this.iconSize)
        this.icon = roundedIcon
        this.iconTint = null
        this.bringToFront()
        if (isNowPlaying) {
            notifPackage = getActiveAppVolumePackage()
            notifSubContent = ""
        } else {
            notifPackage = sbn.packageName
        }
        setOnTouchListener(sbn.notification.contentIntent, notifPackage)
    }

    fun prepareNotificationContent(title: String, content: String): Pair<String, String> {
        val splitter = "|||"
        val contentText = "$title$splitter$content"
        val notificationContent = filterNotifContent(contentText)
        val (notifTitle, notifContent) = notificationContent.split(splitter, limit = 2)
        return Pair(notifTitle.trim(), notifContent.trim())
    }

    fun filterNotifContent(text: String): String {
        val splitter = "|||"
        val (title, content) = text.split(splitter, limit = 2)
        var notifTitle = title.removeSuffix(":").trim()
        notifTitle = notifTitle.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString() 
        }
        val filteredContent = content.toLowerCase().replace(notifTitle.toLowerCase(), "").trim().replaceFirst(notifTitle, notifTitle, ignoreCase = true)
        return "$notifTitle$splitter$filteredContent"
    }

    fun getAppLabel(packageName: String, context: Context): String {
        val packageManager = context.packageManager
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    fun getActiveAppVolumePackage(): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (av in audioManager.listAppVolumes()) {
            if (av.isActive) {
                return av.getPackageName()
            }
        }
        return ""
    }

    fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }
        val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
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
            val pkgname = sbn?.packageName
            if ("com.android.systemui" == pkgname) {
                context.getDrawable(notification.icon)
            } else {
                context.packageManager.getApplicationIcon(pkgname)
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

    private fun onSingleTap(pendingIntent: PendingIntent?, packageName: String) {
        if (isDeviceRinging()) {
            telecomManager?.acceptRingingCall()
        } else {
            val appIntent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

            if (pendingIntent != null) {
                try {
                    val options = ActivityOptions.makeBasic()
                    options.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    pendingIntent.send(context, 0, appIntent, null, null, null, options.toBundle())
                } catch (e: Exception) {
                    appIntent?.let { context.startActivityAsUser(it, UserHandle.CURRENT) }
                }
            } else {
                appIntent?.let { context.startActivityAsUser(it, UserHandle.CURRENT) }
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
        this.iconSize = if (singleLine) resources.getDimensionPixelSize(R.dimen.island_icon_size) / 2 else resources.getDimensionPixelSize(R.dimen.island_icon_size)
        prepareIslandContent()
        this.apply {
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

    private fun shouldShowIslandNotification(): Boolean {
        var shouldShowNotification = !isCurrentNotifActivityOnTop(notifPackage) or !isCurrentNotifActivityOnTop(getActiveAppVolumePackage())
        val taskStackListener = object : TaskStackListener() {
            override fun onTaskStackChanged() {
                shouldShowNotification = !isCurrentNotifActivityOnTop(notifPackage) or !isCurrentNotifActivityOnTop(getActiveAppVolumePackage())
            }
        }
        if (!isStackRegistered) {
            try {
                ActivityTaskManager.getService().registerTaskStackListener(taskStackListener)
                isStackRegistered = true
            } catch (e: Exception) {
                isStackRegistered = false
            }
        }
        if (shouldShowNotification && isStackRegistered) {
            try {
                ActivityTaskManager.getService().unregisterTaskStackListener(taskStackListener)
                isStackRegistered = false
            } catch (e: Exception) {
                isStackRegistered = true
            }
        }
        return shouldShowNotification
    }

    fun isCurrentNotifActivityOnTop(packageName: String): Boolean {
        try {
            val focusedTaskInfo = ActivityTaskManager.getService().getFocusedRootTaskInfo()
            val topActivityPackageName = focusedTaskInfo?.topActivity?.packageName
            return topActivityPackageName == packageName
        } catch (e: Exception) {}
        return false
    }
}
