/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection;

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkState;
import static com.android.systemui.statusbar.notification.collection.GroupEntry.ROOT_ENTRY;
import static com.android.systemui.statusbar.notification.collection.coordinator.BundleCoordinator.debugBundleLog;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_BUILD_STARTED;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_FINALIZE_FILTERING;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_FINALIZING;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_GROUPING;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_GROUP_STABILIZING;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_IDLE;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_PRE_GROUP_FILTERING;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_RESETTING;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_SORTING;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_TRANSFORMING;

import static java.util.Objects.requireNonNull;

import android.annotation.MainThread;
import android.annotation.Nullable;
import android.app.Notification;
import android.os.Trace;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.annotation.VisibleForTesting;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.NotificationInteractionTracker;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.collection.coordinator.BundleCoordinator;
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeFinalizeFilterListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeSortListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeTransformGroupsListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState;
import com.android.systemui.statusbar.notification.collection.listbuilder.SemiStableSort;
import com.android.systemui.statusbar.notification.collection.listbuilder.SemiStableSort.StableOrder;
import com.android.systemui.statusbar.notification.collection.listbuilder.ShadeListBuilderHelper;
import com.android.systemui.statusbar.notification.collection.listbuilder.ShadeListBuilderLogger;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.DefaultNotifBundler;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.DefaultNotifStabilityManager;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Invalidator;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifBundler;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifComparator;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifStabilityManager;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Pluggable;
import com.android.systemui.statusbar.notification.collection.notifcollection.CollectionReadyForBuildListener;
import com.android.systemui.statusbar.notification.shared.NotificationBundleUi;
import com.android.systemui.statusbar.notification.stack.NotificationPriorityBucketKt;
import com.android.systemui.util.Assert;
import com.android.systemui.util.NamedListenerSet;
import com.android.systemui.util.time.SystemClock;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;

/**
 * The second half of {@link NotifPipeline}. Sits downstream of the NotifCollection and transforms
 * its "notification set" into the "shade list", the filtered, grouped, and sorted list of
 * notifications that are currently present in the notification shade.
 */
@MainThread
@SysUISingleton
@OptIn(markerClass = InternalNotificationsApi.class)
public class ShadeListBuilder implements Dumpable, PipelineDumpable {
    private final SystemClock mSystemClock;
    private final ShadeListBuilderLogger mLogger;
    private final NotificationInteractionTracker mInteractionTracker;
    private final DumpManager mDumpManager;
    // used exclusivly by ShadeListBuilder#notifySectionEntriesUpdated
    // TODO replace temp with collection pool for readability
    private final ArrayList<PipelineEntry> mTempSectionMembers = new ArrayList<>();
    private NotifPipelineFlags mFlags;
    private final boolean mAlwaysLogList;

    private List<PipelineEntry> mNotifList = new ArrayList<>();
    private List<PipelineEntry> mNewNotifList = new ArrayList<>();

    private final SemiStableSort mSemiStableSort = new SemiStableSort();
    private final StableOrder<PipelineEntry> mStableOrder = this::getStableOrderRank;
    private final PipelineState mPipelineState = new PipelineState();
    private final Map<String, GroupEntry> mGroups = new ArrayMap<>();
    private Collection<NotificationEntry> mAllEntries = Collections.emptyList();
    @Nullable
    private Collection<NotificationEntry> mPendingEntries = null;
    private int mIterationCount = 0;

    private final List<NotifFilter> mNotifPreGroupFilters = new ArrayList<>();
    private final List<NotifPromoter> mNotifPromoters = new ArrayList<>();
    private final List<NotifFilter> mNotifFinalizeFilters = new ArrayList<>();
    private final List<NotifComparator> mNotifComparators = new ArrayList<>();
    private final List<NotifSection> mNotifSections = new ArrayList<>();
    private NotifStabilityManager mNotifStabilityManager;
    private NotifBundler mNotifBundler;
    private Map<String, BundleEntry> mIdToBundleEntry = new HashMap<>();
    private final NamedListenerSet<OnBeforeTransformGroupsListener>
            mOnBeforeTransformGroupsListeners = new NamedListenerSet<>();
    private final NamedListenerSet<OnBeforeSortListener>
            mOnBeforeSortListeners = new NamedListenerSet<>();
    private final NamedListenerSet<OnBeforeFinalizeFilterListener>
            mOnBeforeFinalizeFilterListeners = new NamedListenerSet<>();
    private final NamedListenerSet<OnBeforeRenderListListener>
            mOnBeforeRenderListListeners = new NamedListenerSet<>();
    @Nullable
    private OnRenderListListener mOnRenderListListener;

    private List<PipelineEntry> mReadOnlyNotifList = Collections.unmodifiableList(mNotifList);
    private List<PipelineEntry> mReadOnlyNewNotifList = Collections.unmodifiableList(mNewNotifList);
    private final NotifPipelineChoreographer mChoreographer;

    private int mConsecutiveReentrantRebuilds = 0;
    @VisibleForTesting
    public static final int MAX_CONSECUTIVE_REENTRANT_REBUILDS = 3;
    private static final boolean DEBUG_FILTER = false;

    @Inject
    public ShadeListBuilder(
            DumpManager dumpManager,
            NotifPipelineChoreographer pipelineChoreographer,
            NotifPipelineFlags flags,
            NotificationInteractionTracker interactionTracker,
            ShadeListBuilderLogger logger,
            SystemClock systemClock
    ) {
        mSystemClock = systemClock;
        mLogger = logger;
        mFlags = flags;
        mAlwaysLogList = flags.isDevLoggingEnabled();
        mInteractionTracker = interactionTracker;
        mChoreographer = pipelineChoreographer;
        mDumpManager = dumpManager;
        setSectioners(Collections.emptyList());
    }

    /**
     * Attach the list builder to the NotifCollection. After this is called, it will start building
     * the notif list in response to changes to the colletion.
     */
    public void attach(NotifCollection collection) {
        Assert.isMainThread();
        mDumpManager.registerDumpable(TAG, this);
        collection.addCollectionListener(mInteractionTracker);
        collection.setBuildListener(mReadyForBuildListener);
        mChoreographer.addOnEvalListener(this::buildList);
    }

    /**
     * Registers the listener that's responsible for rendering the notif list to the screen. Called
     * At the very end of pipeline execution, after all other listeners and pluggables have fired.
     */
    public void setOnRenderListListener(OnRenderListListener onRenderListListener) {
        Assert.isMainThread();

        mPipelineState.requireState(STATE_IDLE);
        mOnRenderListListener = onRenderListListener;
    }

    void addOnBeforeTransformGroupsListener(OnBeforeTransformGroupsListener listener) {
        Assert.isMainThread();

        mPipelineState.requireState(STATE_IDLE);
        mOnBeforeTransformGroupsListeners.addIfAbsent(listener);
    }

    void addOnBeforeSortListener(OnBeforeSortListener listener) {
        Assert.isMainThread();

        mPipelineState.requireState(STATE_IDLE);
        mOnBeforeSortListeners.addIfAbsent(listener);
    }

    void addOnBeforeFinalizeFilterListener(OnBeforeFinalizeFilterListener listener) {
        Assert.isMainThread();

        mPipelineState.requireState(STATE_IDLE);
        mOnBeforeFinalizeFilterListeners.addIfAbsent(listener);
    }

    void addOnBeforeRenderListListener(OnBeforeRenderListListener listener) {
        Assert.isMainThread();

        mPipelineState.requireState(STATE_IDLE);
        mOnBeforeRenderListListeners.addIfAbsent(listener);
    }

    void addPreRenderInvalidator(Invalidator invalidator) {
        Assert.isMainThread();

        mPipelineState.requireState(STATE_IDLE);
        invalidator.setInvalidationListener(this::onPreRenderInvalidated);
    }

    void addPreGroupFilter(NotifFilter filter) {
        Assert.isMainThread();
        mPipelineState.requireState(STATE_IDLE);

        mNotifPreGroupFilters.add(filter);
        filter.setInvalidationListener(this::onPreGroupFilterInvalidated);
    }

    void addFinalizeFilter(NotifFilter filter) {
        Assert.isMainThread();
        mPipelineState.requireState(STATE_IDLE);

        mNotifFinalizeFilters.add(filter);
        filter.setInvalidationListener(this::onFinalizeFilterInvalidated);
    }

    void addPromoter(NotifPromoter promoter) {
        Assert.isMainThread();
        mPipelineState.requireState(STATE_IDLE);

        mNotifPromoters.add(promoter);
        promoter.setInvalidationListener(this::onPromoterInvalidated);
    }

    void setSectioners(List<NotifSectioner> sectioners) {
        Assert.isMainThread();
        mPipelineState.requireState(STATE_IDLE);

        mNotifSections.clear();
        for (NotifSectioner sectioner : sectioners) {
            final NotifSection section = new NotifSection(sectioner, mNotifSections.size());
            final NotifComparator sectionComparator = section.getComparator();
            mNotifSections.add(section);
            sectioner.setInvalidationListener(this::onNotifSectionInvalidated);
            if (sectionComparator != null) {
                sectionComparator.setInvalidationListener(this::onNotifComparatorInvalidated);
            }
        }

        mNotifSections.add(new NotifSection(DEFAULT_SECTIONER, mNotifSections.size()));

        // validate sections
        final ArraySet<Integer> seenBuckets = new ArraySet<>();
        int lastBucket = mNotifSections.size() > 0
                ? mNotifSections.get(0).getBucket()
                : 0;
        for (NotifSection section : mNotifSections) {
            if (lastBucket != section.getBucket() && seenBuckets.contains(section.getBucket())) {
                throw new IllegalStateException("setSectioners with non contiguous sections "
                        + section.getLabel() + " has an already seen bucket");
            }
            lastBucket = section.getBucket();
            seenBuckets.add(lastBucket);
        }
    }

    void setBundler(NotifBundler bundler) {
        Assert.isMainThread();
        mPipelineState.requireState(STATE_IDLE);

        mNotifBundler = bundler;
        if (mNotifBundler == null) {
            throw new IllegalStateException(TAG + ".setBundler: null");
        }

        mIdToBundleEntry.clear();
        for (BundleSpec spec : mNotifBundler.getBundleSpecs()) {
            debugBundleLog(TAG, () -> "create BundleEntry with id: " + spec.getKey());
            mIdToBundleEntry.put(spec.getKey(), new BundleEntry(spec));
        }
    }

    void setNotifStabilityManager(@NonNull NotifStabilityManager notifStabilityManager) {
        Assert.isMainThread();
        mPipelineState.requireState(STATE_IDLE);

        if (mNotifStabilityManager != null) {
            throw new IllegalStateException(
                    "Attempting to set the NotifStabilityManager more than once. There should "
                            + "only be one visual stability manager. Manager is being set by "
                            + mNotifStabilityManager.getName() + " and "
                            + notifStabilityManager.getName());
        }

        mNotifStabilityManager = notifStabilityManager;
        mNotifStabilityManager.setInvalidationListener(this::onReorderingAllowedInvalidated);
    }

    @NonNull
    private NotifStabilityManager getStabilityManager() {
        if (mNotifStabilityManager == null) {
            return DefaultNotifStabilityManager.INSTANCE;
        }
        return mNotifStabilityManager;
    }

    @NonNull
    private NotifBundler getNotifBundler() {
        if (mNotifBundler == null) {
            return DefaultNotifBundler.INSTANCE;
        }
        return mNotifBundler;
    }

    void setComparators(List<NotifComparator> comparators) {
        Assert.isMainThread();
        mPipelineState.requireState(STATE_IDLE);

        mNotifComparators.clear();
        for (NotifComparator comparator : comparators) {
            mNotifComparators.add(comparator);
            comparator.setInvalidationListener(this::onNotifComparatorInvalidated);
        }
    }

    List<PipelineEntry> getShadeList() {
        // NOTE: Accessing this method when the pipeline is running is generally going to provide
        //  incorrect results, and indicates a poorly behaved component of the pipeline.
        mPipelineState.requireState(STATE_IDLE);
        return mReadOnlyNotifList;
    }

    private final CollectionReadyForBuildListener mReadyForBuildListener =
            new CollectionReadyForBuildListener() {
                @Override
                public void onBuildList(Collection<NotificationEntry> entries, String reason) {
                    Assert.isMainThread();
                    mPendingEntries = new ArrayList<>(entries);
                    mLogger.logOnBuildList(reason);
                    rebuildListIfBefore(STATE_BUILD_STARTED);
                }
            };

    private void onPreRenderInvalidated(Invalidator invalidator, @Nullable String reason) {
        Assert.isMainThread();

        mLogger.logPreRenderInvalidated(invalidator, mPipelineState.getState(), reason);

        rebuildListIfBefore(STATE_FINALIZING);
    }

    private void onPreGroupFilterInvalidated(NotifFilter filter, @Nullable String reason) {
        Assert.isMainThread();

        mLogger.logPreGroupFilterInvalidated(filter, mPipelineState.getState(), reason);

        rebuildListIfBefore(STATE_PRE_GROUP_FILTERING);
    }

    private void onReorderingAllowedInvalidated(NotifStabilityManager stabilityManager,
            @Nullable String reason) {
        Assert.isMainThread();

        mLogger.logReorderingAllowedInvalidated(
                stabilityManager,
                mPipelineState.getState(),
                reason);

        rebuildListIfBefore(STATE_GROUPING);
    }

    private void onPromoterInvalidated(NotifPromoter promoter, @Nullable String reason) {
        Assert.isMainThread();

        mLogger.logPromoterInvalidated(promoter, mPipelineState.getState(), reason);

        rebuildListIfBefore(STATE_TRANSFORMING);
    }

    private void onNotifSectionInvalidated(NotifSectioner section, @Nullable String reason) {
        Assert.isMainThread();

        mLogger.logNotifSectionInvalidated(section, mPipelineState.getState(), reason);

        rebuildListIfBefore(STATE_SORTING);
    }

    private void onFinalizeFilterInvalidated(NotifFilter filter, @Nullable String reason) {
        Assert.isMainThread();

        mLogger.logFinalizeFilterInvalidated(filter, mPipelineState.getState(), reason);

        rebuildListIfBefore(STATE_FINALIZE_FILTERING);
    }

    private void onNotifComparatorInvalidated(NotifComparator comparator, @Nullable String reason) {
        Assert.isMainThread();

        mLogger.logNotifComparatorInvalidated(comparator, mPipelineState.getState(), reason);

        rebuildListIfBefore(STATE_SORTING);
    }

    /**
     * The core algorithm of the pipeline. See the top comment in {@link NotifPipeline} for
     * details on our contracts with other code.
     *
     * Once the build starts we are very careful to protect against reentrant code. Anything that
     * tries to invalidate itself after the pipeline has passed it by will return in an exception.
     * In general, we should be extremely sensitive to client code doing things in the wrong order;
     * if we detect that behavior, we should crash instantly.
     */
    private void buildList() {
        Trace.beginSection("ShadeListBuilder.buildList");
        mPipelineState.requireIsBefore(STATE_BUILD_STARTED);
        debugBundleLog(TAG, () -> mPipelineState.getStateName() + "---------------------");

        if (mPendingEntries != null) {
            mAllEntries = mPendingEntries;
            mPendingEntries = null;
        }

        if (!mNotifStabilityManager.isPipelineRunAllowed()) {
            mLogger.logPipelineRunSuppressed();
            Trace.endSection();
            return;
        }

        mPipelineState.setState(STATE_BUILD_STARTED);

        // Step 1: Reset notification states
        mPipelineState.incrementTo(STATE_RESETTING);
        resetNotifs();
        onBeginRun();

        // Step 2: Filter out any notifications that shouldn't be shown right now
        mPipelineState.incrementTo(STATE_PRE_GROUP_FILTERING);
        debugList("before filterNotifs");
        filterNotifs(mAllEntries, mNotifList, mNotifPreGroupFilters);

        // Step 3: Group notifications with the same group key and set summaries
        mPipelineState.incrementTo(STATE_GROUPING);
        groupNotifs(mNotifList, mNewNotifList);
        applyNewNotifList();
        pruneIncompleteGroups(mNotifList);

        // Step 3.5: Bundle notifications according to classification
        if (NotificationBundleUi.isEnabled()) {
            bundleNotifs(mNotifList, mNewNotifList);
            applyNewNotifList();
            debugList("after bundling");
        }

        // Step 4: Group transforming
        // Move some notifs out of their groups and up to top-level (mostly used for heads-upping)
        dispatchOnBeforeTransformGroups(mReadOnlyNotifList);
        mPipelineState.incrementTo(STATE_TRANSFORMING);
        promoteNotifs(mNotifList);
        pruneIncompleteGroups(mNotifList);

        // Step 4.5: Reassign/revert any groups to maintain visual stability
        mPipelineState.incrementTo(STATE_GROUP_STABILIZING);
        stabilizeGroupingNotifs(mNotifList);
        debugList("after stabilizeGroupingNotifs");

        // Step 5: Section & Sort
        // Assign each top-level entry a section, and copy to all of its children
        dispatchOnBeforeSort(mReadOnlyNotifList);
        mPipelineState.incrementTo(STATE_SORTING);
        assignSections();
        notifySectionEntriesUpdated();
        // Sort the list by section and then within section by our list of custom comparators
        sortListAndGroups();
        debugList("after sortListAndGroups");

        // Step 6: Filter out entries after pre-group filtering, grouping, promoting, and sorting
        // Now filters can see grouping, sectioning, and order information to determine whether
        // to filter or not.
        dispatchOnBeforeFinalizeFilter(mReadOnlyNotifList);
        mPipelineState.incrementTo(STATE_FINALIZE_FILTERING);
        filterNotifs(mNotifList, mNewNotifList, mNotifFinalizeFilters);
        applyNewNotifList();
        debugList("after filterNotifs");
        pruneIncompleteGroups(mNotifList);
        debugList("after pruneIncompleteGroups");

        // Step 7: Lock in our group structure and log anything that's changed since the last run
        mPipelineState.incrementTo(STATE_FINALIZING);
        logChanges();
        freeEmptyGroups();
        debugList("after freeEmptyGroups");
        cleanupPluggables();
        debugList("after cleanupPluggables");

        // Step 8: Dispatch the new list, first to any listeners and then to the view layer
        dispatchOnBeforeRenderList(mReadOnlyNotifList);
        Trace.beginSection("ShadeListBuilder.onRenderList");
        if (mOnRenderListListener != null) {
            mOnRenderListListener.onRenderList(mReadOnlyNotifList);
        }
        Trace.endSection();

        Trace.beginSection("ShadeListBuilder.logEndBuildList");
        // Step 9: We're done!
        logEndBuildList(mLogger, mIterationCount, mReadOnlyNotifList,
                /* enforcedVisualStability */ !mNotifStabilityManager.isEveryChangeAllowed());
        if (mAlwaysLogList || mIterationCount % 10 == 0) {
            Trace.beginSection("ShadeListBuilder.logFinalList");
            mLogger.logFinalList(mNotifList);
            Trace.endSection();
        }
        Trace.endSection();
        mPipelineState.setState(STATE_IDLE);
        mIterationCount++;
        Trace.endSection();
    }

    private void notifySectionEntriesUpdated() {
        Trace.beginSection("ShadeListBuilder.notifySectionEntriesUpdated");
        mTempSectionMembers.clear();
        for (NotifSection section : mNotifSections) {
            for (PipelineEntry entry : mNotifList) {
                if (section == entry.getSection()) {
                    mTempSectionMembers.add(entry);
                }
            }
            Trace.beginSection(section.getLabel());
            section.getSectioner().onEntriesUpdated(mTempSectionMembers);
            Trace.endSection();
            mTempSectionMembers.clear();
        }
        Trace.endSection();
    }

    /**
     * Points mNotifList to the list stored in mNewNotifList.
     * Reuses the (emptied) mNotifList as mNewNotifList.
     *
     * Accordingly, updates the ReadOnlyNotifList pointers.
     */
    private void applyNewNotifList() {
        mNotifList.clear();
        List<PipelineEntry> emptyList = mNotifList;
        mNotifList = mNewNotifList;
        mNewNotifList = emptyList;

        List<PipelineEntry> readOnlyNotifList = mReadOnlyNotifList;
        mReadOnlyNotifList = mReadOnlyNewNotifList;
        mReadOnlyNewNotifList = readOnlyNotifList;
    }

    private void resetNotifs() {
        for (GroupEntry group : mGroups.values()) {
            group.beginNewAttachState();
            group.clearChildren();
            group.setSummary(null);
        }

        for (NotificationEntry entry : mAllEntries) {
            entry.beginNewAttachState();
        }

        for (BundleEntry be : mIdToBundleEntry.values()) {
            be.beginNewAttachState();
            be.clearChildren();
            // BundleEntry has not representative summary so we do not need to clear it here.
        }
        mNotifList.clear();
    }

    private void applyFilterToGroup(GroupEntry groupEntry, long now, List<NotifFilter> filters) {
        // apply filter on its summary
        final NotificationEntry summary = groupEntry.getRepresentativeEntry();
        if (applyFilters(summary, now, filters)) {
            groupEntry.setSummary(null);
            annulAddition(summary);
        }

        // apply filter on its children
        final List<NotificationEntry> children = groupEntry.getRawChildren();
        for (int j = children.size() - 1; j >= 0; j--) {
            final NotificationEntry child = children.get(j);
            if (applyFilters(child, now, filters)) {
                children.remove(child);
                annulAddition(child);
            }
        }
    }

    private void applyFilterToBundle(BundleEntry bundleEntry, long now, List<NotifFilter> filters) {
        List<ListEntry> bundleChildren = bundleEntry.getChildren();
        List<ListEntry> bundleChildrenToRemove = new ArrayList<>();
        for (ListEntry listEntry : bundleChildren) {
            if (listEntry instanceof GroupEntry groupEntry) {
                applyFilterToGroup(groupEntry, now, filters);
            } else {
                if (applyFilters((NotificationEntry) listEntry, now, filters)) {
                    bundleChildrenToRemove.add(listEntry);
                    debugBundleLog(TAG, () ->
                            "annulled bundle child" + listEntry.getKey()
                                    + " bundle size: " + bundleEntry.getChildren().size());
                }
            }
        }
        for (ListEntry r : bundleChildrenToRemove) {
            bundleEntry.removeChild(r);
            annulAddition(r);
        }
    }

    private void filterNotifs(
            Collection<? extends PipelineEntry> entries,
            List<PipelineEntry> out,
            List<NotifFilter> filters) {
        Trace.beginSection("ShadeListBuilder.filterNotifs");
        final long now = mSystemClock.elapsedRealtime();
        for (PipelineEntry pipelineEntry : entries) {
            if (pipelineEntry instanceof BundleEntry bundleEntry) {
                applyFilterToBundle(bundleEntry, now, filters);
                // We unconditionally preserve the BundleEntry here, then prune if empty later.
                out.add(bundleEntry);
            } else if (pipelineEntry instanceof GroupEntry groupEntry) {
                applyFilterToGroup(groupEntry, now, filters);
                out.add(groupEntry);
            } else {
                if (applyFilters((NotificationEntry) pipelineEntry, now, filters)) {
                    annulAddition(pipelineEntry);
                } else {
                    out.add(pipelineEntry);
                }
            }
        }
        Trace.endSection();
    }

    private void groupNotifs(List<PipelineEntry> entries, List<PipelineEntry> out) {
        Trace.beginSection("ShadeListBuilder.groupNotifs");
        for (PipelineEntry pipelineEntry : entries) {
            // since grouping hasn't happened yet, all notifs are NotificationEntries
            NotificationEntry entry = (NotificationEntry) pipelineEntry;
            if (entry.getSbn().isGroup()) {
                final String topLevelKey = entry.getSbn().getGroupKey();
                GroupEntry group = mGroups.get(topLevelKey);
                if (group == null) {
                    group = new GroupEntry(topLevelKey, mSystemClock.elapsedRealtime());
                    mGroups.put(topLevelKey, group);
                }
                if (group.getParent() == null) {
                    group.setParent(ROOT_ENTRY);
                    out.add(group);
                }

                entry.setParent(group);

                if (entry.getSbn().getNotification().isGroupSummary()) {
                    final NotificationEntry existingSummary = group.getSummary();

                    if (existingSummary == null) {
                        group.setSummary(entry);
                    } else {
                        mLogger.logDuplicateSummary(mIterationCount, group, existingSummary, entry);

                        NotificationEntry autogroupSummary = getAutogroupSummary(entry,
                                existingSummary);
                        if (autogroupSummary != null) {
                            // Prioritize the autogroup summary if duplicate summaries found
                            group.setSummary(autogroupSummary);
                            NotificationEntry otherEntry =
                                    autogroupSummary.equals(entry) ? existingSummary : entry;
                            annulAddition(otherEntry, out);
                        } else {
                            // Use whichever one was posted most recently
                            if (entry.getSbn().getPostTime()
                                    > existingSummary.getSbn().getPostTime()) {
                                group.setSummary(entry);
                                annulAddition(existingSummary, out);
                            } else {
                                annulAddition(entry, out);
                            }
                        }
                    }
                } else {
                    group.addChild(entry);
                }

            } else {

                final String topLevelKey = entry.getKey();
                if (mGroups.containsKey(topLevelKey)) {
                    mLogger.logDuplicateTopLevelKey(mIterationCount, topLevelKey);
                } else {
                    entry.setParent(ROOT_ENTRY);
                    out.add(entry);
                }
            }
        }
        Trace.endSection();
    }

    private @Nullable NotificationEntry getAutogroupSummary(NotificationEntry newSummary,
            NotificationEntry existingSummary) {
        if ((newSummary.getSbn().getNotification().flags
                & Notification.FLAG_AUTOGROUP_SUMMARY) != 0) {
            return newSummary;
        } else if ((existingSummary.getSbn().getNotification().flags
                & Notification.FLAG_AUTOGROUP_SUMMARY) != 0) {
            return existingSummary;
        }
        return null;
    }

    @Nullable
    BundleEntry getBundleEntry(String id) {
        BundleEntry be = mIdToBundleEntry.get(id);
        if (be == null) {
            debugBundleLog(TAG, () -> "BundleEntry not found for bundleId: " + id);
        }
        return be;
    }

    Collection<BundleEntry> getBundleEntries() {
        return Collections.unmodifiableCollection(mIdToBundleEntry.values());
    }

    private void debugList(String s) {
        if (!BundleCoordinator.debugBundleLogs) {
            return;
        }
        StringBuilder listStr = new StringBuilder();
        for (int i = 0; i < mNotifList.size(); i++) {
            PipelineEntry pipelineEntry = mNotifList.get(i);
            String className = " Notif:";
            if (pipelineEntry instanceof GroupEntry) {
                className = " Group:";
                listStr.append("i=" + i).append(className).append(pipelineEntry.getKey())
                        .append("\n");
            } else if (pipelineEntry instanceof BundleEntry bundleEntry) {
                className = " Bundle:";
                listStr.append("i=" + i).append(className).append(pipelineEntry.getKey())
                        .append(" size: " + bundleEntry.getChildren().size()).append("\n");

                for (ListEntry listEntry : bundleEntry.getChildren()) {

                    if (listEntry instanceof NotificationEntry notifEntry) {
                        listStr.append("  Notif").append(notifEntry.getKey()).append("\n");

                    } else if (listEntry instanceof GroupEntry groupEntry) {
                        listStr.append("  Group").append(groupEntry.getKey())
                                .append(" size: " + groupEntry.getChildren().size()).append("\n");

                        for (NotificationEntry notifEntry : groupEntry.getChildren()) {
                            listStr.append("    Notif").append(notifEntry.getKey()).append("\n");
                        }
                    }
                }
            } else { // Unbundled NotifEntry
                listStr.append("i=" + i).append(className).append(pipelineEntry.getKey())
                        .append("\n");
            }
        }
        Log.d(TAG, mPipelineState.getStateName() + " " + s + " list ---\n" + listStr + "\n");
    }

    private void bundleNotifs(List<PipelineEntry> in, List<PipelineEntry> out) {
        Trace.beginSection("ShadeListBuilder.bundleNotifs");
        // Bundle NotificationEntry and non-empty GroupEntry
        for (PipelineEntry pipelineEntry : in) {
            if (!(pipelineEntry instanceof ListEntry listEntry)) {
                // This should not happen
                continue;
            }
            String id = getNotifBundler().getBundleIdOrNull(listEntry);
            if (id == null) {
                debugBundleLog(TAG, () ->"bundleNotifs: no bundle id for:" + listEntry.getKey());
                out.add(listEntry);
            } else {
                BundleEntry bundleEntry = getBundleEntry(id);
                if (bundleEntry == null) {
                    debugBundleLog(TAG, () ->"bundleNotifs: BundleEntry NULL for: "
                            + listEntry.getKey() + " bundleId:" + id);
                    out.add(listEntry);
                } else {
                    debugBundleLog(TAG, () ->"bundleNotifs: ADD listEntry:" + listEntry.getKey()
                            + " to bundle:" + bundleEntry.getKey());
                    bundleEntry.addChild(listEntry);
                    listEntry.setParent(bundleEntry);
                }
            }
        }
        // Add all BundleEntries to the list. They will be pruned later if they are empty.
        final Collection<BundleEntry> allBundles = mIdToBundleEntry.values();
        for (final BundleEntry bundle : allBundles) {
            bundle.setParent(ROOT_ENTRY);
        }
        out.addAll(allBundles);
        Trace.endSection();
    }

    @OptIn(markerClass = InternalNotificationsApi.class) // for BundleEntry#getRawChildren()
    private void stabilizeGroupingNotifs(List<PipelineEntry> topLevelList) {
        if (getStabilityManager().isEveryChangeAllowed()) {
            return;
        }
        Trace.beginSection("ShadeListBuilder.stabilizeGroupingNotifs");
        for (int i = 0; i < topLevelList.size(); i++) {
            final PipelineEntry tle = topLevelList.get(i);
            if (tle instanceof BundleEntry bundleEntry) {
                // maybe put bundle children back into their old parents (including moving back to
                // top-level)
                final List<ListEntry> bundleChildren = bundleEntry.getRawChildren();
                for (int j = 0; j < bundleChildren.size(); j++) {
                    final ListEntry child = bundleChildren.get(j);
                    if (maybeSuppressParentChange(child, topLevelList)) {
                        bundleChildren.remove(j);
                        j--;
                    }
                    if (child instanceof GroupEntry groupEntry) {
                        // maybe put group children back into their old parents (including moving
                        // back to top-level)
                        final List<NotificationEntry> groupChildren = groupEntry.getRawChildren();
                        for (int k = 0; k < groupChildren.size(); k++) {
                            if (maybeSuppressParentChange(groupChildren.get(k), topLevelList)) {
                                // child was put back into its previous parent, so we remove it from
                                // this group
                                groupChildren.remove(k);
                                k--;
                            }
                        }
                    }
                }
            } else if (tle instanceof GroupEntry groupEntry) {
                // maybe put children back into their old parents (including moving back to
                // top-level)
                List<NotificationEntry> children = groupEntry.getRawChildren();
                for (int j = 0; j < groupEntry.getChildren().size(); j++) {
                    if (maybeSuppressParentChange(children.get(j), topLevelList)) {
                        // child was put back into its previous parent, so we remove it from this
                        // group
                        children.remove(j);
                        j--;
                    }
                }
            } else if (tle instanceof NotificationEntry notifEntry) {
                // maybe put top-level-entries back into their previous parents
                if (maybeSuppressParentChange(notifEntry, topLevelList)) {
                    // entry was put back into its previous parent, so we remove it from the list of
                    // top-level-entries
                    topLevelList.remove(i);
                    i--;
                }
            }
        }
        Trace.endSection();
    }

    /**
     * Returns true if the parent change was suppressed, else false
     */
    private boolean maybeSuppressParentChange(ListEntry entry, List<PipelineEntry> out) {
        final PipelineEntry prevParent = entry.getPreviousAttachState().getParent();
        if (prevParent == null) {
            // New entries are always allowed.
            return false;
        }
        final PipelineEntry assignedParent = entry.getParent();
        if (prevParent == assignedParent) {
            // Nothing to change.
            return false;
        }
        if (prevParent != ROOT_ENTRY && prevParent.getParent() == null) {
            // Previous parent was a group, which has been removed (hence, its parent is null).
            // Always allow this group change, otherwise the child will remain attached to the
            // removed group and be removed from the shade until visual stability ends.
            return false;
        }
        // TODO: Rather than perform "half" of the move here and require the caller remove the child
        //  from the assignedParent, ideally we would have an atomic "move" operation.
        if (entry instanceof NotificationEntry notifEntry) {
            if (!getStabilityManager().isParentChangeAllowed(notifEntry)) {
                entry.getAttachState().getSuppressedChanges().setParent(assignedParent);
                entry.setParent(prevParent);
                if (prevParent == ROOT_ENTRY) {
                    out.add(entry);
                } else if (prevParent instanceof GroupEntry groupEntry) {
                    groupEntry.addChild(notifEntry);
                    if (!mGroups.containsKey(groupEntry.getKey())) {
                        mGroups.put(groupEntry.getKey(), groupEntry);
                    }
                } else if (prevParent instanceof BundleEntry bundleEntry) {
                    bundleEntry.addChild(entry);
                }
                return true;
            }
        } else if (entry instanceof GroupEntry groupEntry) {
            if (!getStabilityManager().isParentChangeAllowed(groupEntry)) {
                entry.getAttachState().getSuppressedChanges().setParent(assignedParent);
                entry.setParent(prevParent);
                if (prevParent == ROOT_ENTRY) {
                    out.add(entry);
                } else if (prevParent instanceof BundleEntry bundleEntry) {
                    bundleEntry.addChild(entry);
                } else {
                    throw new IllegalStateException("GroupEntry " + groupEntry.getKey()
                            + " was previously attached to illegal parent: " + prevParent.getKey());
                }
                return true;
            }
        }
        return false;
    }

    private void promoteNotifs(List<PipelineEntry> list) {
        Trace.beginSection("ShadeListBuilder.promoteNotifs");
        for (int i = 0; i < list.size(); i++) {
            final PipelineEntry tle = list.get(i);

            if (tle instanceof GroupEntry group) {
                group.getRawChildren().removeIf(child -> {
                    final boolean shouldPromote = applyTopLevelPromoters(child);

                    if (shouldPromote) {
                        child.setParent(ROOT_ENTRY);
                        list.add(child);
                    }

                    return shouldPromote;
                });
            }
            // Notifications inside BundleEntry will never be considered for promotion.
        }
        Trace.endSection();
    }

    private void pruneBundleEntry(BundleEntry bundleEntry,
            ArraySet<String> groupsExemptFromSummaryPromotion,
            Set<String> groupsWithChildrenLostToStability, List<PipelineEntry> shadeList) {
        final List<ListEntry> bundleChildren = bundleEntry.getRawChildren();
        // Iterate backwards, so that we can remove elements without affecting indices of
        // yet-to-be-accessed entries.
        for (int i = bundleChildren.size() - 1; i >= 0; i--) {
            final ListEntry listEntry = bundleChildren.get(i);
            if (listEntry instanceof GroupEntry groupEntry) {
                pruneGroupEntry(groupEntry, i, bundleEntry, bundleChildren,
                        groupsExemptFromSummaryPromotion, groupsWithChildrenLostToStability,
                        shadeList);
            }
        }
    }

    private void pruneIncompleteGroups(List<PipelineEntry> shadeList) {
        Trace.beginSection("ShadeListBuilder.pruneIncompleteGroups");
        // Any group which lost a child on this run to stability is exempt from being pruned or
        //  having its summary promoted, regardless of how many children it has
        Set<String> groupsWithChildrenLostToStability =
                getGroupsWithChildrenLostToStability(shadeList);
        // Groups with children lost to stability are exempt from summary promotion.
        ArraySet<String> groupsExemptFromSummaryPromotion =
                new ArraySet<>(groupsWithChildrenLostToStability);
        // Any group which lost a child to filtering or promotion is exempt from having its summary
        // promoted when it has no attached children.
        addGroupsWithChildrenLostToFiltering(groupsExemptFromSummaryPromotion);
        addGroupsWithChildrenLostToPromotion(shadeList, groupsExemptFromSummaryPromotion);

        // Iterate backwards, so that we can remove elements without affecting indices of
        // yet-to-be-accessed entries.
        debugBundleLog(TAG, () -> mPipelineState.getStateName() + " pruneIncompleteGroups size: "
                + shadeList.size());

        for (int i = shadeList.size() - 1; i >= 0; i--) {
            final PipelineEntry pipelineEntry = shadeList.get(i);

            if (pipelineEntry instanceof GroupEntry groupEntry) {
                pruneGroupEntry(groupEntry, i, ROOT_ENTRY, shadeList,
                        groupsExemptFromSummaryPromotion, groupsWithChildrenLostToStability,
                        shadeList);
            } else if (pipelineEntry instanceof BundleEntry bundleEntry) {
                pruneBundleEntry(bundleEntry, groupsExemptFromSummaryPromotion,
                        groupsWithChildrenLostToStability, shadeList);

                if (bundleEntry.getChildren().isEmpty()) {
                    BundleEntry prunedBundle = (BundleEntry) shadeList.remove(i);
                    annulAddition(bundleEntry, shadeList);
                    debugBundleLog(TAG, () -> mPipelineState.getStateName()
                            + " pruned empty bundle: "
                            + prunedBundle.getKey());
                } else {
                    debugBundleLog(TAG, () -> mPipelineState.getStateName()
                            + " skip pruning bundle: " + bundleEntry.getKey()
                            + " size: " + bundleEntry.getChildren().size());
                }
            }
        }
        Trace.endSection();
    }

    private void pruneGroupEntry(GroupEntry group, int i, PipelineEntry parent,
            List<? super ListEntry> siblings, ArraySet<String> groupsExemptFromSummaryPromotion,
            Set<String> groupsWithChildrenLostToStability, List<PipelineEntry> shadeList) {
        final List<NotificationEntry> children = group.getRawChildren();
        final boolean hasSummary = group.getSummary() != null;
        debugBundleLog(TAG,
                () -> mPipelineState.getStateName() + " pruneGroupEntry " + group.getKey()
                        + " hasSummary:" + hasSummary
                        + " childCount:" + children.size()
        );
        if (hasSummary && children.isEmpty()) {
            if (groupsExemptFromSummaryPromotion.contains(group.getKey())) {
                // This group lost a child on this run to promotion or stability, so it is
                //  exempt from having its summary promoted to the top level, so prune it.
                //  It has no children, so it will just vanish.
                pruneGroupAtIndexAndPromoteAnyChildren(parent, siblings, group, i, "no child",
                        shadeList);
            } else {
                // For any other summary with no children, promote the summary.
                pruneGroupAtIndexAndPromoteSummary(parent, siblings, group, i, shadeList);
            }
        } else if (!hasSummary) {
            // If the group doesn't provide a summary, ignore it and add
            //  any children it may have directly to parent entry.
            pruneGroupAtIndexAndPromoteAnyChildren(parent, siblings, group, i, "no summary",
                    shadeList);

        } else if (children.size() < MIN_CHILDREN_FOR_GROUP) {
            // This group has a summary and insufficient, but nonzero children.
            checkState(hasSummary, "group must have summary at this point");
            checkState(!children.isEmpty(), "empty group should have been promoted");

            if (groupsWithChildrenLostToStability.contains(group.getKey())) {
                // This group lost a child on this run to stability, so it is exempt from
                //  the "min children" requirement; keep it around in case more children are
                //  added before changes are allowed again.
                group.getAttachState().getSuppressedChanges().setWasPruneSuppressed(true);
                return;
            }
            if (group.wasAttachedInPreviousPass()
                    && !getStabilityManager().isGroupPruneAllowed(group)) {
                checkState(!children.isEmpty(), "empty group should have been pruned");
                // This group was previously attached and group changes aren't
                //  allowed; keep it around until group changes are allowed again.
                group.getAttachState().getSuppressedChanges().setWasPruneSuppressed(true);
                return;
            }
            // The group is too small, ignore it and add
            // its children (if any) directly to top-level.
            pruneGroupAtIndexAndPromoteAnyChildren(parent, siblings, group, i, "too small",
                    shadeList);
        } else {
            debugBundleLog(TAG, () -> mPipelineState.getStateName()
                    + " group not pruned: " + group.getKey());
        }
    }

    private void pruneGroupAtIndexAndPromoteSummary(PipelineEntry parent,
            List<? super ListEntry> siblings, GroupEntry group, int index,
            List<PipelineEntry> shadeList) {
        debugBundleLog(TAG, () -> mPipelineState.getStateName() + " promote summary prune group:"
                + group.getKey());

        // Validate that the group has no children
        checkArgument(group.getChildren().isEmpty(), "group should have no children");

        NotificationEntry summary = group.getSummary();
        summary.setParent(parent);
        // The list may be sorted; replace the group with the summary, in its place
        PipelineEntry oldEntry = (PipelineEntry) siblings.set(index, summary);

        // Validate that the replaced entry was the group entry
        checkState(oldEntry == group);

        group.setSummary(null);
        annulAddition(group, shadeList);
        summary.getAttachState().setGroupPruneReason(
                "SUMMARY with no children @ " + mPipelineState.getStateName());
    }

    private void pruneGroupAtIndexAndPromoteAnyChildren(PipelineEntry parent,
            List<? super ListEntry> siblings, GroupEntry group, int index, String reason,
            List<PipelineEntry> shadeList) {

        final boolean inBundle = group.getAttachState().getParent() instanceof BundleEntry;
        debugBundleLog(TAG, () -> mPipelineState.getStateName()
                + " " + reason + " => promote child prune group:" + group.getKey()
                + " parent: " + group.getAttachState().getParent().getKey()
                + " inBundle:" + inBundle
        );

        // REMOVE the GroupEntry at this index
        PipelineEntry oldEntry = (PipelineEntry) siblings.remove(index);

        // Validate that the replaced entry was the group entry
        if (oldEntry != group) {
            debugBundleLog(TAG, () -> mPipelineState.getStateName()
                    + " oldEntry:" + oldEntry.getKey()
                    + " groupToRemove:" + group.getKey());
        }
        checkState(oldEntry == group);

        List<NotificationEntry> children = group.getRawChildren();
        boolean hasSummary = group.getSummary() != null;

        // Remove the group summary, if present, and leave detached.
        if (hasSummary) {
            final NotificationEntry summary = group.getSummary();
            group.setSummary(null);
            annulAddition(summary, shadeList);
            summary.getAttachState().setGroupPruneReason(
                    "SUMMARY with too few children @ " + mPipelineState.getStateName());
        }

        // Promote any children
        if (!children.isEmpty()) {
            // create the reason we will report on the child for why its group was pruned.
            String childReason = hasSummary
                    ? ("CHILD with " + (children.size() - 1) + " siblings @ "
                    + mPipelineState.getStateName())
                    : ("CHILD with no summary @ " + mPipelineState.getStateName());

            // Remove children from the group and add them to the shadeList.
            for (int j = 0; j < children.size(); j++) {
                final NotificationEntry child = children.get(j);
                child.setParent(parent);
                child.getAttachState().setGroupPruneReason(requireNonNull(childReason));
            }
            // The list may be sorted, so add the children in order where the group was.
            siblings.addAll(index, children);
            children.clear();
        }

        annulAddition(group, shadeList);
    }

    /**
     * Collect the keys of any groups which have already lost a child to stability this run.
     *
     * If stability is being enforced, then {@link #stabilizeGroupingNotifs(List)} might have
     * detached some children from their groups and left them with their previous parent. Doing so
     * would set the {@link SuppressedAttachState#getParent() suppressed parent} for the current
     * attach state.
     *
     * If we've already removed a child from this group, we don't want to remove any more children
     * from the group (even if that would leave only a single notification in the group) because
     * that could cascade over multiple runs and allow a large group of notifications all show up as
     * top level (ungrouped) notifications.
     */
    @NonNull
    private Set<String> getGroupsWithChildrenLostToStability(List<PipelineEntry> shadeList) {
        if (getStabilityManager().isEveryChangeAllowed()) {
            return Collections.emptySet();
        }
        ArraySet<String> groupsWithChildrenLostToStability = new ArraySet<>();
        for (int i = 0; i < shadeList.size(); i++) {
            final PipelineEntry tle = shadeList.get(i);
            addGroupsWithChildrenLostToStability(tle, groupsWithChildrenLostToStability);
        }
        return groupsWithChildrenLostToStability;
    }

    private void addGroupsWithChildrenLostToStability(PipelineEntry entry, Set<String> out) {
        if (entry instanceof BundleEntry be) {
            final List<ListEntry> children = be.getChildren();
            for (int i = 0; i < children.size(); i++) {
                final ListEntry child = children.get(i);
                addGroupsWithChildrenLostToStability(child, out);
            }
        } else if (entry instanceof ListEntry le) {
            addGroupsWithChildrenLostToStability(le, out);
        }
    }

    private void addGroupsWithChildrenLostToStability(ListEntry entry, Set<String> out) {
        final PipelineEntry suppressedParent =
                entry.getAttachState().getSuppressedChanges().getParent();
        if (suppressedParent != null) {
            // This ListEntry was supposed to be attached to this group, so mark the group as
            // having lost a child to stability.
            out.add(suppressedParent.getKey());
        }
    }

    /**
     * Collect the keys of any groups which have already lost a child to a {@link NotifPromoter}
     * this run.
     *
     * These groups will be exempt from appearing without any children.
     */
    private void addGroupsWithChildrenLostToPromotion(List<PipelineEntry> shadeList,
            Set<String> out) {
        for (int i = 0; i < shadeList.size(); i++) {
            final ListEntry tle = shadeList.get(i).asListEntry();
            if (tle != null && tle.getAttachState().getPromoter() != null) {
                // This top-level-entry was part of a group, but was promoted out of it.
                final String groupKey = tle.getRepresentativeEntry().getSbn().getGroupKey();
                out.add(groupKey);
            }
        }
    }

    /**
     * Collect the keys of any groups which have already lost a child to a {@link NotifFilter}
     * this run.
     *
     * These groups will be exempt from appearing without any children.
     */
    private void addGroupsWithChildrenLostToFiltering(Set<String> out) {
        for (PipelineEntry tle : mAllEntries) {
            final ListEntry listEntry = tle.asListEntry();
            if (listEntry == null) {
                continue;
            }
            StatusBarNotification sbn = listEntry.getRepresentativeEntry().getSbn();
            if (sbn.isGroup()
                    && !sbn.getNotification().isGroupSummary()
                    && listEntry.getAttachState().getExcludingFilter() != null) {
                out.add(sbn.getGroupKey());
            }
        }
    }

    /**
     * If a PipelineEntry was added to the shade list and then later removed (e.g. because it was a
     * group that was broken up), this method will erase any bookkeeping traces of that addition
     * and/or check that they were already erased.
     * <p>
     * Before calling this method, the entry must already have been removed from its parent. If
     * it's a group, its summary must be null and its children must be empty.
     */
    private void annulAddition(PipelineEntry entry, List<PipelineEntry> shadeList) {

        // This function does very little, but if any of its assumptions are violated (and it has a
        // lot of them), it will put the system into an inconsistent state. So we check all of them
        // here.
        final PipelineEntry parent = entry.getParent();

        if (parent == null) {
            throw new IllegalStateException(
                    "Cannot nullify addition of " + entry.getKey() + ": no parent.");
        }

        if (parent == ROOT_ENTRY) {
            if (shadeList.contains(entry)) {
                throw new IllegalStateException("Cannot nullify addition of " + entry.getKey()
                        + ": it's still in the shade list.");
            }
        }

        if (entry instanceof GroupEntry ge) {
            if (ge.getSummary() != null) {
                throw new IllegalStateException(
                        "Cannot nullify group " + ge.getKey() + ": summary is not null");
            }
            if (!ge.getChildren().isEmpty()) {
                throw new IllegalStateException(
                        "Cannot nullify group " + ge.getKey() + ": still has children");
            }
        } else if (entry instanceof BundleEntry be) {
            if (!be.getChildren().isEmpty()) {
                throw new IllegalStateException(
                        "Cannot nullify bundle " + be.getKey() + ": still has children");
            }
        }

        if (parent instanceof GroupEntry parentGroupEntry) {
            if (entry == parentGroupEntry.getSummary()
                    || parentGroupEntry.getChildren().contains(entry)) {
                throw new IllegalStateException("Cannot nullify addition of child "
                        + entry.getKey() + ": it's still attached to its parent.");
            }
        } else if (parent instanceof BundleEntry parentBundleEntry) {
            if (parentBundleEntry.getChildren().contains(entry)) {
                throw new IllegalStateException("Cannot nullify addition of child "
                        + entry.getKey() + ": it's still attached to its parent.");
            }
        }

        annulAddition(entry);
    }

    /**
     * Erases bookkeeping traces stored on an entry when it is removed from the notif list.
     * This can happen if the entry is removed from a group that was broken up or if the entry was
     * filtered out during any of the filtering steps.
     */
    private void annulAddition(PipelineEntry entry) {
        entry.getAttachState().detach();
    }

    private void assignSections() {
        Trace.beginSection("ShadeListBuilder.assignSections");
        // Assign sections to top-level elements and their children
        for (PipelineEntry entry : mNotifList) {
            NotifSection section = applySections(entry);

            if (entry instanceof GroupEntry parent) {
                for (NotificationEntry child : parent.getChildren()) {
                    setEntrySection(child, section);
                }
            } else if (entry instanceof BundleEntry be) {
                for (ListEntry le : be.getChildren()) {
                    setEntrySection(le, section);
                    if (le instanceof GroupEntry ge) {
                        for (NotificationEntry ne : ge.getChildren()) {
                            setEntrySection(ne, section);
                        }
                    }
                }
            }
        }
        Trace.endSection();
    }

    private void sortListAndGroups() {
        Trace.beginSection("ShadeListBuilder.sortListAndGroups");
        sortWithSemiStableSort();
        Trace.endSection();
    }

    private void sortWithSemiStableSort() {
        // Sort each group's children
        boolean allSorted = true;
        for (PipelineEntry entry : mNotifList) {
            if (entry instanceof GroupEntry parent) {
                allSorted &= sortGroupChildren(parent.getRawChildren());
            } else if (entry instanceof BundleEntry bundleEntry) {
                allSorted &= sortBundleChildren(bundleEntry.getRawChildren());
                // Sort children of groups within bundles
                for (ListEntry le : bundleEntry.getChildren()) {
                    if (le instanceof GroupEntry ge) {
                        allSorted &= sortGroupChildren(ge.getRawChildren());
                    }
                }
            }
        }
        // Sort each section within the top level list
        mNotifList.sort(mTopLevelComparator);
        if (!getStabilityManager().isEveryChangeAllowed()) {
            for (List<PipelineEntry> subList : getSectionSubLists(mNotifList)) {
                allSorted &= mSemiStableSort.stabilizeTo(subList, mStableOrder, mNewNotifList);
            }
            applyNewNotifList();
        }
        assignIndexes(mNotifList);
        if (!allSorted) {
            // Report suppressed order changes
            getStabilityManager().onEntryReorderSuppressed();
        }
    }

    private Iterable<List<PipelineEntry>> getSectionSubLists(List<PipelineEntry> entries) {
        return ShadeListBuilderHelper.INSTANCE.getSectionSubLists(entries);
    }

    private boolean sortGroupChildren(List<NotificationEntry> entries) {
        if (getStabilityManager().isEveryChangeAllowed()) {
            entries.sort(mGroupChildrenComparator);
            return true;
        } else {
            return mSemiStableSort.sort(entries, mStableOrder, mGroupChildrenComparator);
        }
    }

    private boolean sortBundleChildren(List<ListEntry> entries) {
        if (getStabilityManager().isEveryChangeAllowed()) {
            entries.sort(mBundleChildrenComparator);
            return true;
        } else {
            return mSemiStableSort.sort(entries, mStableOrder, mBundleChildrenComparator);
        }
    }

    /** Determine whether the items in the list are sorted according to the comparator */
    @VisibleForTesting
    public static <T> boolean isSorted(List<T> items, Comparator<? super T> comparator) {
        if (items.size() <= 1) {
            return true;
        }
        Iterator<T> iterator = items.iterator();
        T previous = iterator.next();
        T current;
        while (iterator.hasNext()) {
            current = iterator.next();
            if (comparator.compare(previous, current) > 0) {
                return false;
            }
            previous = current;
        }
        return true;
    }

    /**
     * Assign the index of each notification relative to the total order
     */
    private void assignIndexes(List<PipelineEntry> notifList) {
        if (notifList.size() == 0) return;
        NotifSection currentSection = requireNonNull(notifList.get(0).getSection());
        int sectionMemberIndex = 0;
        for (int i = 0; i < notifList.size(); i++) {
            final PipelineEntry entry = notifList.get(i);
            NotifSection section = requireNonNull(entry.getSection());
            if (section.getIndex() != currentSection.getIndex()) {
                sectionMemberIndex = 0;
                currentSection = section;
            }
            entry.getAttachState().setStableIndex(sectionMemberIndex++);
            if (entry instanceof GroupEntry parent) {
                final NotificationEntry summary = parent.getSummary();
                if (summary != null) {
                    summary.getAttachState().setStableIndex(sectionMemberIndex++);
                }
                for (NotificationEntry child : parent.getChildren()) {
                    child.getAttachState().setStableIndex(sectionMemberIndex++);
                }
            } else if (entry instanceof BundleEntry bundleEntry) {
                for (ListEntry child : bundleEntry.getChildren()) {
                    child.getAttachState().setStableIndex(sectionMemberIndex++);
                    if (child instanceof GroupEntry groupEntry) {
                        final NotificationEntry summary = groupEntry.getSummary();
                        if (summary != null) {
                            summary.getAttachState().setStableIndex(sectionMemberIndex++);
                        }
                        for (NotificationEntry notifEntry : groupEntry.getChildren()) {
                            notifEntry.getAttachState().setStableIndex(sectionMemberIndex++);
                        }
                    }
                }
            }
        }
    }

    private void freeEmptyGroups() {
        Trace.beginSection("ShadeListBuilder.freeEmptyGroups");
        mGroups.values().removeIf(ge -> ge.getSummary() == null && ge.getChildren().isEmpty());
        Trace.endSection();
    }

    private void logChanges() {
        Trace.beginSection("ShadeListBuilder.logChanges");
        for (NotificationEntry entry : mAllEntries) {
            logAttachStateChanges(entry);
        }
        for (GroupEntry group : mGroups.values()) {
            logAttachStateChanges(group);
        }
        Trace.endSection();
    }

    private void logAttachStateChanges(PipelineEntry entry) {

        final ListAttachState curr = entry.getAttachState();
        final ListAttachState prev = entry.getPreviousAttachState();

        if (!Objects.equals(curr, prev)) {
            mLogger.logEntryAttachStateChanged(
                    mIterationCount,
                    entry,
                    prev.getParent(),
                    curr.getParent());

            if (curr.getParent() != prev.getParent()) {
                mLogger.logParentChanged(mIterationCount, prev.getParent(), curr.getParent());
            }

            PipelineEntry currSuppressedParent = curr.getSuppressedChanges().getParent();
            PipelineEntry prevSuppressedParent = prev.getSuppressedChanges().getParent();
            if (currSuppressedParent != null && (prevSuppressedParent == null
                    || !prevSuppressedParent.getKey().equals(currSuppressedParent.getKey()))) {
                mLogger.logParentChangeSuppressedStarted(
                        mIterationCount,
                        currSuppressedParent,
                        curr.getParent());
            }
            if (prevSuppressedParent != null && currSuppressedParent == null) {
                mLogger.logParentChangeSuppressedStopped(
                        mIterationCount,
                        prevSuppressedParent,
                        prev.getParent());
            }

            if (curr.getSuppressedChanges().getSection() != null) {
                mLogger.logSectionChangeSuppressed(
                        mIterationCount,
                        curr.getSuppressedChanges().getSection(),
                        curr.getSection());
            }

            if (curr.getSuppressedChanges().getWasPruneSuppressed()) {
                mLogger.logGroupPruningSuppressed(
                        mIterationCount,
                        curr.getParent());
            }

            if (!Objects.equals(curr.getGroupPruneReason(), prev.getGroupPruneReason())) {
                mLogger.logPrunedReasonChanged(
                        mIterationCount,
                        prev.getGroupPruneReason(),
                        curr.getGroupPruneReason());
            }

            if (curr.getExcludingFilter() != prev.getExcludingFilter()) {
                mLogger.logFilterChanged(
                        mIterationCount,
                        prev.getExcludingFilter(),
                        curr.getExcludingFilter());
            }

            // When something gets detached, its promoter and section are always set to null, so
            // don't bother logging those changes.
            final boolean wasDetached = curr.getParent() == null && prev.getParent() != null;

            if (!wasDetached && curr.getPromoter() != prev.getPromoter()) {
                mLogger.logPromoterChanged(
                        mIterationCount,
                        prev.getPromoter(),
                        curr.getPromoter());
            }

            if (!wasDetached && curr.getSection() != prev.getSection()) {
                mLogger.logSectionChanged(
                        mIterationCount,
                        prev.getSection(),
                        curr.getSection());
            }
        }
    }

    private void onBeginRun() {
        getStabilityManager().onBeginRun();
    }

    private void cleanupPluggables() {
        Trace.beginSection("ShadeListBuilder.cleanupPluggables");
        callOnCleanup(mNotifPreGroupFilters);
        callOnCleanup(mNotifPromoters);
        callOnCleanup(mNotifFinalizeFilters);
        callOnCleanup(mNotifComparators);

        for (int i = 0; i < mNotifSections.size(); i++) {
            final NotifSection notifSection = mNotifSections.get(i);
            notifSection.getSectioner().onCleanup();
            final NotifComparator comparator = notifSection.getComparator();
            if (comparator != null) {
                comparator.onCleanup();
            }
        }

        callOnCleanup(List.of(getStabilityManager()));
        Trace.endSection();
    }

    private void callOnCleanup(List<? extends Pluggable<?>> pluggables) {
        for (int i = 0; i < pluggables.size(); i++) {
            pluggables.get(i).onCleanup();
        }
    }

    @Nullable
    private NotifComparator getSectionComparator(
            @NonNull PipelineEntry o1, @NonNull PipelineEntry o2) {
        // Sections should be able to sort any PipelineEntry, including bundles
        final NotifSection section = o1.getSection();
        if (section != o2.getSection()) {
            throw new RuntimeException("Entry ordering should only be done within sections");
        }
        if (section != null) {
            return section.getComparator();
        }
        return null;
    }

    private final Comparator<PipelineEntry> mTopLevelComparator = (o1, o2) -> {
        int cmp = Integer.compare(
                o1.getSectionIndex(),
                o2.getSectionIndex());
        if (cmp != 0) return cmp;

        NotifComparator sectionComparator = getSectionComparator(o1, o2);
        if (sectionComparator != null) {
            cmp = sectionComparator.compare(o1, o2);
            if (cmp != 0) return cmp;
        }

        for (int i = 0; i < mNotifComparators.size(); i++) {
            cmp = mNotifComparators.get(i).compare(o1, o2);
            if (cmp != 0) return cmp;
        }

        cmp = Integer.compare(getRanking(o1), getRanking(o2));
        if (cmp != 0) return cmp;

        cmp = -1 * Long.compare(getWhen(o1), getWhen(o2));
        return cmp;
    };

    private static int getRanking(PipelineEntry pipelineEntry) {
        final ListEntry listEntry = pipelineEntry.asListEntry();
        return listEntry != null
                ? listEntry.getRepresentativeEntry().getRanking().getRank()
                // Rank bundles last
                : Integer.MAX_VALUE;
    }

    private static long getWhen(PipelineEntry pipelineEntry) {
        final ListEntry listEntry = pipelineEntry.asListEntry();
        return listEntry != null
                ? listEntry.getRepresentativeEntry().getSbn().getNotification().getWhen()
                // Treat bundles as oldest
                : Integer.MIN_VALUE;
    }

    private final Comparator<NotificationEntry> mGroupChildrenComparator = (o1, o2) -> {
        int cmp = Integer.compare(
                o1.getRepresentativeEntry().getRanking().getRank(),
                o2.getRepresentativeEntry().getRanking().getRank());
        if (cmp != 0) return cmp;

        cmp = -1 * Long.compare(
                o1.getRepresentativeEntry().getSbn().getNotification().getWhen(),
                o2.getRepresentativeEntry().getSbn().getNotification().getWhen());
        return cmp;
    };

    private final Comparator<ListEntry> mBundleChildrenComparator = (o1, o2) -> {
        int cmp = Integer.compare(
                o1.getRepresentativeEntry().getRanking().getRank(),
                o2.getRepresentativeEntry().getRanking().getRank());
        if (cmp != 0) return cmp;

        cmp = -1 * Long.compare(
                o1.getRepresentativeEntry().getSbn().getNotification().getWhen(),
                o2.getRepresentativeEntry().getSbn().getNotification().getWhen());
        return cmp;
    };

    @Nullable
    private Integer getStableOrderRank(PipelineEntry entry) {
        if (getStabilityManager().isEntryReorderingAllowed(entry)) {
            // let the stability manager constrain or allow reordering
            return null;
        }
        if (entry.getAttachState().getSectionIndex()
                != entry.getPreviousAttachState().getSectionIndex()) {
            // stable index is only valid within the same section; otherwise we allow reordering
            return null;
        }
        final int stableIndex = entry.getPreviousAttachState().getStableIndex();
        return stableIndex == -1 ? null : stableIndex;
    }

    private static void debugFilterLog(String s) {
        if (DEBUG_FILTER) {
            android.util.Log.d(TAG, s);
        }
    }

    private boolean applyFilters(NotificationEntry entry, long now, List<NotifFilter> filters) {
        final NotifFilter filter = findRejectingFilter(entry, now, filters);
        entry.getAttachState().setExcludingFilter(filter);
        if (filter != null) {
            // notification is removed from the list, so we reset its initialization time
            if (NotificationBundleUi.isEnabled()) {
                if (entry.getRow() != null) {
                    entry.getRow().resetInitializationTime();
                }
            } else {
                entry.resetInitializationTime();
            }
        }
        return filter != null;
    }

    @Nullable
    private static NotifFilter findRejectingFilter(NotificationEntry entry, long now,
            List<NotifFilter> filters) {
        final int size = filters.size();

        for (int i = 0; i < size; i++) {
            NotifFilter filter = filters.get(i);
            if (filter.shouldFilterOut(entry, now)) {
                debugFilterLog("findRejectingFilter: " + filter.getName() + " rejects: "
                        + entry.getKey());
                return filter;
            }
            debugFilterLog("findRejectingFilter: " + filter.getName() + " pass: "
                    + entry.getKey());
        }
        return null;
    }

    private boolean applyTopLevelPromoters(NotificationEntry entry) {
        NotifPromoter promoter = findPromoter(entry);
        entry.getAttachState().setPromoter(promoter);
        return promoter != null;
    }

    @Nullable
    private NotifPromoter findPromoter(NotificationEntry entry) {
        for (int i = 0; i < mNotifPromoters.size(); i++) {
            NotifPromoter promoter = mNotifPromoters.get(i);
            if (promoter.shouldPromoteToTopLevel(entry)) {
                return promoter;
            }
        }
        return null;
    }

    private NotifSection applySections(PipelineEntry entry) {
        final NotifSection newSection = findSection(entry);
        final ListAttachState prevAttachState = entry.getPreviousAttachState();

        NotifSection finalSection = newSection;

        final ListEntry listEntry = entry.asListEntry();

        // have we seen this entry before and are we changing its section?
        if (listEntry != null && listEntry.wasAttachedInPreviousPass()
                && newSection != prevAttachState.getSection()) {

            // are section changes allowed?
            if (!getStabilityManager().isSectionChangeAllowed(listEntry.getRepresentativeEntry())) {
                // record the section that we wanted to change to
                entry.getAttachState().getSuppressedChanges().setSection(newSection);

                // keep the previous section
                finalSection = prevAttachState.getSection();
            }
        }

        setEntrySection(entry, finalSection);
        return finalSection;
    }

    private void setEntrySection(PipelineEntry entry, NotifSection finalSection) {
        entry.getAttachState().setSection(finalSection);
        final ListEntry listEntry = entry.asListEntry();
        if (listEntry == null) {
            return;
        }
        final NotificationEntry representativeEntry = listEntry.getRepresentativeEntry();
        if (representativeEntry != null) {
            representativeEntry.getAttachState().setSection(finalSection);
            if (finalSection != null) {
                representativeEntry.setBucket(finalSection.getBucket());
            }
        }
    }

    @NonNull
    private NotifSection findSection(PipelineEntry entry) {
        for (int i = 0; i < mNotifSections.size(); i++) {
            NotifSection section = mNotifSections.get(i);
            if (section.getSectioner().isInSection(entry)) {
                return section;
            }
        }
        throw new RuntimeException("Missing default sectioner!");
    }

    private void rebuildListIfBefore(@PipelineState.StateName int rebuildState) {
        final @PipelineState.StateName int currentState = mPipelineState.getState();

        // If the pipeline is idle, requesting an invalidation is always okay, and starts a new run.
        if (currentState == STATE_IDLE) {
            scheduleRebuild(/* reentrant = */ false, rebuildState);
            return;
        }

        // If the pipeline is running, it is okay to request an invalidation of a *later* stage.
        // Since the current pipeline run hasn't run it yet, no new pipeline run is needed.
        if (rebuildState > currentState) {
            return;
        }

        // If the pipeline is running, it is bad to request an invalidation of *earlier* stages or
        // the *current* stage; this will run the pipeline more often than needed, and may even
        // cause an infinite loop of pipeline runs.
        //
        // Unfortunately, there are some unfixed bugs that cause reentrant pipeline runs, so we keep
        // a counter and allow a few reentrant runs in a row between any two non-reentrant runs.
        //
        // It is technically possible for a *pair* of invalidations, one reentrant and one not, to
        // trigger *each other*, alternating responsibility for pipeline runs in an infinite loop
        // but constantly resetting the reentrant run counter. Hopefully that doesn't happen.
        scheduleRebuild(/* reentrant = */ true, rebuildState);
    }

    private void scheduleRebuild(boolean reentrant) {
        scheduleRebuild(reentrant, STATE_IDLE);
    }

    private void scheduleRebuild(boolean reentrant, @PipelineState.StateName int rebuildState) {
        if (!reentrant) {
            mConsecutiveReentrantRebuilds = 0;
            mChoreographer.schedule();
            return;
        }

        final @PipelineState.StateName int currentState = mPipelineState.getState();

        final String rebuildStateName = PipelineState.getStateName(rebuildState);
        final String currentStateName = PipelineState.getStateName(currentState);
        final IllegalStateException exception = new IllegalStateException(
                "Reentrant notification pipeline rebuild of state " + rebuildStateName
                        + " while pipeline in state " + currentStateName + ".");

        mConsecutiveReentrantRebuilds++;

        if (mConsecutiveReentrantRebuilds > MAX_CONSECUTIVE_REENTRANT_REBUILDS) {
            Log.e(TAG, "Crashing after more than " + MAX_CONSECUTIVE_REENTRANT_REBUILDS
                    + " consecutive reentrant notification pipeline rebuilds.", exception);
            throw exception;
        }

        Log.wtf(TAG, "Allowing " + mConsecutiveReentrantRebuilds
                + " consecutive reentrant notification pipeline rebuild(s).", exception);
        mChoreographer.schedule();
    }

    private static void logEndBuildList(
            ShadeListBuilderLogger logger,
            int iterationCount,
            List<PipelineEntry> shadeList,
            boolean enforcedVisualStability) {
        Trace.beginSection("ShadeListBuilder.logEndBuildList");
        final int numTopLevelEntries = shadeList.size();
        int bundledCount = 0;
        int bundledChildCount = 0;
        int childCount = 0;
        for (int i = 0; i < numTopLevelEntries; i++) {
            final PipelineEntry entry = shadeList.get(i);
            if (entry instanceof GroupEntry groupEntry) {
                childCount += groupEntry.getChildren().size();
            } else if (entry instanceof BundleEntry bundleEntry) {
                int numBundleChildren = bundleEntry.getChildren().size();
                bundledCount += numBundleChildren;
                for (int j = 0; j < numBundleChildren; j++) {
                    final ListEntry bundleChild = bundleEntry.getChildren().get(j);
                    if (bundleChild instanceof GroupEntry bundleChildGroup) {
                        bundledChildCount += bundleChildGroup.getChildren().size();
                    }
                }
            }
        }

        logger.logEndBuildList(
                iterationCount,
                numTopLevelEntries,
                childCount,
                bundledCount,
                bundledChildCount,
                /* enforcedVisualStability */ enforcedVisualStability);
        Trace.endSection();
    }

    private void dispatchOnBeforeTransformGroups(List<PipelineEntry> entries) {
        Trace.beginSection("ShadeListBuilder.dispatchOnBeforeTransformGroups");
        mOnBeforeTransformGroupsListeners.forEachTraced(listener -> {
            listener.onBeforeTransformGroups(entries);
        });
        Trace.endSection();
    }

    private void dispatchOnBeforeSort(List<PipelineEntry> entries) {
        Trace.beginSection("ShadeListBuilder.dispatchOnBeforeSort");
        mOnBeforeSortListeners.forEachTraced(listener -> {
            listener.onBeforeSort(entries);
        });
        Trace.endSection();
    }

    private void dispatchOnBeforeFinalizeFilter(List<PipelineEntry> entries) {
        Trace.beginSection("ShadeListBuilder.dispatchOnBeforeFinalizeFilter");
        mOnBeforeFinalizeFilterListeners.forEachTraced(listener -> {
            listener.onBeforeFinalizeFilter(entries);
        });
        Trace.endSection();
    }

    private void dispatchOnBeforeRenderList(List<PipelineEntry> entries) {
        Trace.beginSection("ShadeListBuilder.dispatchOnBeforeRenderList");
        mOnBeforeRenderListListeners.forEachTraced(listener -> {
            listener.onBeforeRenderList(entries);
        });
        Trace.endSection();
    }

    @Override
    public void dump(PrintWriter pw, @NonNull String[] args) {
        pw.println("\t" + TAG + " shade notifications:");
        if (getShadeList().size() == 0) {
            pw.println("\t\t None");
        }

        pw.println(ListDumper.dumpTree(
                getShadeList(),
                mInteractionTracker,
                true,
                "\t\t"));
    }

    @Override
    public void dumpPipeline(@NonNull PipelineDumper d) {
        d.dump("choreographer", mChoreographer);
        d.dump("notifPreGroupFilters", mNotifPreGroupFilters);
        d.dump("onBeforeTransformGroupsListeners", mOnBeforeTransformGroupsListeners);
        d.dump("notifPromoters", mNotifPromoters);
        d.dump("onBeforeSortListeners", mOnBeforeSortListeners);
        d.dump("notifSections", mNotifSections);
        d.dump("notifComparators", mNotifComparators);
        d.dump("onBeforeFinalizeFilterListeners", mOnBeforeFinalizeFilterListeners);
        d.dump("notifFinalizeFilters", mNotifFinalizeFilters);
        d.dump("onBeforeRenderListListeners", mOnBeforeRenderListListeners);
        d.dump("onRenderListListener", mOnRenderListListener);
    }

    /** See {@link #setOnRenderListListener(OnRenderListListener)} */
    public interface OnRenderListListener {
        /**
         * Called with the final filtered, grouped, and sorted list.
         *
         * @param entries A read-only view into the current notif list. Note that this list is
         *                backed by the live list and will change in response to new pipeline runs.
         */
        void onRenderList(@NonNull List<PipelineEntry> entries);
    }

    private static final NotifSectioner DEFAULT_SECTIONER = new NotifSectioner("UnknownSection",
            NotificationPriorityBucketKt.BUCKET_UNKNOWN) {
        @Override
        public boolean isInSection(PipelineEntry entry) {
            return true;
        }
    };

    private static final int MIN_CHILDREN_FOR_GROUP = 2;

    private static final String TAG = "ShadeListBuilder";
}
