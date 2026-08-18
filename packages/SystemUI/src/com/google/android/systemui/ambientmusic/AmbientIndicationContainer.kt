package com.google.android.systemui.ambientmusic

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.Trace
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.util.LruCache
import android.util.MathUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import com.android.app.animation.Interpolators
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.AutoReinflateContainer
import com.android.systemui.Dependency
import com.android.systemui.doze.DozeReceiver
import com.android.systemui.graphics.ImageLoader
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.monet.ColorScheme
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.wakelock.DelayedWakeLock
import com.android.systemui.util.wakelock.WakeLock
import com.google.android.systemui.keyguard.shared.ExtendedIndication
import com.google.ux.material.libmonet.dynamiccolor.MaterialDynamicColors
import java.util.Objects
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

open class AmbientIndicationContainer(context: Context, attrs: AttributeSet) :
    AutoReinflateContainer(context, attrs),
    DozeReceiver,
    StatusBarStateController.StateListener,
    NotificationMediaManager.MediaListener,
    BcSmartspaceDataPlugin.SmartspaceTargetListener {

    var mActivityStarter: ActivityStarter? = null
    var mAmbientIconOverride: Drawable? = null
    lateinit var mAmbientIndicationActionContainer: LinearLayout
    lateinit var mAmbientIndicationCollapsedContainer: LinearLayout
    lateinit var mAmbientIndicationContainer: ConstraintLayout
    lateinit var mAmbientIndicationContainerBackground: ImageView
    lateinit var mAmbientIndicationExtendedContainer: FrameLayout
    var mAmbientIndicationIconSize: Int = 0
    lateinit var mAmbientIndicationInfoContainer: LinearLayout
    lateinit var mAmbientIndicationLikeContainer: FrameLayout
    lateinit var mAmbientIndicationLikeIcon: ImageView
    lateinit var mAmbientIndicationPlayContainer: FrameLayout
    lateinit var mAmbientIndicationPlayIcon: ImageView
    lateinit var mAmbientIndicationTextContainer: FrameLayout
    lateinit var mAmbientIndicationWrapperContainer: FrameLayout
    var mAmbientMusicAnimation: Drawable? = null
    var mAmbientMusicNoteIcon: Drawable? = null
    var mAmbientMusicNoteIconIconSize: Int = 0
    var mAmbientMusicText: CharSequence? = null
    var mAmbientSkipUnlock: Boolean = false
    var mAnimationState: Int = 0
    lateinit var mBackgroundExecutor: Executor
    var mCurrentLoadedAlbumArtUri: Uri? = null
    var mDelayedWakeLockFactory: DelayedWakeLock.Factory? = null
    lateinit var mDockedTopIcon: ImageView
    var mDozing: Boolean = false
    val mEnabledExtendedInteraction: Boolean
    var mExtendedIndication: ExtendedIndication? = null
    var mFalsingManager: FalsingManager? = null
    var mFavoritingIntent: PendingIntent? = null
    val mHandler: Handler
    val mIconBounds: Rect
    var mIconDescription: String? = null
    var mIconOverride: Int = 0
    lateinit var mIconView: ImageView
    var mImageLoader: ImageLoader? = null
    var mIndicationTextMode: Int = 0
    var mIsCurrentlyInExpandedState: Boolean = false
    var mKeyguardUpdateMonitor: KeyguardUpdateMonitor? = null
    var mMainDelayableExecutor: DelayableExecutor? = null
    var mMediaPlaybackState: Int = 0
    val mMusicAppIconCache: LruCache<String, Drawable>
    var mOpenIntent: PendingIntent? = null
    var mPowerInteractor: PowerInteractor? = null
    lateinit var mRealTextSet: LinearLayout
    var mStatusBarState: Int = 0
    lateinit var mTempTextSet: LinearLayout
    lateinit var mTempTextView: TextView
    lateinit var mTempTextViewExtended: TextView
    var mTextColor: Int = 0
    var mTextColorAnimator: ValueAnimator? = null
    lateinit var mTextView: TextView
    lateinit var mTextViewExtended: TextView
    var mUsingExtendedIndication: Boolean = false
    var mWakeLock: WakeLock? = null

    init {
        mAnimationState = 0
        mIconBounds = Rect()
        mIconOverride = -1
        mHandler = Handler(Looper.getMainLooper())
        mMusicAppIconCache = LruCache(5)
        mEnabledExtendedInteraction = true
    }

    fun initializeView(child: View? = null) {
        mTextView = findViewById(R.id.ambient_indication_text)
        mIconView = findViewById(R.id.ambient_indication_icon)
        mAmbientIndicationContainer = findViewById(R.id.ambient_indication)
        mAmbientIndicationInfoContainer = findViewById(R.id.ambient_indication_info_container)
        mDockedTopIcon = findViewById(R.id.docked_top_icon)
        mAmbientIndicationContainerBackground =
            findViewById(R.id.ambient_indication_container_background)
        mAmbientIndicationWrapperContainer = findViewById(R.id.ambient_indication_wrapper_container)
        mAmbientIndicationExtendedContainer =
            findViewById(R.id.ambient_indication_extended_container)

        if (mEnabledExtendedInteraction) {
            val wrapperContainer = mAmbientIndicationWrapperContainer
            wrapperContainer.post { applyRoundedOutline(wrapperContainer, 32) }
            val iconView = mIconView
            iconView.post { applyRoundedOutline(iconView, 12) }
        }

        mAmbientIndicationCollapsedContainer =
            findViewById(R.id.ambient_indication_collapsed_container)
        mAmbientIndicationTextContainer = findViewById(R.id.ambient_indication_text_container)
        mAmbientIndicationActionContainer = findViewById(R.id.ambient_indication_action_container)
        mTextViewExtended = findViewById(R.id.ambient_indication_text_extended)
        mAmbientIndicationLikeContainer = findViewById(R.id.ambient_indication_like_container)
        mAmbientIndicationPlayContainer = findViewById(R.id.ambient_indication_play_container)
        mAmbientIndicationLikeIcon = findViewById(R.id.ambient_indication_like_icon)
        mAmbientIndicationPlayIcon = findViewById(R.id.ambient_indication_play_icon)
        mRealTextSet = findViewById(R.id.text_set_real)
        mTempTextSet = findViewById(R.id.text_set_temp)
        mTempTextView = findViewById(R.id.ambient_indication_text_temp)
        mTempTextViewExtended = findViewById(R.id.ambient_indication_text_extended_temp)

        val constraintSet = ConstraintSet()
        val isUdfpsSupported = mKeyguardUpdateMonitor?.isUdfpsSupported() ?: false
        if (isUdfpsSupported) {
            constraintSet.load(context, R.xml.ambient_indication_inner_downwards)
        } else {
            constraintSet.load(context, R.xml.ambient_indication_inner_upwards)
        }
        if (mEnabledExtendedInteraction && mDockedTopIcon.visibility == View.GONE) {
            constraintSet.clear(R.id.ambient_indication_info_container, ConstraintSet.TOP)
            constraintSet.clear(R.id.ambient_indication_info_container, ConstraintSet.BOTTOM)
            constraintSet.connect(
                R.id.ambient_indication_info_container,
                ConstraintSet.TOP,
                ConstraintSet.PARENT_ID,
                ConstraintSet.TOP,
            )
            constraintSet.connect(
                R.id.ambient_indication_info_container,
                ConstraintSet.BOTTOM,
                ConstraintSet.PARENT_ID,
                ConstraintSet.BOTTOM,
            )
        }
        constraintSet.applyTo(mAmbientIndicationContainer)

        mAmbientMusicAnimation = null
        mAmbientMusicNoteIcon = null
        mTextColor = mTextView.currentTextColor
        mAmbientIndicationIconSize =
            resources.getDimensionPixelSize(R.dimen.ambient_indication_icon_size)
        mAmbientMusicNoteIconIconSize =
            resources.getDimensionPixelSize(R.dimen.ambient_indication_note_icon_size)
        if (mEnabledExtendedInteraction) {
            val configuration = context.resources.configuration
            updateContainerWidthOnFoldableDevice(
                configuration.screenWidthDp,
                configuration.smallestScreenWidthDp,
            )
        }
        mTextView.isEnabled = !mDozing
        mIsCurrentlyInExpandedState = false
        updateColors()
        updatePill()

        mTextView.setOnClickListener { onTextClick() }
        mTempTextView.setOnClickListener { onTextClick() }
        mIconView.setOnClickListener { onIconClick() }

        if (mEnabledExtendedInteraction) {
            mAmbientIndicationCollapsedContainer.accessibilityDelegate =
                object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfo,
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                        info.addAction(
                            AccessibilityNodeInfo.AccessibilityAction(
                                16,
                                resources.getString(R.string.accessibility_action_expand),
                            )
                        )
                    }
                }
            mAmbientIndicationCollapsedContainer.setOnClickListener { onWrapperClick() }
            mAmbientIndicationPlayContainer.setOnClickListener { onDmpPlayClick() }
            mAmbientIndicationLikeContainer.setOnClickListener { onIconClick() }
            mAmbientIndicationExtendedContainer.setOnClickListener { onExtendedContainerClick() }
        }
    }

    private fun applyRoundedOutline(view: View, radiusDp: Int) {
        val radiusPx = getPixelsFromDp(radiusDp).toFloat()
        view.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                }
            }
        view.clipToOutline = true
    }

    companion object {
        private const val TAG = "AmbientIndication"

        fun sendBroadcastWithoutDismissingKeyguard(pendingIntent: PendingIntent) {
            if (pendingIntent.isActivity) {
                return
            }
            try {
                pendingIntent.send()
            } catch (e: PendingIntent.CanceledException) {
                Log.w(TAG, "Sending intent failed: $e")
            }
        }

        fun updateContainerAccessibility(
            view: View,
            isImportant: Boolean,
            description: CharSequence?,
        ) {
            view.importantForAccessibility =
                if (isImportant) View.IMPORTANT_FOR_ACCESSIBILITY_YES
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO
            view.contentDescription = if (!isImportant) null else description
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Dependency.get(StatusBarStateController::class.java).addCallback(this)
        Dependency.get(NotificationMediaManager::class.java).addCallback(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Dependency.get(StatusBarStateController::class.java).removeCallback(this)
        Dependency.get(NotificationMediaManager::class.java).removeCallback(this)
        mMediaPlaybackState = 0
    }

    override fun onDozingChanged(isDozing: Boolean) {
        mDozing = isDozing
        visibility = if (mStatusBarState == 1) View.VISIBLE else View.INVISIBLE
        if (mUsingExtendedIndication) {
            if (::mAmbientIndicationExtendedContainer.isInitialized) {
                mAmbientIndicationExtendedContainer.isEnabled = !isDozing
                updateColors()
            }
            return
        }
        if (::mTextView.isInitialized) {
            mTextView.isEnabled = !isDozing
            updateColors()
        }
    }

    override fun dozeTimeTick() {
        updatePill()
    }

    override fun onStateChanged(state: Int) {
        mStatusBarState = state
        visibility = if (state == 1) View.VISIBLE else View.INVISIBLE
    }

    override fun onPrimaryMetadataOrStateChanged(metadata: MediaMetadata?, state: Int) {
        if (mMediaPlaybackState != state) {
            mMediaPlaybackState = state
            if (NotificationMediaManager.isPlayingState(state)) {
                setAmbientMusic(null, null, null, 0, false, null, null)
            }
        }
    }

    override fun onSmartspaceTargetsUpdated(targets: List<out Parcelable>?) {}

    fun onTextClick() {
        val falsingManager = mFalsingManager
        if ((falsingManager == null || !falsingManager.isFalseTap(1)) && mOpenIntent != null) {
            mPowerInteractor?.wakeUpIfDozing("AMBIENT_MUSIC_CLICK", 4)
            if (mAmbientSkipUnlock) {
                sendBroadcastWithoutDismissingKeyguard(mOpenIntent!!)
            } else {
                mActivityStarter?.startPendingIntentDismissingKeyguard(mOpenIntent)
            }
        }
    }

    private fun onIconClick() {
        val falsingManager = mFalsingManager
        if (falsingManager == null || !falsingManager.isFalseTap(1)) {
            if (
                mUsingExtendedIndication &&
                    !mIsCurrentlyInExpandedState &&
                    isExtendedIndicationRecognitionResult()
            ) {
                return
            }
            val favoritingIntent = mFavoritingIntent
            if (favoritingIntent == null) {
                onTextClick()
            } else {
                mPowerInteractor?.wakeUpIfDozing("AMBIENT_MUSIC_CLICK", 4)
                sendBroadcastWithoutDismissingKeyguard(favoritingIntent)
            }
        }
    }

    private fun onWrapperClick() {
        val falsingManager = mFalsingManager
        if ((falsingManager == null || !falsingManager.isFalseTap(1)) && mUsingExtendedIndication) {
            val expanded = mIsCurrentlyInExpandedState
            if (!expanded) {
                if (mExtendedIndication?.expandedIndicationData != null) {
                    performExpandAnimation()
                }
                val expandIntent = mExtendedIndication?.expandIntent
                if (expandIntent != null) {
                    mPowerInteractor?.wakeUpIfDozing("AMBIENT_MUSIC_CLICK", 4)
                    sendBroadcastWithoutDismissingKeyguard(expandIntent)
                }
            } else {
                onTextClick()
            }
        }
    }

    private fun onDmpPlayClick() {
        val falsingManager = mFalsingManager
        val dmpIntent = mExtendedIndication?.expandedIndicationData?.dmpIntent
        if (
            (falsingManager == null || !falsingManager.isFalseTap(1)) &&
                mIsCurrentlyInExpandedState &&
                dmpIntent != null
        ) {
            mPowerInteractor?.wakeUpIfDozing("AMBIENT_MUSIC_CLICK", 4)
            mActivityStarter?.startPendingIntentDismissingKeyguard(dmpIntent)
        }
    }

    private fun onExtendedContainerClick() {
        if (mIsCurrentlyInExpandedState) {
            onTextClick()
        }
    }

    fun setAmbientMusic(
        text: CharSequence?,
        openIntent: PendingIntent?,
        favoritingIntent: PendingIntent?,
        iconOverride: Int,
        skipUnlock: Boolean,
        iconDescription: String?,
        extendedIndication: ExtendedIndication?,
    ) {
        if (
            Objects.equals(mAmbientMusicText, text) &&
                Objects.equals(mOpenIntent, openIntent) &&
                Objects.equals(mFavoritingIntent, favoritingIntent) &&
                mIconOverride == iconOverride &&
                Objects.equals(mIconDescription, iconDescription) &&
                mAmbientSkipUnlock == skipUnlock &&
                Objects.equals(mExtendedIndication, extendedIndication)
        ) {
            return
        }
        val hasExpandedData = extendedIndication?.expandedIndicationData != null
        if (
            mIsCurrentlyInExpandedState &&
                TextUtils.equals(mAmbientMusicText, text) &&
                !hasExpandedData
        ) {
            return
        }
        mAmbientMusicText = text
        mOpenIntent = openIntent
        mFavoritingIntent = favoritingIntent
        mAmbientSkipUnlock = skipUnlock
        mIconOverride = iconOverride
        mIconDescription = iconDescription
        mExtendedIndication = extendedIndication
        mUsingExtendedIndication = extendedIndication != null && mEnabledExtendedInteraction

        var drawable: Drawable? = null
        if (!isExtendedIndicationRecognitionResult()) {
            drawable =
                when (iconOverride) {
                    1 -> context.getDrawable(R.drawable.ic_music_search)
                    3 ->
                        if (mUsingExtendedIndication) {
                            context.getDrawable(R.drawable.ic_now_playing_music_off)
                        } else {
                            context.getDrawable(R.drawable.ic_music_not_found)
                        }
                    4 -> context.getDrawable(R.drawable.ic_cloud_off)
                    5 -> context.getDrawable(R.drawable.ic_favorite)
                    6 -> context.getDrawable(R.drawable.ic_favorite_border)
                    7 -> context.getDrawable(R.drawable.ic_error)
                    8 -> context.getDrawable(R.drawable.ic_favorite_note)
                    else -> null
                }
        }
        mAmbientIconOverride = drawable
        updatePill()
    }

    fun performCollapseAnimation() {
        if (mAnimationState != 0) return
        mAnimationState = 2
        Trace.beginAsyncSection("collapse_animation", 4)
        mIsCurrentlyInExpandedState = false
        setContentDescriptionForOuterContainer()
        val hadLoadedArt = mCurrentLoadedAlbumArtUri != null
        mCurrentLoadedAlbumArtUri = null
        updateColors()
        mAmbientIndicationExtendedContainer.post {
            performCollapseAnimationContinuation(hadLoadedArt)
        }
    }

    fun performExpandAnimation() {
        if (mAnimationState != 0) return
        mAnimationState = 1
        Trace.beginAsyncSection("expand_animation", 2)
        mAmbientIndicationPlayContainer.alpha = 0.0f
        mAmbientIndicationLikeContainer.alpha = 0.0f
        mAmbientIndicationPlayIcon.alpha = 0.0f
        mAmbientIndicationLikeIcon.alpha = 0.0f
        updateIcons()
        mAmbientIndicationContainerBackground.visibility = View.VISIBLE
        mIsCurrentlyInExpandedState = true
        setContentDescriptionForOuterContainer()
        Trace.beginAsyncSection("bind_artwork", 3)
        mAmbientIndicationExtendedContainer.post { bindArtworkAsync() }
        mAmbientIndicationActionContainer.visibility = View.VISIBLE
        mAmbientIndicationExtendedContainer.post {
            if (mIsCurrentlyInExpandedState) {
                animateCollapsedContainerTranslationX()
                animateActionContainerTranslationX()
            }
        }
    }

    fun restoreToCollapsedState() {
        if (mIsCurrentlyInExpandedState) {
            performCollapseAnimation()
            return
        }
        mAmbientIndicationActionContainer.visibility = View.GONE
        mAmbientIndicationExtendedContainer.background = null
        mIsCurrentlyInExpandedState = false
        adjustTextContainerPadding()
        mCurrentLoadedAlbumArtUri = null
    }

    fun setContentDescriptionForOuterContainer() {
        var focusTarget: View? = null
        var description: CharSequence? = null
        val extendedIndication = mExtendedIndication
        if (mUsingExtendedIndication && extendedIndication != null) {
            if (extendedIndication.isRecognitionResult == true) {
                focusTarget =
                    if (mIsCurrentlyInExpandedState) mAmbientIndicationExtendedContainer
                    else mAmbientIndicationCollapsedContainer
                description =
                    context.getString(
                        R.string
                            .accessibility_now_playing_container_recognition_result_content_description,
                        extendedIndication.songTitle,
                        extendedIndication.artistName,
                    )
            } else {
                focusTarget = mTextView
                description =
                    if (extendedIndication.isSongSearching == true) {
                        context.getString(
                            R.string
                                .accessibility_now_playing_container_song_searching_content_description
                        )
                    } else {
                        mAmbientMusicText
                    }
            }
        }
        val collapsedContainer = mAmbientIndicationCollapsedContainer
        updateContainerAccessibility(
            collapsedContainer,
            focusTarget === collapsedContainer,
            description,
        )
        val extendedContainer = mAmbientIndicationExtendedContainer
        updateContainerAccessibility(
            extendedContainer,
            focusTarget === extendedContainer,
            description,
        )
        val textView = mTextView
        updateContainerAccessibility(textView, focusTarget === textView, description)
    }

    fun updateColors() {
        val animator = mTextColorAnimator
        if (animator != null && animator.isRunning) {
            mTextColorAnimator?.cancel()
        }
        val defaultColor = mTextView.textColors.defaultColor
        val targetColor = if (mDozing) -1 else mTextColor
        if (defaultColor == targetColor) {
            mTextView.setTextColor(targetColor)
            if (mUsingExtendedIndication) {
                mTextViewExtended.setTextColor(targetColor)
            }
            if (mIsCurrentlyInExpandedState) return
            mIconView.imageTintList = ColorStateList.valueOf(targetColor)
            return
        }
        val colorAnimator = ValueAnimator.ofArgb(defaultColor, targetColor)
        mTextColorAnimator = colorAnimator
        colorAnimator.interpolator = Interpolators.LINEAR_OUT_SLOW_IN
        colorAnimator.duration = 500L
        colorAnimator.addUpdateListener { anim ->
            val color = anim.animatedValue as Int
            mTextView.setTextColor(color)
            if (mUsingExtendedIndication) {
                mTextViewExtended.setTextColor(color)
            }
            if (!mIsCurrentlyInExpandedState) {
                mIconView.imageTintList = ColorStateList.valueOf(color)
            }
        }
        colorAnimator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    mTextColorAnimator = null
                }
            }
        )
        colorAnimator.start()
    }

    fun updateContainerWidthOnFoldableDevice(screenWidthDp: Int, smallestScreenWidthDp: Int) {
        if (!mEnabledExtendedInteraction) return
        val widthPx =
            if (smallestScreenWidthDp >= 600) {
                getPixelsFromDp((screenWidthDp / 2) - 24)
            } else {
                getPixelsFromDp(screenWidthDp)
            }
        if (!::mAmbientIndicationInfoContainer.isInitialized) {
            mAmbientIndicationInfoContainer = findViewById(R.id.ambient_indication_info_container)
        }
        val layoutParams =
            mAmbientIndicationInfoContainer.layoutParams as ConstraintLayout.LayoutParams
        if (widthPx != layoutParams.width) {
            layoutParams.width = widthPx
            mAmbientIndicationInfoContainer.layoutParams = layoutParams
            if (mEnabledExtendedInteraction) {
                mAmbientIndicationExtendedContainer.post { updateTextEllipsizing() }
            }
        }
    }

    fun updateIcons() {
        val expandedIndicationData = mExtendedIndication?.expandedIndicationData ?: return
        mAmbientIndicationLikeContainer.visibility = View.VISIBLE
        val isFavorite = expandedIndicationData.isFavorite == true
        val dmpPackageName = expandedIndicationData.dmpPackageName
        mAmbientIndicationLikeIcon.setImageResource(
            if (isFavorite) R.drawable.ic_now_playing_heart_minus
            else R.drawable.ic_now_playing_heart_plus
        )
        mAmbientIndicationLikeIcon.contentDescription =
            if (isFavorite) {
                context.getString(R.string.accessibility_now_playing_unlike_icon)
            } else {
                context.getString(R.string.accessibility_now_playing_like_icon)
            }
        if (dmpPackageName.isNullOrEmpty() || expandedIndicationData.dmpIntent == null) {
            mAmbientIndicationPlayContainer.visibility = View.GONE
            return
        }
        var applicationIcon: Drawable? = mMusicAppIconCache.get(dmpPackageName)
        if (applicationIcon == null) {
            applicationIcon =
                try {
                    var icon = context.packageManager.getApplicationIcon(dmpPackageName)
                    if (icon is AdaptiveIconDrawable) {
                        var monochrome = icon.monochrome ?: icon.foreground
                        val sizePx = getPixelsFromDp(36)
                        monochrome?.setBounds(0, 0, sizePx, sizePx)
                        icon = monochrome as Drawable
                    }
                    icon
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(
                        TAG,
                        "Failed to get icon for music app with package name: $dmpPackageName",
                        e,
                    )
                    null
                }
            if (applicationIcon != null) {
                mMusicAppIconCache.put(dmpPackageName, applicationIcon)
            }
        }
        mAmbientIndicationPlayIcon.setImageDrawable(applicationIcon)
        mAmbientIndicationPlayContainer.visibility = View.VISIBLE
    }

    fun createWakeLock(): WakeLock? {
        return mDelayedWakeLockFactory?.create("AmbientIndication")
    }

    fun getAmbientMusicNoteIcon(): Drawable? {
        if (mAmbientMusicNoteIcon == null) {
            val drawable =
                if (mUsingExtendedIndication) {
                    context.getDrawable(R.drawable.ic_now_playing_lockscreen)
                } else {
                    context.getDrawable(R.drawable.ic_music_note)
                }
            mAmbientMusicNoteIcon = drawable
            drawable?.mutate()
        }
        return mAmbientMusicNoteIcon
    }

    fun getPixelsFromDp(dp: Int): Int {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp.toFloat(),
                resources.displayMetrics,
            )
            .toInt()
    }

    fun isExtendedIndicationRecognitionResult(): Boolean {
        val extendedIndication = mExtendedIndication
        return mUsingExtendedIndication &&
            extendedIndication != null &&
            extendedIndication.isRecognitionResult == true
    }

    fun isExtendedIndicationToBeExpanded(): Boolean {
        val extendedIndication = mExtendedIndication
        return mUsingExtendedIndication &&
            extendedIndication != null &&
            extendedIndication.expandedIndicationData != null
    }

    fun adjustTextContainerPadding() {
        val startPaddingRes =
            if (mIsCurrentlyInExpandedState) {
                R.dimen.ambient_indication_extended_text_container_start_padding_expanded
            } else {
                R.dimen.ambient_indication_extended_text_container_start_padding_collapsed
            }
        var startPadding = resources.getDimensionPixelSize(startPaddingRes)
        val endPadding =
            if (mUsingExtendedIndication && !TextUtils.isEmpty(mAmbientMusicText)) {
                resources.getDimensionPixelSize(
                    R.dimen.ambient_indication_extended_text_container_end_padding
                )
            } else {
                0
            }
        val container = mAmbientIndicationTextContainer
        if (!mUsingExtendedIndication) {
            startPadding = 0
        }
        container.setPaddingRelative(
            startPadding,
            container.paddingTop,
            endPadding,
            mAmbientIndicationTextContainer.paddingBottom,
        )
    }

    fun animateActionContainerTranslationX() {
        val width =
            ((mAmbientIndicationExtendedContainer.width -
                mAmbientIndicationExtendedContainer.paddingStart -
                mAmbientIndicationExtendedContainer.paddingEnd) -
                mAmbientIndicationActionContainer.width) / 2.0f
        val expanded = mIsCurrentlyInExpandedState
        val gravity = if (expanded) 8388613 /* END | CENTER_VERTICAL */ else 17 /* CENTER */
        AmbientIndicationAnimationUtils.animateTranslationX(
            mAmbientIndicationActionContainer,
            (if (expanded) 1 else -1) * width,
            null,
            Runnable { finishActionContainerTranslation(gravity) },
            AmbientIndicationAnimationUtils.defaultSpatialSpec,
        )
    }

    private fun finishActionContainerTranslation(gravity: Int) {
        mAmbientIndicationActionContainer.translationX = 0.0f
        val layoutParams =
            mAmbientIndicationActionContainer.layoutParams as FrameLayout.LayoutParams
        layoutParams.gravity = gravity or 16 /* CENTER_VERTICAL */
        mAmbientIndicationActionContainer.layoutParams = layoutParams
        if (mIsCurrentlyInExpandedState) {
            Trace.endAsyncSection("expand_animation", 2)
        } else {
            Trace.endAsyncSection("collapse_animation", 4)
        }
        mAnimationState = 0
    }

    fun animateCollapsedContainerTranslationX() {
        val startPadding =
            if (mAmbientIndicationTextContainer.visibility == View.GONE) {
                0
            } else {
                resources.getDimensionPixelSize(
                    R.dimen.ambient_indication_extended_text_container_start_padding_collapsed
                ) +
                    (mAmbientIndicationTextContainer.width -
                        mAmbientIndicationTextContainer.paddingStart)
            }
        val width =
            ((mAmbientIndicationExtendedContainer.width -
                mAmbientIndicationExtendedContainer.paddingStart -
                mAmbientIndicationExtendedContainer.paddingEnd) -
                (mIconView.width + startPadding)) / 2.0f
        val animationEndCounter = AtomicInteger(0)
        val gravity =
            if (mIsCurrentlyInExpandedState) 8388611 /* START | CENTER_VERTICAL */
            else 17 /* CENTER */
        val hasTriggeredActionContainerAlphaAnim = AtomicBoolean(false)

        val updateListener: DynamicAnimation.OnAnimationUpdateListener? =
            if (mIsCurrentlyInExpandedState) {
                DynamicAnimation.OnAnimationUpdateListener { _, value, _ ->
                    if (
                        !hasTriggeredActionContainerAlphaAnim.get() &&
                            Math.abs(value) / width >= 0.37f
                    ) {
                        AmbientIndicationAnimationHelper.animateActionButtonsAlphaWithSpring(
                            mAmbientIndicationLikeContainer,
                            mAmbientIndicationLikeIcon,
                            mAmbientIndicationActionContainer,
                            1.0f,
                            0.6f,
                        )
                        AmbientIndicationAnimationHelper.animateActionButtonsAlphaWithSpring(
                            mAmbientIndicationPlayContainer,
                            mAmbientIndicationPlayIcon,
                            mAmbientIndicationActionContainer,
                            1.0f,
                            0.6f,
                        )
                        hasTriggeredActionContainerAlphaAnim.set(true)
                    }
                }
            } else {
                null
            }

        val onEnd = Runnable {
            if (animationEndCounter.incrementAndGet() == 2) {
                mIconView.translationX = 0.0f
                mAmbientIndicationTextContainer.translationX = 0.0f
                mAmbientIndicationCollapsedContainer.gravity = gravity or 16
                adjustTextContainerPadding()
            }
        }

        val startPaddingOffset = width - getPixelsFromDp(14)
        val expanded = mIsCurrentlyInExpandedState
        val direction = if (expanded) -1 else 1
        val iconTargetTranslation = width * direction
        val springForce = AmbientIndicationAnimationUtils.defaultSpatialSpec
        AmbientIndicationAnimationUtils.animateTranslationX(
            mIconView,
            iconTargetTranslation,
            updateListener,
            onEnd,
            springForce,
        )
        AmbientIndicationAnimationUtils.animateTranslationX(
            mAmbientIndicationTextContainer,
            direction * startPaddingOffset,
            null,
            onEnd,
            springForce,
        )
    }

    fun bindArtworkAsync() {
        if (!mIsCurrentlyInExpandedState) return
        val imageLoader = mImageLoader ?: return
        val width = mAmbientIndicationExtendedContainer.width
        val height = mAmbientIndicationExtendedContainer.height
        val uri = mExtendedIndication?.expandedIndicationData?.albumArtUri

        var cached = AmbientIndicationArtworkHelper.lastArtworkResult
        if (cached != null) {
            if (cached.albumArtUri != uri) {
                cached = null
            }
            if (cached != null) {
                updateColorScheme(
                    cached.artwork,
                    cached.colorScheme,
                    cached.albumArtUri,
                    cached.smallIcon,
                )
                return
            }
        }
        AmbientIndicationArtworkHelper.processArtwork(
            context,
            imageLoader,
            uri,
            width,
            height,
            mHandler,
        ) { artwork, colorScheme, albumArtUri, smallIcon ->
            updateColorScheme(artwork, colorScheme, albumArtUri, smallIcon)
        }
    }

    fun updateColorScheme(
        artwork: Drawable?,
        colorScheme: ColorScheme?,
        albumArtUri: Uri?,
        smallIcon: Drawable?,
    ) {
        val imageView = mAmbientIndicationContainerBackground
        val background = imageView.background
        val toBgDrawable = artwork
        val toBgColor =
            if (colorScheme != null) {
                colorScheme.materialScheme.secondaryPalette.tone(20)
            } else {
                context.getColor(R.color.material_deep_teal_300)
            }

        val bgAnimationEnd = AmbientIndicationAnimationHelper.bgAnimationEndTraceGuard(toBgDrawable)

        if (background == null) {
            mMainDelayableExecutor?.executeDelayed(
                AmbientIndicationAnimationHelper.animateBackgroundArtworkInExpand(
                    imageView,
                    toBgColor,
                    bgAnimationEnd,
                    toBgDrawable,
                ),
                33L,
            )
        } else {
            val colorDrawable = background as? ColorDrawable
            if (colorDrawable != null) {
                AmbientIndicationAnimationHelper.animateDrawableColor(
                    colorDrawable,
                    AmbientIndicationAnimationHelper.getBackgroundColor(imageView),
                    toBgColor,
                    bgAnimationEnd,
                    AmbientIndicationAnimationHelper::setColorDrawableColor,
                )
            }
            AmbientIndicationAnimationHelper.animateBackgroundArtworkInExpandStartToSrcAnimation(
                toBgDrawable,
                imageView,
            )
        }

        val iconView = mIconView
        if (smallIcon == null) {
            val fallbackColor = context.getColor(R.color.material_grey_100)
            val currentTint = iconView.imageTintList
            val colorAnimator =
                ValueAnimator.ofArgb(
                    currentTint?.defaultColor ?: 0,
                    context.getColor(com.android.internal.R.color.materialColorShadow),
                )
            colorAnimator.duration = 200L
            colorAnimator.addUpdateListener(
                AmbientIndicationAnimationHelper.iconTintUpdateListener(iconView)
            )
            colorAnimator.start()

            val colorDrawable = ColorDrawable(fallbackColor)
            val noteDrawable = context.getDrawable(R.drawable.ic_now_playing_music_note)
            colorDrawable.alpha = 0
            iconView.background = colorDrawable
            AmbientIndicationAnimationUtils.animateDrawableAlpha(
                colorDrawable,
                iconView,
                255,
                null,
                AmbientIndicationAnimationHelper.animateIconAndThumbnailOnExpandNoAlbumArtEnd,
                AmbientIndicationAnimationUtils.defaultEffectsSpec,
            )
            val currentIconDrawable = iconView.drawable
            if (noteDrawable != null && currentIconDrawable != null) {
                AmbientIndicationAnimationUtils.animateDrawableAlpha(
                    currentIconDrawable,
                    iconView,
                    0,
                    AmbientIndicationAnimationHelper
                        .animateIconTransitionExpandNoIconUpdateListener(iconView, noteDrawable),
                    AmbientIndicationAnimationHelper.animateIconTransitionEnd,
                    AmbientIndicationAnimationUtils.defaultEffectsSpec,
                )
            }
        } else {
            val currentIconDrawable = iconView.drawable
            if (currentIconDrawable != null) {
                val hasTriggeredBgDrawableAnim = AtomicBoolean(false)
                AmbientIndicationAnimationUtils.animateDrawableAlpha(
                    currentIconDrawable,
                    iconView,
                    0,
                    AmbientIndicationAnimationHelper
                        .animateIconAndThumbnailOnExpandWithAlbumArtUpdateListener(
                            iconView,
                            smallIcon,
                            hasTriggeredBgDrawableAnim,
                        ),
                    AmbientIndicationAnimationHelper.animateBackgroundArtworkInCollapseEndAction(
                        iconView,
                        3,
                    ),
                    AmbientIndicationAnimationUtils.fastEffectsSpec,
                )
            }
        }

        val playAndLikeContainers =
            listOf(mAmbientIndicationPlayContainer, mAmbientIndicationLikeContainer)
        val playAndLikeIcons = listOf(mAmbientIndicationPlayIcon, mAmbientIndicationLikeIcon)
        val dynamicScheme = colorScheme?.materialScheme
        val containerTargetColor =
            if (dynamicScheme != null) {
                MaterialDynamicColors().primaryFixed().getArgb(dynamicScheme)
            } else {
                context.getColor(com.android.internal.R.color.materialColorTertiaryFixedDim)
            }
        val iconTargetColor =
            if (dynamicScheme != null) {
                MaterialDynamicColors().onPrimaryFixed().getArgb(dynamicScheme)
            } else {
                context.getColor(com.android.internal.R.color.materialColorPrimaryContainer)
            }
        val containerFromColor =
            AmbientIndicationAnimationHelper.getBackgroundColor(playAndLikeContainers.first())
        val iconFromColor = playAndLikeIcons.first().imageTintList?.defaultColor ?: 0

        for (view in playAndLikeContainers) {
            val gradientDrawable = view.background as? GradientDrawable
            if (gradientDrawable != null) {
                AmbientIndicationAnimationHelper.animateDrawableColor(
                    gradientDrawable,
                    containerFromColor,
                    containerTargetColor,
                    AmbientIndicationAnimationHelper.updateActionContainerColorsEnd,
                    AmbientIndicationAnimationHelper::setGradientDrawableColor,
                )
            }
        }
        for (icon in playAndLikeIcons) {
            val colorAnimator = ValueAnimator.ofArgb(iconFromColor, iconTargetColor)
            colorAnimator.duration = 200L
            colorAnimator.addUpdateListener(
                AmbientIndicationAnimationHelper.iconTintUpdateListener(icon)
            )
            colorAnimator.start()
        }

        val textViews = listOf(mTextView, mTextViewExtended)
        val hasArtwork = artwork != null
        var textTargetColor = mTextColor
        val textFromColor = textViews.first().textColors.defaultColor
        if (!hasArtwork) {
            textTargetColor =
                context.getColor(com.android.internal.R.color.materialColorSecondaryFixedDim)
        }
        val textColorAnimator = ValueAnimator.ofArgb(textFromColor, textTargetColor)
        textColorAnimator.duration = 200L
        textColorAnimator.addUpdateListener(
            AmbientIndicationAnimationHelper.textColorsUpdateListener(textViews)
        )
        textColorAnimator.start()

        mCurrentLoadedAlbumArtUri = albumArtUri
    }

    fun updatePill() {
        if (!::mTextView.isInitialized) return

        val previousMode = mIndicationTextMode
        mIndicationTextMode = 1
        var text = mAmbientMusicText
        val textCurrentlyVisible = mTextView.visibility == View.VISIBLE
        val textIsEmptyNonNull = mAmbientMusicText != null && mAmbientMusicText!!.isEmpty()

        mTextView.isClickable = mOpenIntent != null && !isExtendedIndicationRecognitionResult()
        mIconView.isClickable =
            (mFavoritingIntent != null || mOpenIntent != null) &&
                !isExtendedIndicationRecognitionResult()

        var iconDrawable: Drawable?
        var textIsEmpty: Boolean

        if (!TextUtils.isEmpty(text) || textIsEmptyNonNull) {
            var icon = mAmbientIconOverride
            if (icon == null) {
                icon =
                    if (textCurrentlyVisible) {
                        getAmbientMusicNoteIcon()
                    } else {
                        if (mAmbientMusicAnimation == null) {
                            mAmbientMusicAnimation = context.getDrawable(R.anim.audioanim_animation)
                        }
                        mAmbientMusicAnimation
                    }
                if (mUsingExtendedIndication) {
                    icon = getAmbientMusicNoteIcon()
                }
            }
            iconDrawable = icon
            textIsEmpty = textIsEmptyNonNull
        } else {
            iconDrawable = null
            textIsEmpty = textIsEmptyNonNull
        }

        if (mEnabledExtendedInteraction) {
            mAmbientIndicationExtendedContainer.post { updateTextEllipsizing() }
        }

        if (isExtendedIndicationRecognitionResult()) {
            text = mExtendedIndication?.songTitle
        }
        val artistText =
            if (isExtendedIndicationRecognitionResult()) mExtendedIndication?.artistName else null

        mTextView.setTypeface(mTextView.typeface, if (mUsingExtendedIndication) 1 else 0)
        mTempTextView.setTypeface(mTextView.typeface, 1)

        val finalIconDrawable: Drawable?
        if (iconDrawable != null) {
            mIconBounds.set(0, 0, iconDrawable.intrinsicWidth, iconDrawable.intrinsicHeight)
            var iconSize =
                if (iconDrawable === mAmbientMusicNoteIcon) mAmbientMusicNoteIconIconSize
                else mAmbientIndicationIconSize
            if (mUsingExtendedIndication) {
                iconSize = mAmbientIndicationIconSize
            }
            MathUtils.fitRect(mIconBounds, iconSize)
            val boundedIcon =
                object : DrawableWrapper(iconDrawable) {
                    override fun getIntrinsicHeight(): Int = mIconBounds.height()

                    override fun getIntrinsicWidth(): Int = mIconBounds.width()
                }
            val endPadding =
                if (!TextUtils.isEmpty(text)) (resources.displayMetrics.density * 24.0f).toInt()
                else 0
            if (!mUsingExtendedIndication) {
                mTextView.setPaddingRelative(
                    mTextView.paddingStart,
                    mTextView.paddingTop,
                    endPadding,
                    mTextView.paddingBottom,
                )
            }
            finalIconDrawable = boundedIcon
        } else {
            if (!mUsingExtendedIndication) {
                mTextView.setPaddingRelative(
                    mTextView.paddingStart,
                    mTextView.paddingTop,
                    0,
                    mTextView.paddingBottom,
                )
            }
            finalIconDrawable = iconDrawable
        }

        if (!mIsCurrentlyInExpandedState) {
            mIconView.setImageDrawable(finalIconDrawable)
        }
        if (
            mUsingExtendedIndication &&
                finalIconDrawable != null &&
                mAmbientIndicationContainerBackground.drawable == null
        ) {
            finalIconDrawable.alpha = 255
        }

        val hasContent = !TextUtils.isEmpty(text) || textIsEmpty
        val contentVisibility = if (hasContent) View.VISIBLE else View.GONE
        mTextView.visibility = contentVisibility
        if (iconDrawable == null) {
            mIconView.visibility = View.GONE
        } else {
            mIconView.visibility = contentVisibility
        }
        mAmbientIndicationWrapperContainer.visibility = contentVisibility
        mTextViewExtended.visibility =
            if (
                hasContent &&
                    isExtendedIndicationRecognitionResult() &&
                    !TextUtils.isEmpty(mExtendedIndication?.artistName)
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
        mAmbientIndicationTextContainer.visibility =
            if (TextUtils.isEmpty(text)) View.GONE else View.VISIBLE

        if (
            !mIsCurrentlyInExpandedState &&
                isExtendedIndicationRecognitionResult() &&
                !isExtendedIndicationToBeExpanded() &&
                textCurrentlyVisible &&
                !TextUtils.equals(mTextView.text, mExtendedIndication?.songTitle)
        ) {
            val newSong = mExtendedIndication?.songTitle
            val newArtist = mExtendedIndication?.artistName
            setContentDescriptionForOuterContainer()
            val realSetWidth = mRealTextSet.width
            mTempTextView.text = newSong
            mTempTextViewExtended.text = newArtist
            mTempTextSet.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val measuredWidth = mTempTextSet.measuredWidth
            mTempTextView.text = null
            mTempTextViewExtended.text = null
            runSongChangeSlideAnimation(measuredWidth, realSetWidth, newSong, newArtist)
            return
        }

        if (mUsingExtendedIndication && mExtendedIndication?.isSongSearching == true) {
            mIconView.setImageDrawable(context.getDrawable(R.drawable.avd_nowplaying_searching))
            val iconDrawableNow = mIconView.drawable
            setContentDescriptionForOuterContainer()
            if (iconDrawableNow is AnimatedVectorDrawable) {
                iconDrawableNow.registerAnimationCallback(
                    object : Animatable2.AnimationCallback() {
                        override fun onAnimationEnd(drawable: Drawable) {
                            if (mIconView.drawable === drawable) {
                                iconDrawableNow.start()
                            }
                        }
                    }
                )
                iconDrawableNow.start()
            }
            if (!mIsCurrentlyInExpandedState) {
                mTextView.text = text
                val collapsedContainer = mAmbientIndicationCollapsedContainer
                val translateY =
                    resources
                        .getDimensionPixelSize(
                            R.dimen.ambient_indication_song_searching_animation_translation_y
                        )
                        .toFloat()
                collapsedContainer.translationY = translateY
                collapsedContainer.alpha = 0.0f
                AmbientIndicationAnimationUtils.animateTranslationY(
                    collapsedContainer,
                    0.0f,
                    null,
                    null,
                    AmbientIndicationAnimationUtils.fastSpatialSpec,
                )
                mMainDelayableExecutor?.executeDelayed(
                    AmbientIndicationAnimationHelper.performSongSearchingAnimationContinuation(
                        collapsedContainer
                    ),
                    50L,
                )
                return
            }
        }

        mTextView.text = text
        mTextViewExtended.text = artistText

        if (mUsingExtendedIndication && hasContent && isExtendedIndicationRecognitionResult()) {
            val expandedIndicationData = mExtendedIndication?.expandedIndicationData
            if (!mIsCurrentlyInExpandedState && isExtendedIndicationToBeExpanded()) {
                performExpandAnimation()
            } else if (mIsCurrentlyInExpandedState && isExtendedIndicationToBeExpanded()) {
                updateIcons()
                val newAlbumArtUri = expandedIndicationData?.albumArtUri
                if (
                    newAlbumArtUri != null &&
                        (mCurrentLoadedAlbumArtUri == null ||
                            mCurrentLoadedAlbumArtUri.toString() != newAlbumArtUri.toString())
                ) {
                    mMainDelayableExecutor?.executeDelayed(
                        {
                            Trace.beginAsyncSection("bind_artwork", 3)
                            mAmbientIndicationExtendedContainer.post { bindArtworkAsync() }
                        },
                        800L,
                    )
                }
            } else if (!mIsCurrentlyInExpandedState || isExtendedIndicationToBeExpanded()) {
                adjustTextContainerPadding()
            } else {
                performCollapseAnimation()
            }
        } else {
            restoreToCollapsedState()
        }

        setContentDescriptionForOuterContainer()
        if (mIsCurrentlyInExpandedState) return

        if (!hasContent) {
            mTextView.animate().cancel()
            if (iconDrawable is AnimatedVectorDrawable) {
                iconDrawable.reset()
            }
            val noop = Runnable { /* ExternalSyntheticLambda2: no-op */ }
            val wrapped = mWakeLock?.wrap(noop) ?: noop
            mHandler.post(wrapped)
            return
        }

        if (textCurrentlyVisible) {
            if (previousMode == mIndicationTextMode) {
                val noop = Runnable { /* ExternalSyntheticLambda2: no-op */ }
                val wrapped = mWakeLock?.wrap(noop) ?: noop
                mHandler.post(wrapped)
                return
            } else {
                if (iconDrawable is AnimatedVectorDrawable) {
                    mWakeLock?.acquire("AmbientIndication")
                    iconDrawable.start()
                    mWakeLock?.release("AmbientIndication")
                }
                return
            }
        }

        if (mUsingExtendedIndication && isExtendedIndicationRecognitionResult()) {
            Trace.beginAsyncSection("first_recognition_animation", 1)
            val translateY =
                resources.getDimension(
                    R.dimen.ambient_indication_first_recognition_animation_translation_y
                )
            val extendedContainer = mAmbientIndicationExtendedContainer
            extendedContainer.translationY = translateY
            extendedContainer.alpha = 0.0f
            AmbientIndicationAnimationUtils.animateTranslationY(
                extendedContainer,
                0.0f,
                null,
                AmbientIndicationAnimationHelper.performFirstRecognitionAnimationTranslationEnd,
                AmbientIndicationAnimationUtils.slowSpatialSpec,
            )
            AmbientIndicationAnimationUtils.animateAlpha(
                extendedContainer,
                1.0f,
                null,
                AmbientIndicationAnimationHelper.performFirstRecognitionAnimationAlphaEnd,
                AmbientIndicationAnimationUtils.slowEffectsSpec,
            )
            return
        }

        mWakeLock?.acquire("AmbientIndication")
        if (iconDrawable is AnimatedVectorDrawable) {
            iconDrawable.start()
        }
        mTextView.translationY = (mTextView.height / 2).toFloat()
        mTextView.alpha = 0.0f
        mTextView
            .animate()
            .alpha(1.0f)
            .translationY(0.0f)
            .setStartDelay(150L)
            .setDuration(100L)
            .setListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        mWakeLock?.release("AmbientIndication")
                        mTextView.animate().setListener(null)
                    }
                }
            )
            .setInterpolator(Interpolators.DECELERATE_QUINT)
            .start()
    }

    private fun updateTextEllipsizing() {
        var maxWidth =
            mAmbientIndicationExtendedContainer.width -
                resources.getDimensionPixelSize(
                    R.dimen.ambient_indication_extended_container_non_text_width
                )
        val shouldEllipsize = mIndicationTextMode == 1 && mUsingExtendedIndication
        if (!shouldEllipsize) {
            maxWidth = Int.MAX_VALUE
        }
        val truncateAt = if (shouldEllipsize) TextUtils.TruncateAt.END else null
        for (tv in listOf(mTextView, mTextViewExtended, mTempTextView, mTempTextViewExtended)) {
            tv.maxWidth = maxWidth
            tv.ellipsize = truncateAt
        }
    }

    private fun runSongChangeSlideAnimation(
        newWidth: Int,
        oldWidth: Int,
        newSong: CharSequence?,
        newArtist: CharSequence?,
    ) {
        AmbientIndicationAnimationUtils.animateTranslationX(
            mAmbientIndicationCollapsedContainer,
            (oldWidth - newWidth) / 2.0f,
            null,
            Runnable {
                val action = Runnable {
                    runSongChangeContentSlide(newWidth, oldWidth, newSong, newArtist)
                }
                val wrapped = mWakeLock?.wrap(action) ?: action
                mHandler.post(wrapped)
            },
            AmbientIndicationAnimationUtils.defaultSpatialSpec,
        )
    }

    private fun runSongChangeContentSlide(
        newWidth: Int,
        oldWidth: Int,
        newSong: CharSequence?,
        newArtist: CharSequence?,
    ) {
        if (newWidth > oldWidth) {
            mRealTextSet.layoutParams.width = newWidth
            mAmbientIndicationCollapsedContainer.translationX = 0.0f
        }
        mTempTextView.text = newSong
        mTempTextViewExtended.text = newArtist
        val translateY =
            resources.getDimension(R.dimen.ambient_indication_song_change_animation_translation_y)
        mTempTextSet.translationY = translateY
        mTempTextSet.alpha = 1.0f
        mTempTextView.alpha = 0.0f
        mTempTextViewExtended.alpha = 0.0f
        val springForce = AmbientIndicationAnimationUtils.slowSpatialSpec
        AmbientIndicationAnimationUtils.animateTranslationY(
            mRealTextSet,
            -translateY,
            null,
            null,
            springForce,
        )
        AmbientIndicationAnimationUtils.animateAlpha(
            mRealTextSet,
            0.0f,
            null,
            null,
            AmbientIndicationAnimationUtils.fastEffectsSpec,
        )

        val animationEndCounter = AtomicInteger(3)
        val finalSwapAction =
            AmbientIndicationAnimationHelper.runSongChangeContentSlideFinalSwapAction(
                mTextView,
                newSong,
                mTextViewExtended,
                newArtist,
                mRealTextSet,
                mTempTextSet,
                mTempTextView,
                mTempTextViewExtended,
                mAmbientIndicationCollapsedContainer,
            )
        val sharedOnEndListener =
            AmbientIndicationAnimationHelper.runSongChangeContentSlideSharedOnEndListener(
                animationEndCounter,
                finalSwapAction,
            )
        val iconTransitionUpdateListener =
            AmbientIndicationAnimationHelper.songChangeIconIntroUpdateListener(
                mTempTextViewExtended,
                sharedOnEndListener,
            )
        val tempSetUpdateListener =
            AmbientIndicationAnimationHelper.songChangeTempTextSlideUpdateListener(
                mTempTextView,
                iconTransitionUpdateListener,
                sharedOnEndListener,
            )
        AmbientIndicationAnimationUtils.animateTranslationY(
            mTempTextSet,
            0.0f,
            tempSetUpdateListener,
            sharedOnEndListener,
            springForce,
        )
    }

    private fun performCollapseAnimationContinuation(hadLoadedArt: Boolean) {
        if (mIsCurrentlyInExpandedState) return
        animateCollapsedContainerTranslationX()
        animateActionContainerTranslationX()
        AmbientIndicationAnimationHelper.animateActionButtonsAlphaWithSpring(
            mAmbientIndicationLikeContainer,
            mAmbientIndicationLikeIcon,
            mAmbientIndicationActionContainer,
            0.0f,
            0.75f,
        )
        AmbientIndicationAnimationHelper.animateActionButtonsAlphaWithSpring(
            mAmbientIndicationPlayContainer,
            mAmbientIndicationPlayIcon,
            mAmbientIndicationActionContainer,
            0.0f,
            0.75f,
        )
        val drawable = context.getDrawable(R.drawable.ic_now_playing_lockscreen)
        drawable?.mutate()

        val imageView = mIconView
        val background = imageView.background
        if (hadLoadedArt) {
            if (background != null) {
                val hasTriggeredIconSwap = AtomicBoolean(false)
                drawable?.alpha = 0
                imageView.setImageDrawable(drawable)
                AmbientIndicationAnimationUtils.animateDrawableAlpha(
                    background,
                    imageView,
                    0,
                    AmbientIndicationAnimationHelper
                        .animateIconTransitionCollapseWithArtUpdateListener(
                            imageView,
                            hasTriggeredIconSwap,
                        ),
                    AmbientIndicationAnimationHelper.animateBackgroundArtworkInCollapseEndAction(
                        imageView,
                        2,
                    ),
                    AmbientIndicationAnimationUtils.defaultEffectsSpec,
                )
            }
        } else {
            if (background != null) {
                AmbientIndicationAnimationUtils.animateDrawableAlpha(
                    background,
                    imageView,
                    0,
                    null,
                    AmbientIndicationAnimationHelper.animateBackgroundArtworkInCollapseEndAction(
                        imageView,
                        1,
                    ),
                    AmbientIndicationAnimationUtils.defaultEffectsSpec,
                )
            }
            val currentDrawable = imageView.drawable
            if (currentDrawable != null && drawable != null) {
                AmbientIndicationAnimationUtils.animateDrawableAlpha(
                    currentDrawable,
                    imageView,
                    0,
                    AmbientIndicationAnimationHelper
                        .animateIconTransitionExpandNoIconUpdateListener(imageView, drawable),
                    AmbientIndicationAnimationHelper.animateIconTransitionEnd,
                    AmbientIndicationAnimationUtils.fastEffectsSpec,
                )
            }
        }

        val bgImageView = mAmbientIndicationContainerBackground
        AmbientIndicationAnimationUtils.animateAlpha(
            bgImageView,
            0.0f,
            AmbientIndicationAnimationHelper.animateBackgroundArtworkInCollapseUpdateListener,
            AmbientIndicationAnimationHelper.animateBackgroundArtworkInCollapseEndAction(
                bgImageView,
                0,
            ),
            AmbientIndicationAnimationUtils.defaultEffectsSpec,
        )
        val springForce = AmbientIndicationAnimationUtils.defaultSpatialSpec
        val scaleAnim = SpringAnimation(bgImageView, DynamicAnimation.SCALE_X, 1.0f)
        scaleAnim.spring = AmbientIndicationAnimationUtils.copySpringForce(springForce, 1.0f)
        scaleAnim.start()
    }
}
