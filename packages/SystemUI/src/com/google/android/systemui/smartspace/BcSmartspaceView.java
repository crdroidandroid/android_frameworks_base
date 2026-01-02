package com.google.android.systemui.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceTargetEvent;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Debug;
import android.os.Handler;
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
import androidx.viewpager.widget.ViewPager;
import androidx.viewpager2.widget.ViewPager2;

import com.android.launcher3.icons.GraphicsUtils;
import com.android.systemui.plugins.BcSmartspaceConfigPlugin;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.plugins.FalsingManager;

import com.google.android.systemui.smartspace.CardPagerAdapter;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLogger;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggerUtil;
import com.google.android.systemui.smartspace.logging.BcSmartspaceCardLoggingInfo;
import com.google.android.systemui.smartspace.logging.BcSmartspaceSubcardLoggingInfo;
import com.google.android.systemui.smartspace.uitemplate.BaseTemplateCard;

import com.android.systemui.res.R;

import java.lang.invoke.VarHandle;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class BcSmartspaceView extends FrameLayout implements BcSmartspaceDataPlugin.SmartspaceTargetListener, BcSmartspaceDataPlugin.SmartspaceView {
    public static final boolean DEBUG = Log.isLoggable("BcSmartspaceView", 3);
    public CardAdapter mAdapter;
    public final ContentObserver mAodObserver;
    public Handler mBgHandler;
    public int mCardPosition;
    public BcSmartspaceConfigPlugin mConfigProvider;
    public BcSmartspaceDataPlugin mDataProvider;
    public boolean mHasPerformedLongPress;
    public boolean mHasPostedLongPress;
    public boolean mIsAodEnabled;
    public final Set<String> mLastReceivedTargets;
    public final Runnable mLongPressCallback;
    public PageIndicator mPageIndicator;
    public PagerDots mPagerDots;
    public List mPendingTargets;
    public RecyclerView.ViewHolder mPreInflatedViewHolder;
    public float mPreviousDozeAmount;
    public final RecyclerView.RecycledViewPool mRecycledViewPool;
    public int mScrollState;
    public boolean mSplitShadeEnabled;
    public Integer mSwipedCardPosition;
    public ViewPager mViewPager;
    public ViewPager2 mViewPager2;
    public final ViewPager2.OnPageChangeCallback mViewPager2OnPageChangeCallback;
    public final ViewPager.OnPageChangeListener mViewPagerOnPageChangeListener;

    public final class ViewPager2OnPageChangeCallback extends ViewPager2.OnPageChangeCallback {
        @Override
        public final void onPageScrollStateChanged(int state) {
            Integer num;
            SmartspaceCard cardAtPosition;
            mScrollState = state;
            if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                mSwipedCardPosition = Integer.valueOf(mViewPager2.getCurrentItem());
            }
            if (state == ViewPager2.SCROLL_STATE_IDLE) {
                if (mConfigProvider.isSwipeEventLoggingEnabled() && (num = mSwipedCardPosition) != null && num.intValue() != mViewPager2.getCurrentItem() && (cardAtPosition = mAdapter.getCardAtPosition(mSwipedCardPosition.intValue())) != null) {
                    BcSmartspaceCardLogger.log(BcSmartspaceEvent.SMARTSPACE_CARD_SWIPE, cardAtPosition.getLoggingInfo());
                }
                mSwipedCardPosition = null;
            }
        }

        @Override
        public final void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
            setSelectedDot(positionOffset, position);
        }

        @Override
        public final void onPageSelected(int position) {
            setSelectedDot(0.0f, position);
            onViewPagerPageSelected(BcSmartspaceView.this, position);
        }
    }

    public final class ViewPagerOnPageChangeListener implements ViewPager.OnPageChangeListener {
        @Override
        public final void onPageScrollStateChanged(int state) {
            Integer num;
            SmartspaceCard cardAtPosition;
            mScrollState = state;
            if (state == ViewPager.SCROLL_STATE_DRAGGING) {
                mSwipedCardPosition = mViewPager.getCurrentItem();
            }
            if (state == ViewPager.SCROLL_STATE_IDLE) {
                if (mConfigProvider.isSwipeEventLoggingEnabled() && (num = mSwipedCardPosition) != null && num.intValue() != mViewPager.getCurrentItem() && (cardAtPosition = mAdapter.getCardAtPosition(mSwipedCardPosition.intValue())) != null) {
                    BcSmartspaceCardLogger.log(BcSmartspaceEvent.SMARTSPACE_CARD_SWIPE, cardAtPosition.getLoggingInfo());
                }
                mSwipedCardPosition = null;
                if (mPendingTargets != null) {
                    onSmartspaceTargetsUpdated(mPendingTargets);
                }
            }
        }

        @Override
        public final void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
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
            view.logSmartspaceEvent(currentTarget, view.mCardPosition, BcSmartspaceEvent.SMARTSPACE_CARD_SEEN);
        }
        if (view.mDataProvider == null) {
            Log.w("BcSmartspaceView", "Cannot notify target hidden/shown smartspace events: data provider null");
            return;
        }
        if (previousTarget == null) {
            Log.w("BcSmartspaceView", "Cannot notify target hidden smartspace event: previous target is null.");
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
            Log.w("BcSmartspaceView", "Cannot notify target shown smartspace event: shown card smartspace target null.");
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
        mCardPosition = 0;
        mPreviousDozeAmount = 0.0f;
        mScrollState = 0;
        mSplitShadeEnabled = false;
        mAodObserver = new ContentObserver(new Handler()) {
            @Override
            public final void onChange(boolean selfChange) {
                mIsAodEnabled = Settings.Secure.getIntForUser(getContext().getContentResolver(), "doze_always_on", 0, getContext().getUserId()) == 1;
            }
        };
        mViewPager2OnPageChangeCallback = new ViewPager2OnPageChangeCallback();
        mViewPagerOnPageChangeListener = new ViewPagerOnPageChangeListener();
        mLongPressCallback =
                () -> {
                    if (mViewPager2 != null && !mHasPerformedLongPress) {
                        mHasPerformedLongPress = true;
                        if (mViewPager2.performLongClick()) {
                            mViewPager2.setPressed(false);
                            getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    }
                };
        getContext().getTheme().applyStyle(R.style.DefaultSmartspaceView, false);
    }

    public final void cancelScheduledLongPress() {
        if (mViewPager2 != null && mHasPostedLongPress) {
            mHasPostedLongPress = false;
            mViewPager2.removeCallbacks(mLongPressCallback);
        }
    }

    public int getCurrentCardTopPadding() {
        int position = getSelectedPage();
        BcSmartspaceCard legacyCard = mAdapter.getLegacyCardAtPosition(position);
        if (legacyCard != null) {
            return legacyCard.getPaddingTop();
        }
        BaseTemplateCard templateCard = mAdapter.getTemplateCardAtPosition(position);
        if (templateCard != null) {
            return templateCard.getPaddingTop();
        }
        BcSmartspaceRemoteViewsCard remoteViewsCard =
                mAdapter.getRemoteViewsCardAtPosition(position);
        if (remoteViewsCard != null) {
            return remoteViewsCard.getPaddingTop();
        }
        return 0;
    }

    @Override
    public final int getSelectedPage() {
        int i = mViewPager != null ? mViewPager.getCurrentItem() : 0;
        return mViewPager2 != null ? mViewPager2.getCurrentItem() : i;
    }

    public final boolean handleTouchOverride(MotionEvent event, Predicate<MotionEvent> touchHandler) {
        boolean onTouchEvent;
        if (mViewPager2 != null) {
            int action = event.getAction();
            if (action == 0) {
                mHasPerformedLongPress = false;
                if (mViewPager2.isLongClickable()) {
                    cancelScheduledLongPress();
                    mHasPostedLongPress = true;
                    mViewPager2.postDelayed(mLongPressCallback, ViewConfiguration.getLongPressTimeout());
                }
            } else if (action == 1 || action == 3) {
                cancelScheduledLongPress();
            }

            if (mHasPerformedLongPress) {
                cancelScheduledLongPress();
                return true;
            }

            if (touchHandler.test(event)) {
                cancelScheduledLongPress();
                return true;
            }
        }
        return false;
    }

    public final void logSmartspaceEvent(SmartspaceTarget target, int rank, BcSmartspaceEvent event) {
        int receivedLatencyMillis;
        if (event == BcSmartspaceEvent.SMARTSPACE_CARD_RECEIVED) {
            try {
                receivedLatencyMillis = (int) Instant.now().minusMillis(target.getCreationTimeMillis()).toEpochMilli();
            } catch (ArithmeticException | DateTimeException e) {
                Log.e("BcSmartspaceView", "received_latency_millis will be -1 due to exception ", e);
                receivedLatencyMillis = -1;
            }
        } else {
            receivedLatencyMillis = 0;
        }
        boolean hasValidTemplate = BcSmartspaceCardLoggerUtil.containsValidTemplateType(target.getTemplateData());
        BcSmartspaceCardLoggingInfo.Builder loggingInfoBuilder =
                new BcSmartspaceCardLoggingInfo.Builder()
                        .setInstanceId(InstanceId.create(target))
                        .setFeatureType(target.getFeatureType())
                        .setDisplaySurface(
                                BcSmartSpaceUtil.getLoggingDisplaySurface(
                                        mAdapter.getUiSurface(), mAdapter.getDozeAmount()))
                        .setRank(rank)
                        .setCardinality(mAdapter.getCount())
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
        if (mViewPager != null) {
            if (mAdapter instanceof CardPagerAdapter) {
                mViewPager.setAdapter((CardPagerAdapter) mAdapter);
                mViewPager.addOnPageChangeListener(mViewPagerOnPageChangeListener);
                if (mPagerDots != null) {
                    mPagerDots.setNumPages(mAdapter.getCount(), isLayoutRtl());
                }
                if (TextUtils.equals(mAdapter.getUiSurface(), BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD)) {
                    try {
                        if (mBgHandler == null) {
                            throw new IllegalStateException("Must set background handler to avoid making binder calls on main thread");
                        }
                        mBgHandler.post(
                                () -> {
                                    ContentResolver resolver = getContext().getContentResolver();
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
                if (mDataProvider != null) {
                    registerDataProvider(mDataProvider);
                    return;
                }
                return;
            }
        }
        if (mViewPager2 != null) {
            if (mAdapter instanceof CardRecyclerViewAdapter) {
                mViewPager2.setAdapter((CardRecyclerViewAdapter) mAdapter);
                mViewPager2.registerOnPageChangeCallback(mViewPager2OnPageChangeCallback);
                if (mPagerDots != null) {
                }
                if (TextUtils.equals(mAdapter.getUiSurface(), BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD)) {
                }
                if (mDataProvider != null) {
                }
            }
        }
        Log.w("BcSmartspaceView", "Unable to attach the view pager adapter");
        if (mPagerDots != null) {
        }
        if (TextUtils.equals(mAdapter.getUiSurface(), BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD)) {
        }
        if (mDataProvider != null) {
        }
    }

    @Override
    public final void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mBgHandler == null) {
            throw new IllegalStateException("Must set background handler to avoid making binder calls on main thread");
        }
        mBgHandler.post(
                () -> getContext().getContentResolver().unregisterContentObserver(mAodObserver));
        if (mViewPager != null) {
            mViewPager.removeOnPageChangeListener(mViewPagerOnPageChangeListener);
        } else if (mViewPager2 != null) {
            mViewPager2.unregisterOnPageChangeCallback(mViewPager2OnPageChangeCallback);
        }
        if (mDataProvider != null) {
            mDataProvider.unregisterListener(this);
        }
    }

    @Override
    public final void onFinishInflate() {
        super.onFinishInflate();
        View pager = findViewById(R.id.smartspace_card_pager);
        if (pager instanceof ViewPager) {
            mViewPager = (ViewPager) pager;
            mAdapter = new CardPagerAdapter(this, mConfigProvider);
        } else {
            if (!(pager instanceof ViewPager2)) {
                throw new IllegalStateException("smartspace_card_pager is an invalid view type");
            }
            mViewPager2 = (ViewPager2) pager;
            mAdapter = new CardRecyclerViewAdapter(this, mConfigProvider);
            if (mViewPager2 != null) {
                CardRecyclerViewAdapter cardRecyclerViewAdapter = new CardRecyclerViewAdapter(this, mConfigProvider);
                cardRecyclerViewAdapter.setTargets(Collections.EMPTY_LIST, null);
                if (cardRecyclerViewAdapter.smartspaceTargets.size() > 0) {
                    RecyclerView recyclerView = (RecyclerView) mViewPager2.getChildAt(0);
                    recyclerView.setRecycledViewPool(mRecycledViewPool);
                    mPreInflatedViewHolder = cardRecyclerViewAdapter.createViewHolder(recyclerView, cardRecyclerViewAdapter.getItemViewType(0));
                }
            }
        }
        View indicator = findViewById(R.id.smartspace_page_indicator);
        if (indicator instanceof PagerDots) {
            mPagerDots = (PagerDots) indicator;
        }
        if (mPagerDots != null) {
            int paddingStart =
                    getResources().getDimensionPixelSize(R.dimen.non_remoteviews_card_padding_start);
            mPagerDots.setPaddingRelative(paddingStart, mPagerDots.getPaddingTop(), mPagerDots.getPaddingEnd(), mPagerDots.getPaddingBottom());
        }
    }

    @Override
    public final boolean onInterceptTouchEvent(MotionEvent event) {
        if (mViewPager2 == null) {
            return super.onInterceptTouchEvent(event);
        }
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
        int desiredHeight = getContext().getResources().getDimensionPixelSize(R.dimen.enhanced_smartspace_height);
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

    @Override
    public final void onSmartspaceTargetsUpdated(List<? extends Parcelable> targets) {
        List<SmartspaceTarget> smartspaceTargets =
                targets.stream()
                        .filter(t -> t instanceof SmartspaceTarget)
                        .map(t -> (SmartspaceTarget) t)
                        .collect(Collectors.toList());
        if (DEBUG) {
            Log.d("BcSmartspaceView", "@" + Integer.toHexString(hashCode()) + ", onTargetsAvailable called. Callers = " + Debug.getCallers(5));
            StringBuilder sb = new StringBuilder("    targets.size() = ");
            sb.append(targets.size());
            Log.d("BcSmartspaceView", sb.toString());
            Log.d("BcSmartspaceView", "    targets = " + targets.toString());
        }
        if (mViewPager != null && mScrollState != 0 && mAdapter.getCount() > 1 && mViewPager != null) {
            mPendingTargets = smartspaceTargets;
            return;
        }
        mPendingTargets = null;
        boolean isRtl = isLayoutRtl();
        int selectedPage = getSelectedPage();
        if (isRtl && (mAdapter instanceof CardPagerAdapter)) {
            Collections.reverse(smartspaceTargets);
        }
        View templateCardAtPosition = mAdapter.getTemplateCardAtPosition(selectedPage);
        BcSmartspaceCard legacyCardAtPosition = mAdapter.getLegacyCardAtPosition(selectedPage);
        BcSmartspaceRemoteViewsCard remoteViewsCardAtPosition = mAdapter.getRemoteViewsCardAtPosition(selectedPage);
        if (templateCardAtPosition == null) {
            templateCardAtPosition = legacyCardAtPosition != null ? legacyCardAtPosition : remoteViewsCardAtPosition;
        }
        View cardAtPosition = templateCardAtPosition;
        int count = mAdapter.getCount();
        CardAdapter cardAdapter = mAdapter;
        if (!(cardAdapter instanceof CardRecyclerViewAdapter)) {
            cardAdapter.setTargets(smartspaceTargets);
            setTargets(isRtl, selectedPage, cardAtPosition, count);
            return;
        }
        ((CardRecyclerViewAdapter) cardAdapter).setTargets(smartspaceTargets, () -> {
            setTargets(isRtl, selectedPage, cardAtPosition, count);
        });
    }

    @Override
    public final boolean onTouchEvent(MotionEvent event) {
        if (mViewPager2 == null) {
            return super.onTouchEvent(event);
        }
        return handleTouchOverride(event, (ev) -> mViewPager2.onTouchEvent(ev));
    }

    @Override
    public final void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (mDataProvider != null) {
            mDataProvider.getEventNotifier().notifySmartspaceEvent(new SmartspaceTargetEvent.Builder(isVisible ? 6 : 7).build());
        }
        if (mViewPager == null || mScrollState == 0) {
            return;
        }
        mScrollState = 0;
        if (mPendingTargets != null) {
            onSmartspaceTargetsUpdated(mPendingTargets);
        }
    }

    @Override
    public final void registerConfigProvider(BcSmartspaceConfigPlugin configProvider) {
        mConfigProvider = configProvider;
        mAdapter.setConfigProvider(configProvider);
    }

    @Override
    public final void registerDataProvider(BcSmartspaceDataPlugin dataProvider) {
        if (mDataProvider != null) {
            mDataProvider.unregisterListener(this);
        }
        mDataProvider = dataProvider;
        mDataProvider.registerListener(this);
        mAdapter.setDataProvider(mDataProvider);
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
        mAdapter.setBgHandler(handler);
    }

    @Override
    public final void setDozeAmount(float dozeAmount) {
        List<SmartspaceTarget> previousTargets = mAdapter.getSmartspaceTargets();
        mAdapter.setDozeAmount(dozeAmount);
        if (!mAdapter.getSmartspaceTargets().isEmpty()) {
            BcSmartspaceTemplateDataUtils.updateVisibility(this, View.VISIBLE);
        }
        float alpha = 1.0f;
        if (mAdapter.getHasAodLockscreenTransition()) {
            if (dozeAmount == mPreviousDozeAmount) {
                alpha = getAlpha();
            } else if (mPreviousDozeAmount > dozeAmount) {
                alpha = 1.0f - dozeAmount;
            } else {
                alpha = dozeAmount;
            }
            float threshold = 0.36f;
            if (alpha < threshold) {
                alpha = (threshold - alpha) / threshold;
            } else {
                alpha = (alpha - threshold) / 0.64f;
            }
        } else {
            alpha = 1.0f;
        }
        setAlpha(alpha);
        if (mPagerDots != null) {
            mPagerDots.setNumPages(mAdapter.getCount(), isLayoutRtl());
            mPagerDots.setAlpha(alpha);
            if (mPagerDots.getVisibility() != View.GONE) {
                if (dozeAmount == 1.0f) {
                    BcSmartspaceTemplateDataUtils.updateVisibility(mPagerDots, View.INVISIBLE);
                } else {
                    BcSmartspaceTemplateDataUtils.updateVisibility(mPagerDots, View.VISIBLE);
                }
            }
        }
        mPreviousDozeAmount = dozeAmount;
        if (mAdapter.getHasDifferentTargets() && mAdapter.getSmartspaceTargets() != previousTargets && mAdapter.getCount() > 0) {
            if (mAdapter instanceof CardRecyclerViewAdapter) {
                setSelectedPage(0);
            } else {
                setSelectedPage(isLayoutRtl() ? mAdapter.getCount() - 1 : 0);
            }
        }
        int displaySurface = BcSmartSpaceUtil.getLoggingDisplaySurface(mAdapter.getUiSurface(), mAdapter.getDozeAmount());
        if (displaySurface == -1) {
            return;
        }
        if (displaySurface != 3 || mIsAodEnabled) {
            if (DEBUG) {
                Log.d("BcSmartspaceView", "@" + Integer.toHexString(hashCode()) + ", setDozeAmount: Logging SMARTSPACE_CARD_SEEN, currentSurface = " + displaySurface);
            }
            SmartspaceTarget target = mAdapter.getTargetAtPosition(mCardPosition);
            if (target == null) {
                Log.w("BcSmartspaceView", "Current card is not present in the Adapter; cannot log.");
            } else {
                logSmartspaceEvent(target, mCardPosition, BcSmartspaceEvent.SMARTSPACE_CARD_SEEN);
            }
        }
    }

    @Override
    public final void setDozing(boolean dozing) {
        if (!dozing && mSplitShadeEnabled && mAdapter.getHasAodLockscreenTransition() && mAdapter.getLockscreenTargets().isEmpty()) {
            BcSmartspaceTemplateDataUtils.updateVisibility(this, View.GONE);
        }
    }

    @Override
    public final void setFalsingManager(FalsingManager falsingManager) {
        BcSmartSpaceUtil.sFalsingManager = falsingManager;
    }

    @Override
    public final void setHorizontalPaddings(int padding) {
        if (mPagerDots != null) {
            mPagerDots.setPaddingRelative(padding, mPagerDots.getPaddingTop(), padding, mPagerDots.getPaddingBottom());
        }
        mAdapter.setNonRemoteViewsHorizontalPadding(padding);
    }

    @Override
    public final void setKeyguardBypassEnabled(boolean enabled) {
        mAdapter.setKeyguardBypassEnabled(enabled);
    }

    @Override
    public final void setMediaTarget(SmartspaceTarget target) {
        if (!(mAdapter instanceof CardRecyclerViewAdapter)) {
            mAdapter.setMediaTarget(target);
            return;
        }
        CardRecyclerViewAdapter cardRecyclerViewAdapter = (CardRecyclerViewAdapter) mAdapter;
        cardRecyclerViewAdapter.mediaTargets.clear();
        if (target != null) {
            cardRecyclerViewAdapter.mediaTargets.add(target);
        }
        cardRecyclerViewAdapter.updateTargetVisibility(null, true);
    }

    @Override
    public final void setOnLongClickListener(View.OnLongClickListener listener) {
        if (mViewPager != null) {
            mViewPager.setOnLongClickListener(listener);
            return;
        }
        if (mViewPager2 != null) {
            mViewPager2.setOnLongClickListener(listener);
        }
    }

    @Override
    public final void setPrimaryTextColor(int color) {
        mAdapter.setPrimaryTextColor(color);
        if (mPagerDots != null) {
            mPagerDots.primaryColor = color;
            mPagerDots.paint.setColor(color);
            mPagerDots.invalidate();
        }
    }

    @Override
    public final void setScreenOn(boolean screenOn) {
        if (mViewPager != null && mScrollState != 0) {
            mScrollState = 0;
            if (mPendingTargets != null) {
                onSmartspaceTargetsUpdated(mPendingTargets);
            }
        }
        mAdapter.setScreenOn(screenOn);
    }

    public final void setSelectedDot(float f, int i) {
        if (mPagerDots != null) {
            if (i < 0) {
                mPagerDots.getClass();
                return;
            }
            if (i >= mPagerDots.numPages) {
                return;
            }
            mPagerDots.currentPositionIndex = i;
            mPagerDots.currentPositionOffset = f;
            mPagerDots.invalidate();
            if (f >= 0.5d) {
                i++;
            }
            mPagerDots.updateCurrentPageIndex(i);
        }
    }

    public final void setSelectedPage(int i) {
        if (mViewPager != null) {
            mViewPager.setCurrentItem(i, false);
        } else if (mViewPager2 != null) {
            mViewPager2.post(() -> {
                mViewPager2.setCurrentItem(i, false);
            });
        }
        setSelectedDot(0.0f, i);
    }

    @Override
    public final void setSplitShadeEnabled(boolean enabled) {
        mSplitShadeEnabled = enabled;
    }

    public final void setTargets(boolean z, int i, View view, int i2) {
        int count = mAdapter.getCount();
        if (mPagerDots != null) {
            mPagerDots.setNumPages(count, z);
        }
        if (z && (mAdapter instanceof CardPagerAdapter)) {
            setSelectedPage(Math.max(0, Math.min(count - 1, count - (i2 - i))));
        } else if (mAdapter instanceof CardRecyclerViewAdapter) {
            setSelectedPage(Math.max(0, Math.min(i, count - 1)));
        }
        for (int i3 = 0; i3 < count; i3++) {
            SmartspaceTarget targetAtPosition = mAdapter.getTargetAtPosition(i3);
            if (!mLastReceivedTargets.contains(targetAtPosition.getSmartspaceTargetId())) {
                logSmartspaceEvent(targetAtPosition, i3, BcSmartspaceEvent.SMARTSPACE_CARD_RECEIVED);
                SmartspaceTargetEvent.Builder builder = new SmartspaceTargetEvent.Builder(8);
                builder.setSmartspaceTarget(targetAtPosition);
                SmartspaceAction baseAction = targetAtPosition.getBaseAction();
                if (baseAction != null) {
                    builder.setSmartspaceActionId(baseAction.getId());
                }
                mDataProvider.getEventNotifier().notifySmartspaceEvent(builder.build());
            }
        }
        mLastReceivedTargets.clear();
        mLastReceivedTargets.addAll((Collection) mAdapter.getSmartspaceTargets().stream()
            .map(target -> ((SmartspaceTarget) target).getSmartspaceTargetId())
            .collect(Collectors.toList()));
    }

    @Override
    public final void setTimeChangedDelegate(BcSmartspaceDataPlugin.TimeChangedDelegate delegate) {
        mAdapter.setTimeChangedDelegate(delegate);
    }

    @Override
    public final void setUiSurface(String uiSurface) {
        if (isAttachedToWindow()) {
            throw new IllegalStateException("Must call before attaching view to window.");
        }
        if (uiSurface == BcSmartspaceDataPlugin.UI_SURFACE_HOME_SCREEN) {
            getContext().getTheme().applyStyle(R.style.LauncherSmartspaceView, true);
        }
        mAdapter.setUiSurface(uiSurface);
    }
}
