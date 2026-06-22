package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceTargetEvent;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.android.systemui.plugins.BcSmartspaceConfigPlugin;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.res.R;

import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLogger;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggerUtil;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;
import com.google.android.systemui.smartspace.logging.BcSmartspaceSubcardLoggingInfo;
import com.google.android.systemui.smartspace.uitemplate.BaseTemplateCard;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class BcSmartspaceView extends FrameLayout
        implements BcSmartspaceDataPlugin.SmartspaceTargetListener,
                BcSmartspaceDataPlugin.SmartspaceView {
    public static final boolean DEBUG = Log.isLoggable("BcSmartspaceView", 3);
    public CardRecyclerViewAdapter mAdapter;
    public final ContentObserver mAodObserver;
    public final ContentObserver mBackgroundToggleObserver;
    public Handler mBgHandler;
    public int mCardPosition;
    public BcSmartspaceConfigPlugin mConfigProvider;
    public BcSmartspaceDataPlugin mDataProvider;
    public boolean mHasPerformedLongPress;
    public boolean mHasPostedLongPress;
    public float mInitialTouchX;
    public float mInitialTouchY;
    public boolean mIsAodEnabled;
    public boolean mIsBackgroundEnabled;
    public final Set<String> mLastReceivedTargets;
    public final Runnable mLongPressCallback;
    public PageIndicator mPageIndicator;
    public PagerDots mPagerDots;
    public RecyclerView.ViewHolder mPreInflatedViewHolder;
    public float mPreviousDozeAmount;
    public final RecyclerView.RecycledViewPool mRecycledViewPool;
    public int mScrollState;
    public boolean mSplitShadeEnabled;
    public Integer mSwipedCardPosition;
    public final int mTouchSlop;
    public ViewPager2 mViewPager2;
    public final ViewPager2.OnPageChangeCallback mViewPager2OnPageChangeCallback;

    public final class ViewPager2OnPageChangeCallback extends ViewPager2.OnPageChangeCallback {
        @Override
        public final void onPageScrollStateChanged(int state) {
            mScrollState = state;
            if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                mSwipedCardPosition = mViewPager2.getCurrentItem();
            }
            if (state == ViewPager2.SCROLL_STATE_IDLE) {
                if (mSwipedCardPosition != null
                        && mSwipedCardPosition != mViewPager2.getCurrentItem()
                        && mAdapter.viewHolders.get(mSwipedCardPosition) != null) {
                    BcSmartspaceCardLogger.log(
                            BcSmartspaceEvent.SMARTSPACE_CARD_SWIPE,
                            mAdapter.viewHolders.get(mSwipedCardPosition).card.getLoggingInfo());
                }
                mSwipedCardPosition = null;
            }
        }

        @Override
        public final void onPageScrolled(
                int position, float positionOffset, int positionOffsetPixels) {
            setSelectedDot(positionOffset, position);
        }

        @Override
        public final void onPageSelected(int position) {
            setSelectedDot(0.0f, position);
            onViewPagerPageSelected(BcSmartspaceView.this, position);
        }
    }

    public static void onViewPagerPageSelected(BcSmartspaceView view, int position) {
        SmartspaceTarget previousTarget = view.mAdapter.getTargetAtPosition(view.mCardPosition);
        view.mCardPosition = position;
        SmartspaceTarget currentTarget = view.mAdapter.getTargetAtPosition(position);
        if (currentTarget != null) {
            view.logSmartspaceEvent(
                    currentTarget, view.mCardPosition, BcSmartspaceEvent.SMARTSPACE_CARD_SEEN);
        }
        if (view.mDataProvider == null) {
            Log.w(
                    "BcSmartspaceView",
                    "Cannot notify target hidden/shown smartspace events: data provider null");
            return;
        }
        if (previousTarget == null) {
            Log.w(
                    "BcSmartspaceView",
                    "Cannot notify target hidden smartspace event: previous target is null.");
        } else {
            SmartspaceTargetEvent.Builder builder = new SmartspaceTargetEvent.Builder(3);
            builder.setSmartspaceTarget(previousTarget);
            SmartspaceAction baseAction = previousTarget.getBaseAction();
            if (baseAction != null) {
                builder.setSmartspaceActionId(baseAction.getId());
            }
            view.mDataProvider.getEventNotifier().notifySmartspaceEvent(builder.build());
        }
        if (currentTarget == null) {
            Log.w(
                    "BcSmartspaceView",
                    "Cannot notify target shown smartspace event: shown card smartspace target"
                            + " null.");
            return;
        }
        SmartspaceTargetEvent.Builder builder = new SmartspaceTargetEvent.Builder(2);
        builder.setSmartspaceTarget(currentTarget);
        SmartspaceAction baseAction = currentTarget.getBaseAction();
        if (baseAction != null) {
            builder.setSmartspaceActionId(baseAction.getId());
        }
        view.mDataProvider.getEventNotifier().notifySmartspaceEvent(builder.build());
    }

    public BcSmartspaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mConfigProvider = new DefaultBcSmartspaceConfigProvider();
        mRecycledViewPool = new RecyclerView.RecycledViewPool();
        mPreInflatedViewHolder = null;
        mLastReceivedTargets = new ArraySet<>();
        mIsAodEnabled = false;
        mIsBackgroundEnabled = false;
        mCardPosition = 0;
        mPreviousDozeAmount = 0.0f;
        mScrollState = 0;
        mSplitShadeEnabled = false;
        mAodObserver =
                new ContentObserver(new Handler()) {
                    @Override
                    public final void onChange(boolean selfChange) {
                        mIsAodEnabled =
                                Settings.Secure.getIntForUser(
                                                getContext().getContentResolver(),
                                                "doze_always_on",
                                                0,
                                                getContext().getUserId())
                                        == 1;
                    }
                };
        mBackgroundToggleObserver =
                new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override
                    public final void onChange(boolean selfChange) {
                        onBackgroundToggled();
                    }
                };
        mViewPager2OnPageChangeCallback = new ViewPager2OnPageChangeCallback();
        mLongPressCallback =
                () -> {
                    mHasPerformedLongPress = true;
                    if (mViewPager2.performLongClick()) {
                        mViewPager2.setPressed(false);
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                };
        getContext().getTheme().applyStyle(R.style.DefaultSmartspaceView, false);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public final void cancelScheduledLongPress() {
        if (mHasPostedLongPress) {
            mHasPostedLongPress = false;
            mViewPager2.removeCallbacks(mLongPressCallback);
        }
    }

    @Override
    public int getCurrentCardTopPadding() {
        BcSmartspaceCard legacyCard =
                mAdapter.getLegacyCardAtPosition(mViewPager2.getCurrentItem());
        if (legacyCard != null) {
            return legacyCard.getPaddingTop();
        }
        BaseTemplateCard templateCard =
                mAdapter.getTemplateCardAtPosition(mViewPager2.getCurrentItem());
        if (templateCard != null) {
            return templateCard.getPaddingTop();
        }
        CardRecyclerViewAdapter.ViewHolder viewHolder =
                mAdapter.viewHolders.get(mViewPager2.getCurrentItem());
        if (viewHolder != null && viewHolder.card instanceof BcSmartspaceRemoteViewsCard) {
            return ((BcSmartspaceRemoteViewsCard) viewHolder.card).getPaddingTop();
        }
        return 0;
    }

    @Override
    public final int getSelectedPage() {
        return mViewPager2.getCurrentItem();
    }

    public boolean handleTouchOverride(MotionEvent event, Predicate<MotionEvent> touchHandler) {
        boolean onTouchEvent = touchHandler.test(event);
        int action = event.getAction();
        if (action == 0) {
            mInitialTouchX = event.getX();
            mInitialTouchY = event.getY();
            mHasPerformedLongPress = false;
            if (mViewPager2.isLongClickable()) {
                cancelScheduledLongPress();
                mHasPostedLongPress = true;
                mViewPager2.postDelayed(
                        mLongPressCallback, ViewConfiguration.getLongPressTimeout());
            }
        } else if (action == 1) {
            cancelScheduledLongPress();
        } else if (action == 2) {
            if (Math.hypot(event.getX() - mInitialTouchX, event.getY() - mInitialTouchY)
                    > mTouchSlop) {
                cancelScheduledLongPress();
            }
            cancelScheduledLongPress();
        }

        if (mHasPerformedLongPress) {
            cancelScheduledLongPress();
            return true;
        }

        if (onTouchEvent) {
            cancelScheduledLongPress();
            return true;
        }
        return false;
    }

    public final void logSmartspaceEvent(
            SmartspaceTarget target, int rank, BcSmartspaceEvent event) {
        int receivedLatencyMillis;
        if (event == BcSmartspaceEvent.SMARTSPACE_CARD_RECEIVED) {
            try {
                receivedLatencyMillis =
                        (int)
                                Instant.now()
                                        .minusMillis(target.getCreationTimeMillis())
                                        .toEpochMilli();
            } catch (ArithmeticException | DateTimeException e) {
                Log.e(
                        "BcSmartspaceView",
                        "received_latency_millis will be -1 due to exception ",
                        e);
                receivedLatencyMillis = -1;
            }
        } else {
            receivedLatencyMillis = 0;
        }
        boolean hasValidTemplate =
                BcSmartspaceCardLoggerUtil.containsValidTemplateType(target.getTemplateData());
        BcSmartspaceCardLoggingInfo.Builder loggingInfoBuilder =
                new BcSmartspaceCardLoggingInfo.Builder()
                        .setInstanceId(InstanceId.create(target))
                        .setFeatureType(target.getFeatureType())
                        .setDisplaySurface(
                                BcSmartSpaceUtil.getLoggingDisplaySurface(
                                        mAdapter.uiSurface, mAdapter.dozeAmount))
                        .setRank(rank)
                        .setCardinality(mAdapter.smartspaceTargets.size())
                        .setReceivedLatency(receivedLatencyMillis)
                        .setUid(-1);
        BcSmartspaceSubcardLoggingInfo subcardInfo =
                hasValidTemplate
                        ? BcSmartspaceCardLoggerUtil.createSubcardLoggingInfo(
                                target.getTemplateData())
                        : BcSmartspaceCardLoggerUtil.createSubcardLoggingInfo(target);
        loggingInfoBuilder.setSubcardInfo(subcardInfo);
        loggingInfoBuilder.setDimensionalInfo(
                BcSmartspaceCardLoggerUtil.createDimensionalLoggingInfo(target.getTemplateData()));
        BcSmartspaceCardLoggingInfo loggingInfo =
                new BcSmartspaceCardLoggingInfo(loggingInfoBuilder);
        if (hasValidTemplate) {
            BcSmartspaceCardLoggerUtil.tryForcePrimaryFeatureTypeOrUpdateLogInfoFromTemplateData(
                    loggingInfo, target.getTemplateData());
        }
        BcSmartspaceCardLogger.log(event, loggingInfo);
    }

    @Override
    public final void onAttachedToWindow() {
        super.onAttachedToWindow();
        mViewPager2.setAdapter(mAdapter);
        mViewPager2.registerOnPageChangeCallback(mViewPager2OnPageChangeCallback);
        if (mPagerDots != null) {
            mPagerDots.setNumPages(mAdapter.smartspaceTargets.size(), isLayoutRtl());
        }
        if (mBgHandler == null) {
            throw new IllegalStateException(
                    "Must set background handler to avoid making binder calls on main thread");
        }
        ContentResolver resolver = getContext().getContentResolver();
        if (TextUtils.equals(
                mAdapter.uiSurface, BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD)) {
            try {
                mBgHandler.post(
                        () -> {
                            int userId = getContext().getUserId();
                            mIsAodEnabled =
                                    Settings.Secure.getIntForUser(
                                                    resolver, "doze_always_on", 0, userId)
                                            == 1;
                            resolver.registerContentObserver(
                                    Settings.Secure.getUriFor("doze_always_on"),
                                    false,
                                    mAodObserver,
                                    -1);
                        });
            } catch (Exception e) {
                Log.w("BcSmartspaceView", "Unable to register Doze Always on content observer.", e);
            }
        }
        try {
            mBgHandler.post(
                    () -> {
                        resolver.registerContentObserver(
                                Settings.Secure.getUriFor("smartspace_settings_background"),
                                false,
                                mBackgroundToggleObserver,
                                -1);
                    });
        } catch (Exception e) {
            Log.w(
                    "BcSmartspaceView",
                    "Unable to register Smartspace Background Settings observer.",
                    e);
        }
        onBackgroundToggled();
        if (mDataProvider != null) {
            registerDataProvider(mDataProvider);
        }
    }

    public final void onBackgroundToggled() {
        boolean z =
                Settings.Secure.getIntForUser(
                                getContext().getContentResolver(),
                                "smartspace_settings_background",
                                0,
                                getContext().getUserId())
                        == 1;
        if (mIsBackgroundEnabled == z) {
            return;
        }
        mIsBackgroundEnabled = z;
        mAdapter._isBackgroundEnabled = z;
        mAdapter.refreshCardBackground();
        mAdapter.refreshCardPaddings();
        mAdapter.updateCurrentTextColor();
    }

    @Override
    public final void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mBgHandler == null) {
            throw new IllegalStateException(
                    "Must set background handler to avoid making binder calls on main thread");
        }
        mBgHandler.post(
                () -> {
                    getContext().getContentResolver().unregisterContentObserver(mAodObserver);
                    getContext()
                            .getContentResolver()
                            .unregisterContentObserver(mBackgroundToggleObserver);
                });
        mViewPager2.unregisterOnPageChangeCallback(mViewPager2OnPageChangeCallback);
        if (mDataProvider != null) {
            mDataProvider.unregisterListener(this);
        }
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        View pager = findViewById(R.id.smartspace_card_pager);
        mViewPager2 = (ViewPager2) pager;
        mAdapter = new CardRecyclerViewAdapter(this, mConfigProvider);
        CardRecyclerViewAdapter cardRecyclerViewAdapter =
                new CardRecyclerViewAdapter(this, mConfigProvider);
        cardRecyclerViewAdapter.uiSurface = BcSmartspaceDataPlugin.UI_SURFACE_HOME_SCREEN;
        cardRecyclerViewAdapter.setTargets(Collections.EMPTY_LIST, null);
        if (cardRecyclerViewAdapter.smartspaceTargets.size() > 0) {
            RecyclerView recyclerView = (RecyclerView) mViewPager2.getChildAt(0);
            recyclerView.setRecycledViewPool(mRecycledViewPool);
            mPreInflatedViewHolder =
                    cardRecyclerViewAdapter.createViewHolder(
                            recyclerView, cardRecyclerViewAdapter.getItemViewType(0));
        }
        View indicator = findViewById(R.id.smartspace_page_indicator);
        if (indicator instanceof PagerDots) {
            mPagerDots = (PagerDots) indicator;
        }
        if (mPagerDots != null) {
            int paddingStart =
                    getResources()
                            .getDimensionPixelSize(R.dimen.non_remoteviews_card_padding_start);
            mPagerDots.setPaddingRelative(
                    paddingStart,
                    mPagerDots.getPaddingTop(),
                    mPagerDots.getPaddingEnd(),
                    mPagerDots.getPaddingBottom());
        }
    }

    @Override
    public final boolean onInterceptTouchEvent(MotionEvent event) {
        handleTouchOverride(event, (ev) -> mViewPager2.onInterceptTouchEvent(ev));
        return super.onInterceptTouchEvent(event) || mHasPerformedLongPress;
    }

    @Override
    public final void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (mPreInflatedViewHolder != null) {
            mRecycledViewPool.putRecycledView(mPreInflatedViewHolder);
            mPreInflatedViewHolder = null;
        }
        super.onLayout(changed, left, top, right, bottom);
    }

    @Override
    public final void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = View.MeasureSpec.getSize(heightMeasureSpec);
        int desiredHeight =
                getContext()
                        .getResources()
                        .getDimensionPixelSize(
                                com.android.systemui.customization.clocks.R.dimen
                                        .enhanced_smartspace_height);
        if (height <= 0 || height >= desiredHeight) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            setScaleX(1.0f);
            setScaleY(1.0f);
            resetPivot();
            return;
        }
        float scale = (float) height / desiredHeight;
        int width = (int) (MeasureSpec.getSize(widthMeasureSpec) / scale);
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(desiredHeight, MeasureSpec.EXACTLY));
        setScaleX(scale);
        setScaleY(scale);
        setPivotX(0.0f);
        setPivotY(desiredHeight / 2.0f);
    }

    // ???
    public final void onSmartspaceTargetsUpdated(
            List<? extends Parcelable> targets, Runnable runnable) {
        List<SmartspaceTarget> smartspaceTargets =
                targets.stream()
                        .filter(t -> t instanceof SmartspaceTarget)
                        .map(t -> (SmartspaceTarget) t)
                        .collect(Collectors.toList());
        if (DEBUG) {
            Log.d(
                    "BcSmartspaceView",
                    "@"
                            + Integer.toHexString(hashCode())
                            + ", onTargetsAvailable called. Callers = "
                            + Debug.getCallers(5));
            StringBuilder sb = new StringBuilder("    targets.size() = ");
            sb.append(targets.size());
            Log.d("BcSmartspaceView", sb.toString());
            Log.d("BcSmartspaceView", "    targets = " + targets.toString());
        }
        View templateCardAtPosition =
                mAdapter.getTemplateCardAtPosition(mViewPager2.getCurrentItem());
        View legacyCardAtPosition = mAdapter.getLegacyCardAtPosition(mViewPager2.getCurrentItem());
        CardRecyclerViewAdapter.ViewHolder viewHolder =
                (CardRecyclerViewAdapter.ViewHolder)
                        mAdapter.viewHolders.get(mViewPager2.getCurrentItem());
        SmartspaceCard smartspaceCard = viewHolder != null ? viewHolder.card : null;
        View view =
                smartspaceCard instanceof BcSmartspaceRemoteViewsCard
                        ? (BcSmartspaceRemoteViewsCard) smartspaceCard
                        : null;
        if (templateCardAtPosition == null) {
            templateCardAtPosition = legacyCardAtPosition != null ? legacyCardAtPosition : view;
        }
        Runnable updateTargetsRunnable =
                () -> {
                    int size = mAdapter.smartspaceTargets.size();
                    if (mPagerDots != null) {
                        mPagerDots.setNumPages(size, isLayoutRtl());
                    }
                    for (int index = 0; index < size; index++) {
                        SmartspaceTarget targetAtPosition = mAdapter.getTargetAtPosition(index);
                        if (!mLastReceivedTargets.contains(
                                targetAtPosition.getSmartspaceTargetId())) {
                            logSmartspaceEvent(
                                    targetAtPosition,
                                    index,
                                    BcSmartspaceEvent.SMARTSPACE_CARD_RECEIVED);
                            SmartspaceTargetEvent.Builder builder =
                                    new SmartspaceTargetEvent.Builder(8);
                            builder.setSmartspaceTarget(targetAtPosition);
                            SmartspaceAction baseAction = targetAtPosition.getBaseAction();
                            if (baseAction != null) {
                                builder.setSmartspaceActionId(baseAction.getId());
                            }
                            if (mDataProvider != null) {
                                mDataProvider
                                        .getEventNotifier()
                                        .notifySmartspaceEvent(builder.build());
                            }
                        }
                    }
                    mLastReceivedTargets.clear();
                    mLastReceivedTargets.addAll(
                            (Collection)
                                    mAdapter.smartspaceTargets.stream()
                                            .map(
                                                    obj ->
                                                            ((SmartspaceTarget) obj)
                                                                    .getSmartspaceTargetId())
                                            .collect(Collectors.toList()));
                    if (runnable != null) {
                        runnable.run();
                    }
                };
        mAdapter.setTargets(smartspaceTargets, updateTargetsRunnable);
    }

    @Override
    public final boolean onTouchEvent(MotionEvent event) {
        return handleTouchOverride(event, (ev) -> mViewPager2.onTouchEvent(ev));
    }

    @Override
    public final void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (mDataProvider != null) {
            mDataProvider
                    .getEventNotifier()
                    .notifySmartspaceEvent(
                            new SmartspaceTargetEvent.Builder(isVisible ? 6 : 7).build());
        }
    }

    @Override
    public final void registerConfigProvider(BcSmartspaceConfigPlugin configProvider) {
        mConfigProvider = configProvider;
        mAdapter.configProvider = configProvider;
    }

    @Override
    public final void registerDataProvider(BcSmartspaceDataPlugin dataProvider) {
        if (mDataProvider != null) {
            mDataProvider.unregisterListener(this);
        }
        mDataProvider = dataProvider;
        mDataProvider.registerListener(this);
        mAdapter.dataProvider = mDataProvider;
    }

    @Override
    public final void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        if (disallowIntercept) {
            cancelScheduledLongPress();
        }
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
    }

    @Override
    public final void setBgHandler(Handler handler) {
        mBgHandler = handler;
        mAdapter.bgHandler = handler;
    }

    @Override
    public final void setDozeAmount(float dozeAmount) {
        boolean z;
        float alpha = 1.0f;
        List<SmartspaceTarget> previousTargets = mAdapter.smartspaceTargets;
        mAdapter.dozeAmount = dozeAmount;
        mAdapter.transitioningTo =
                mAdapter.previousDozeAmount > dozeAmount
                        ? CardRecyclerViewAdapter.TransitionType.TO_LOCKSCREEN
                        : mAdapter.previousDozeAmount < dozeAmount
                                ? CardRecyclerViewAdapter.TransitionType.TO_AOD
                                : CardRecyclerViewAdapter.TransitionType.NOT_IN_TRANSITION;
        mAdapter.previousDozeAmount = dozeAmount;
        mAdapter.updateTargetVisibility(null, false);
        if (mAdapter.currentBackgroundDrawable != mAdapter.backgroundOutlineDrawable) {
            if (mAdapter.dozeAmount == 1.0f
                    || (mAdapter.dozeAmount >= 0.36f
                            && mAdapter.transitioningTo
                                    == CardRecyclerViewAdapter.TransitionType.TO_AOD)) {
                z = true;
                if (!z) {
                    mAdapter.currentBackgroundDrawable = mAdapter.backgroundOutlineDrawable;
                    mAdapter.refreshCardBackground();
                } else if (mAdapter.currentBackgroundDrawable == mAdapter.backgroundDrawable
                        && mAdapter.needToSetToLockscreenTargets()) {
                    mAdapter.currentBackgroundDrawable = mAdapter.backgroundDrawable;
                    mAdapter.refreshCardBackground();
                }
                mAdapter.updateCurrentTextColor();
                if (!mAdapter.smartspaceTargets.isEmpty()) {
                    BcSmartspaceTemplateDataUtils.updateVisibility(this, View.VISIBLE);
                }
                if (mAdapter.hasAodLockscreenTransition) {
                    alpha = 1.0f;
                } else {
                    if (dozeAmount == mPreviousDozeAmount) {
                        alpha = getAlpha();
                    } else {
                        float threshold =
                                mPreviousDozeAmount > dozeAmount ? 1.0f - dozeAmount : dozeAmount;
                        alpha =
                                threshold < 0.36f
                                        ? (0.36f - threshold) / 0.36f
                                        : (threshold - 0.36f) / 0.64f;
                    }
                }
                setAlpha(alpha);
                if (mPagerDots != null) {
                    mPagerDots.setNumPages(mAdapter.smartspaceTargets.size(), isLayoutRtl());
                    mPagerDots.setAlpha(alpha);
                    if (mPagerDots.getVisibility() != View.GONE) {
                        if (dozeAmount == 1.0f) {
                            BcSmartspaceTemplateDataUtils.updateVisibility(
                                    mPagerDots, View.INVISIBLE);
                        } else {
                            BcSmartspaceTemplateDataUtils.updateVisibility(
                                    mPagerDots, View.VISIBLE);
                        }
                    }
                }
                mPreviousDozeAmount = dozeAmount;
                if (mAdapter.hasDifferentTargets
                        && mAdapter.smartspaceTargets != previousTargets
                        && mAdapter.smartspaceTargets.size() > 0) {
                    mViewPager2.setCurrentItem(0, false);
                }
                int displaySurface =
                        BcSmartSpaceUtil.getLoggingDisplaySurface(
                                mAdapter.uiSurface, mAdapter.dozeAmount);
                if (displaySurface != -1) {
                    return;
                }
                if (displaySurface != 3 || mIsAodEnabled) {
                    if (DEBUG) {
                        Log.d(
                                "BcSmartspaceView",
                                "@"
                                        + Integer.toHexString(hashCode())
                                        + ", setDozeAmount: Logging SMARTSPACE_CARD_SEEN,"
                                        + " currentSurface = "
                                        + displaySurface);
                    }
                    SmartspaceTarget targetAtPosition = mAdapter.getTargetAtPosition(mCardPosition);
                    if (targetAtPosition == null) {
                        Log.w(
                                "BcSmartspaceView",
                                "Current card is not present in the Adapter; cannot log.");
                        return;
                    } else {
                        logSmartspaceEvent(
                                targetAtPosition,
                                mCardPosition,
                                BcSmartspaceEvent.SMARTSPACE_CARD_SEEN);
                        return;
                    }
                }
                return;
            }
        }
        z = false;
        if (mAdapter.currentBackgroundDrawable == mAdapter.backgroundDrawable) {}
        if (!z) {}
        mAdapter.updateCurrentTextColor();
        if (!mAdapter.smartspaceTargets.isEmpty()) {}
        if (mAdapter.hasAodLockscreenTransition) {}
        setAlpha(alpha);
        if (mPagerDots != null) {}
        mPreviousDozeAmount = dozeAmount;
        if (mAdapter.hasDifferentTargets) {
            mViewPager2.setCurrentItem(0, false);
        }
        int displaySurface =
                BcSmartSpaceUtil.getLoggingDisplaySurface(mAdapter.uiSurface, mAdapter.dozeAmount);
        if (displaySurface != -1) {}
    }

    @Override
    public final void setDozing(boolean dozing) {
        if (!dozing && mSplitShadeEnabled && mAdapter.hasAodLockscreenTransition) {
            if (((mAdapter.mediaTargets.isEmpty() || !mAdapter.keyguardBypassEnabled)
                            ? mAdapter._lockscreenTargets
                            : mAdapter.mediaTargets)
                    .isEmpty()) {
                BcSmartspaceTemplateDataUtils.updateVisibility(this, View.GONE);
            }
        }
    }

    @Override
    public final void setFalsingManager(FalsingManager falsingManager) {
        BcSmartSpaceUtil.sFalsingManager = falsingManager;
    }

    @Override
    public final void setHorizontalPaddings(int padding) {
        if (mPagerDots != null) {
            mPagerDots.setPaddingRelative(
                    padding, mPagerDots.getPaddingTop(), padding, mPagerDots.getPaddingBottom());
        }
        mAdapter.nonRemoteViewsHorizontalPadding = padding;
        if (mAdapter._isBackgroundEnabled) {
            return;
        }
        mAdapter.refreshCardPaddings();
    }

    @Override
    public final void setKeyguardBypassEnabled(boolean enabled) {
        mAdapter.keyguardBypassEnabled = enabled;
        mAdapter.updateTargetVisibility(null, false);
    }

    @Override
    public final void setMediaTarget(SmartspaceTarget target) {
        mAdapter.mediaTargets.clear();
        if (target != null) {
            mAdapter.mediaTargets.add(target);
        }
        mAdapter.updateTargetVisibility(null, true);
    }

    @Override
    public final void setOnLongClickListener(View.OnLongClickListener listener) {
        mViewPager2.setOnLongClickListener(listener);
    }

    @Override
    public final void setPrimaryTextColor(int color) {
        mAdapter.primaryTextColor = color;
        mAdapter.updateCurrentTextColor();
        if (mPagerDots != null) {
            mPagerDots.primaryColor = color;
            mPagerDots.paint.setColor(color);
            mPagerDots.invalidate();
        }
    }

    @Override
    public final void setScreenOn(boolean screenOn) {
        int size = mAdapter.viewHolders.size();
        for (int i = 0; i < size; i++) {
            SparseArray<CardRecyclerViewAdapter.ViewHolder> sparseArray = mAdapter.viewHolders;
            CardRecyclerViewAdapter.ViewHolder viewHolder =
                    (CardRecyclerViewAdapter.ViewHolder) sparseArray.get(sparseArray.keyAt(i));
            if (viewHolder != null) {
                viewHolder.card.setScreenOn(screenOn);
            }
        }
    }

    public final void setSelectedDot(float f, int i) {
        if (mPagerDots != null && i > 0 && i <= mPagerDots.numPages) {
            mPagerDots.currentPositionIndex = i;
            mPagerDots.currentPositionOffset = f;
            mPagerDots.invalidate();
            if (f >= 0.5d) {
                i++;
            }
            mPagerDots.updateCurrentPageIndex(i);
        }
    }

    // DOES NOT EXIST ???
    public final void setSelectedPage(int i) {
        mViewPager2.post(
                () -> {
                    mViewPager2.setCurrentItem(i, false);
                });
        setSelectedDot(0.0f, i);
    }

    @Override
    public final void setSplitShadeEnabled(boolean enabled) {
        mSplitShadeEnabled = enabled;
    }

    @Override
    public final void setTimeChangedDelegate(BcSmartspaceDataPlugin.TimeChangedDelegate delegate) {
        mAdapter.timeChangedDelegate = delegate;
    }

    @Override
    public final void setUiSurface(String uiSurface) {
        if (isAttachedToWindow()) {
            throw new IllegalStateException("Must call before attaching view to window.");
        }
        if (uiSurface == BcSmartspaceDataPlugin.UI_SURFACE_HOME_SCREEN) {
            getContext().getTheme().applyStyle(R.style.LauncherSmartspaceView, true);
        }
        mAdapter.uiSurface = uiSurface;
    }

    @Override
    public void onSmartspaceTargetsUpdated(List<? extends Parcelable> targets) {
        onSmartspaceTargetsUpdated(targets, null);
    }
}
