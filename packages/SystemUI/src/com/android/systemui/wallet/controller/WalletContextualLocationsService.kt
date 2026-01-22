package com.android.systemui.wallet.controller

import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Serves as an intermediary between QuickAccessWalletService and ContextualCardManager (in PCC).
 * When QuickAccessWalletService has a list of store locations, WalletContextualLocationsService
 * will send them to ContextualCardManager. When the user enters a store location, this Service
 * class will be notified, and WalletContextualSuggestionsController will be updated.
 */
class WalletContextualLocationsService
@Inject
constructor(
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val controller: WalletContextualSuggestionsController,
    private val featureFlags: FeatureFlags,
) : LifecycleService() {
    private var listener: IWalletCardsUpdatedListener? = null
    private var scope: CoroutineScope = this.lifecycleScope

    @VisibleForTesting
    constructor(
        dispatcher: CoroutineDispatcher,
        controller: WalletContextualSuggestionsController,
        featureFlags: FeatureFlags,
        scope: CoroutineScope,
    ) : this(dispatcher, controller, featureFlags) {
        this.scope = scope
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        scope.launch(context = backgroundDispatcher) {
            controller.allWalletCards.collect { cards ->
                val cardsSize = cards.size
                Log.i(TAG, "Number of cards registered $cardsSize")
                try {
                    listener?.registerNewWalletCards(cards)
                } catch (e: DeadObjectException) {
                    Log.e(
                        TAG,
                        "Failed to register wallet cards because IWalletCardsUpdatedListener is dead",
                    )
                }
            }
        }
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        listener = null
    }

    @VisibleForTesting
    fun addWalletCardsUpdatedListenerInternal(listener: IWalletCardsUpdatedListener) {
        if (!featureFlags.isEnabled(Flags.ENABLE_WALLET_CONTEXTUAL_LOYALTY_CARDS)) {
            return
        }
        this.listener = listener // Currently, only one listener at a time is supported
        // Sends WalletCard objects from QuickAccessWalletService to the listener
        val cards = controller.allWalletCards.value
        if (!cards.isEmpty()) {
            val cardsSize = cards.size
            Log.i(TAG, "Number of cards registered $cardsSize")
            listener.registerNewWalletCards(cards)
        }
    }

    @VisibleForTesting
    fun onWalletContextualLocationsStateUpdatedInternal(storeLocations: List<String>) {
        if (!featureFlags.isEnabled(Flags.ENABLE_WALLET_CONTEXTUAL_LOYALTY_CARDS)) {
            return
        }
        Log.i(TAG, "Entered store $storeLocations")
        controller.setSuggestionCardIds(storeLocations.toSet())
    }

    private val binder: IWalletContextualLocationsService.Stub =
        object : IWalletContextualLocationsService.Stub() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                // Maybe replace the runtime check with a new Android permission.
                if (!isCallerAllowed()) {
                    throw SecurityException("Caller is not allowed.")
                }

                return super.onTransact(code, data, reply, flags)
            }

            override fun addWalletCardsUpdatedListener(listener: IWalletCardsUpdatedListener) {
                addWalletCardsUpdatedListenerInternal(listener)
            }

            override fun onWalletContextualLocationsStateUpdated(storeLocations: List<String>) {
                onWalletContextualLocationsStateUpdatedInternal(storeLocations)
            }
        }

    /**
     * Validates that the caller is authorized to interact with this service.
     *
     * Security is enforced by checking two conditions:
     * 1. The caller's package must hold the `android.app.role.SYSTEM_UI_INTELLIGENCE` role.
     * 2. The caller must be a privileged app.
     */
    private fun isCallerAllowed(): Boolean {
        val uid = Binder.getCallingUid()
        val identity = Binder.clearCallingIdentity()
        try {
            val packages = packageManager.getPackagesForUid(uid)
            if (packages.isNullOrEmpty()) return false

            val roleManager = getSystemService(RoleManager::class.java)
            val roleHolders =
                roleManager?.getRoleHolders(ROLE_SYSTEM_UI_INTELLIGENCE) ?: emptyList()

            return packages.any { packageName ->
                (packageName in roleHolders) &&
                    try {
                        val appInfo = packageManager.getApplicationInfo(packageName, 0)
                        appInfo.isPrivilegedApp()
                    } catch (e: PackageManager.NameNotFoundException) {
                        false
                    }
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    companion object {
        private const val TAG = "WalletContextualLocationsService"
        private const val ROLE_SYSTEM_UI_INTELLIGENCE = "android.app.role.SYSTEM_UI_INTELLIGENCE"
    }
}
