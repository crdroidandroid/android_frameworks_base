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
import android.content.pm.ApplicationInfo
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
import android.util.IconDrawableFactory
import android.util.Log
import android.view.animation.AccelerateInterpolator
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.android.systemui.res.R
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

import com.android.settingslib.drawable.CircleFramedDrawable

import kotlin.math.abs
import kotlin.text.Regex
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.Locale

class IslandView : ExtendedFloatingActionButton {

    private var notificationStackScroller: NotificationStackScrollLayout? = null
    private var headsUpManager: HeadsUpManager? = null

    private var subtitleColor: Int = Color.parseColor("#66000000")
    private var titleSpannable: SpannableString = SpannableString("")
    private var islandText: SpannableStringBuilder = SpannableStringBuilder()
    private var notifTitle: String = ""
    private var notifContent: String = ""
    private var notifSubContent: String = ""
    private var notifPackage: String = ""
    private var topActivityPackage: String = ""

    private var isIslandAnimating = false
    private var isDismissed = true
    private var isTouchInsetsRemoved = true
    private var isExpanded = false
    private var isNowPlaying = false
    private var isPostPoned = false
    private var isStackRegistered = false
    private var isLongPress = false

    private val effectClick: VibrationEffect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
    private val effectTick: VibrationEffect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)

    private var telecomManager: TelecomManager? = null
    private var vibrator: Vibrator? = null

    private val bgExecutor: Executor = Executors.newSingleThreadExecutor()

    private val taskStackChangeListener = object : TaskStackChangeListener {
        override fun onTaskStackChanged() {
            try {
                bgExecutor.execute {
                    updateForegroundTaskSync()
                }
            } catch (e: RejectedExecutionException) {}
        }
    }

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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(taskStackChangeListener)
    }
    
    private fun updateForegroundTaskSync() {
        try {
            val focusedStack = ActivityTaskManager.getService().getFocusedRootTaskInfo()
            topActivityPackage = focusedStack?.topActivity?.packageName ?: ""
        } catch (e: Exception) {}
    }

    fun init(context: Context) {
        this.visibility = View.GONE
        telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        TaskStackChangeListeners.getInstance().registerTaskStackListener(taskStackChangeListener)
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
        if (expandedFraction > 0.0f) {
            return
        }
        post({
            notificationStackScroller?.visibility = View.GONE
            setIslandContents(true)
            if (!shouldShowIslandNotification() || this.icon == null && this.text.isBlank()) {
                isPostPoned = true
                return@post
            }
            if (isIslandAnimating && !isDismissed) {
                isPostPoned = true
                shrink()
                postOnAnimationDelayed({
                    hide()
                }, 150L)
                return@post
            }
            show()
            translationX = 0f
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
        if (isDismissed) return
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
            isDismissed = true
            removeInsetsListener()
        } else if (isIslandAnimating && expandedFraction == 0.0f) {
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

    fun setIslandBackgroundColorTint() {
        this.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.island_background_color))
        setTextColor(ColorStateList.valueOf(context.getColor(R.color.island_title_color)))
        subtitleColor = context.getColor(R.color.island_subtitle_color)
    }

    private fun prepareIslandContent() {
        val sbn = headsUpManager?.getTopEntry()?.row?.entry?.sbn ?: return
        val notification = sbn.notification
        val (islandTitle, islandText) = resolveNotificationContent(notification)
        val iconDrawable = sequenceOf(
            Notification.EXTRA_CONVERSATION_ICON,
            Notification.EXTRA_LARGE_ICON_BIG,
            Notification.EXTRA_LARGE_ICON,
            Notification.EXTRA_SMALL_ICON
        ).mapNotNull { key -> getDrawableFromExtras(notification.extras, key, context) }
            .firstOrNull() ?: getNotificationIcon(sbn, notification) ?: return
        val appLabel = getAppLabel(getActiveAppVolumePackage(), context)
        isNowPlaying = sbn.packageName == "com.android.systemui" &&
                       islandTitle.toLowerCase(Locale.ENGLISH).equals(
                           context.getString(R.string.now_playing_on, appLabel).toLowerCase(Locale.ENGLISH)
                       )
        val isSystem = sbn.packageName == "android" || sbn.packageName == "com.android.systemui"
        notifTitle = when {
            isNowPlaying ->
                { islandText.takeIf { it.isNotBlank() } ?: return } // island now playing 
            isSystem && !isNowPlaying -> { "" } // USB debugging notification etc
            else -> {
                islandTitle.takeIf { it.isNotBlank() } ?: return // normal apps
            }
        }
        notifContent = if (isNowPlaying) {
            "" // No content for now playing notifications
        } else {
            islandText.takeIf { it.isNotBlank() } ?: "" // Normal apps
        }
        notifSubContent = if (isNowPlaying) "" else notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        titleSpannable = SpannableString(notifTitle.ifEmpty { notifContent }).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val resources = context.resources
        val bitmap = drawableToBitmap(iconDrawable)
        val roundedIcon = CircleFramedDrawable(bitmap, this.iconSize)
        recycleBitmap((this.icon as? BitmapDrawable)?.bitmap)
        this.icon = roundedIcon
        this.iconTint = null
        this.bringToFront()
        notifPackage = if (isNowPlaying) getActiveAppVolumePackage() else sbn.packageName
        setOnTouchListener(sbn.notification.contentIntent, notifPackage)
    }

    private fun resolveNotificationContent(notification: Notification): Pair<String, String> {
        val titleText = notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: notification.extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: notification.extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
            ?: ""
        val contentText = notification.extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ""
        return titleText.toString() to contentText.toString()
    }

    fun getApplicationInfo(sbn: StatusBarNotification): ApplicationInfo {
        return context.packageManager.getApplicationInfoAsUser(
                sbn.packageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
                sbn.getUser().getIdentifier())
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
        recycleBitmap((this.icon as? BitmapDrawable)?.bitmap)
        return bitmap
    }

    private fun recycleBitmap(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
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
        val threshold = dpToPx(40f)
        val halfThreshold = threshold / 2
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (isLongPress) return false
                val newTranslationX = translationX - distanceX
                translationX = newTranslationX.coerceIn(-width.toFloat(), width.toFloat())
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (isLongPress) return false
                if (e1 != null && e2 != null) {
                    val deltaX = e2.x - e1.x
                    val deltaY = e2.y - e1.y
                    if (abs(deltaX) > abs(deltaY) && abs(deltaX) > threshold) {
                        animateDismiss(if (deltaX > 0) 1 else -1)
                        return true
                    }
                }
                return false
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (intent == null) return false
                onSingleTap(intent, packageName)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                isLongPress = true
                onLongPress()
            }
        })
        this.setOnTouchListener { view, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    if (isLongPress) {
                        isLongPress = false
                        return@setOnTouchListener true
                    }
                    if (isDismissed) return@setOnTouchListener true
                    if (abs(translationX) >= halfThreshold) {
                        if (abs(translationX) >= threshold) {
                            animateDismiss(if (translationX > 0) 1 else -1)
                        } else {
                            visibility = View.GONE
                            translationX = 0f
                            alpha = 1f
                            isDismissed = true
                            isIslandAnimating = false
                        }
                    } else {
                        animate().translationX(0f).alpha(1f).start()
                    }
                }
            }
            true
        }
    }

    private fun animateDismiss(direction: Int) {
        val animationDuration = 300L
        val endTranslationX = direction * width.toFloat()
        val endAlpha = 0f
        val animator = animate()
            .translationX(endTranslationX)
            .alpha(endAlpha)
            .setDuration(animationDuration)
        animator.interpolator = AccelerateInterpolator()
        animator.withEndAction {
            visibility = View.GONE
            translationX = 0f
            isDismissed = true
            isIslandAnimating = false
        }
        animator.start()
    }

    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
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
        return !isCurrentNotifActivityOnTop(notifPackage) or !isCurrentNotifActivityOnTop(getActiveAppVolumePackage())
    }

    fun isCurrentNotifActivityOnTop(packageName: String): Boolean {
        return topActivityPackage.isNotEmpty() && topActivityPackage == packageName
    }

}
