package af.shizuku.manager.home

import android.os.Build
import com.airbnb.mvrx.withState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.management.AppsViewModel
import af.shizuku.manager.model.ServiceStatus
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.common.util.UserHandleCompat
import af.shizuku.manager.R
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.IdBasedRecyclerViewAdapter
import rikka.recyclerview.IndexCreatorPool

class HomeAdapter(
    private val homeModel: HomeViewModel,
    private val appsModel: AppsViewModel,
    private val scope: CoroutineScope
) : IdBasedRecyclerViewAdapter(ArrayList()) {

    companion object {
        const val ID_STATUS = 0L
        const val ID_APPS = 1L
        const val ID_TERMINAL = 2L
        const val ID_START_ROOT = 3L
        const val ID_START_WADB = 4L
        const val ID_START_ADB = 5L
        const val ID_LEARN_MORE = 6L
        const val ID_ADB_PERMISSION_LIMITED = 7L
        const val ID_AUTOMATION = 8L
        const val ID_COMPANION = 9L
        const val ID_START_VIA_STOCK = 10L
        const val ID_BOOT_SETUP = 11L
        // Upstream's App Backup card arrived at r2431 claiming 11L, which this fork had already
        // spent on ID_BOOT_SETUP and 12L on ID_RISH — both persisted in saved card orders. Upstream's
        // new card takes the next free id instead; nothing references these numerically.
        const val ID_BACKUP = 13L

        private val DEFAULT_ORDER = listOf(
            ID_TERMINAL, ID_START_ROOT, ID_BOOT_SETUP, ID_START_WADB, ID_START_ADB, ID_AUTOMATION,
            ID_BACKUP, ID_LEARN_MORE, ID_COMPANION
        )
    }

    private val cardOrder: MutableList<Long> = run {
        val saved = ShizukuSettings.getCardOrder()
        if (saved.isNullOrEmpty()) {
            DEFAULT_ORDER.toMutableList()
        } else {
            val parsed = saved.split(",").mapNotNull { it.trim().toLongOrNull() }
            val merged = parsed.toMutableList()
            var migrated = false
            // Merge in ids the saved order predates. Appending them — what this used to do — drops
            // every new card at the bottom of the home screen on existing installs, wherever it
            // actually belongs: the boot-setup card has to sit *above* the wireless-adb card, since
            // it is what makes that card's start survive a reboot. So a missing id goes in front of
            // the first card that follows it in DEFAULT_ORDER and is actually present, and only
            // falls back to the end when nothing following it is.
            DEFAULT_ORDER.forEachIndexed { index, id ->
                if (id in merged) return@forEachIndexed
                val anchor = DEFAULT_ORDER.drop(index + 1)
                    .firstNotNullOfOrNull { next -> merged.indexOf(next).takeIf { it >= 0 } }
                if (anchor != null) merged.add(anchor, id) else merged.add(id)
                migrated = true
            }
            // Persist at once: the placement is a one-time migration, so leaving it unsaved would
            // redo it on every launch and fight any reorder the user makes afterwards.
            if (migrated) ShizukuSettings.setCardOrder(merged.joinToString(","))
            merged
        }
    }

    private val startWadbCreator = StartWirelessAdbViewHolder.creator(scope, homeModel)
    private val companionCreator = ShizukuCompanionViewHolder.creator(scope, homeModel)
    private val startStockCreator = StartStockShizukuViewHolder.creator(scope)
    private val bootSetupCreator = BootSetupViewHolder.creator(scope)
    private val serverStatusCreator = ServerStatusViewHolder.creator(scope)

    var isDragging = false
    private var isUpdating = false
    private var lastUpdateDataTime = 0L
    private var pendingUpdate = false
    private val animatedIds = HashSet<Long>()

    // Cached inputs from the last successful updateData() render, so moveItem() can rebuild the
    // adapter's real backing list synchronously mid-drag instead of only reordering cardOrder.
    private var lastRenderStatus: ServiceStatus? = null
    private var lastRenderGrantedCount: Int? = null
    private var lastRenderIsEditMode: Boolean = false
    private var lastRenderCompanionInstalled: Boolean = false
    private var lastRenderCompatHubInstalled: Boolean = false
    private var lastRenderIsOriginalShizukuRunning: Boolean = false
    private var lastRenderHidden: Set<String> = emptySet()

    /**
     * Callback to notify when the empty state should be shown/hidden.
     * @param isEmpty true if there are no visible cards (excluding fixed status card)
     */
    var onEmptyStateChanged: ((Boolean) -> Unit)? = null

    init {
        setHasStableIds(true)
        HomeEditMode.onChanged = { updateData() }
        HomeEditMode.removeCardCallback = { cardId ->
            val hiddenSet = ShizukuSettings.getHiddenHomeCards().toMutableSet()
            if (cardId.toString() in hiddenSet) {
                hiddenSet.remove(cardId.toString())
            } else {
                hiddenSet.add(cardId.toString())
            }
            ShizukuSettings.setHiddenHomeCards(hiddenSet)
            HomeEditMode.exit()
            updateData()
        }
    }

    /**
     * Restores every hidden card at once. Wired to the "Restore cards" button on the
     * all-cards-hidden empty state, whose copy promises "Go to Settings to restore them" / a
     * bulk restore - it previously only called HomeEditMode.enter(), which doesn't unhide
     * anything on its own and left the user to un-hide each card individually via its "+"
     * toggle, contradicting what the button said it would do.
     */
    fun restoreAllCards() {
        if (ShizukuSettings.getHiddenHomeCards().isEmpty()) return
        ShizukuSettings.setHiddenHomeCards(emptySet())
        updateData()
    }

    override fun onCreateCreatorPool(): IndexCreatorPool = IndexCreatorPool()

    fun updateData() {
        val now = System.currentTimeMillis()
        // On start the state changes in a rapid burst (Loading -> Success, plus state-listener and
        // onResume reloads). Dropping throttled/in-flight requests loses the final "running" render,
        // leaving a stale "not running" card until the user re-enters the screen. Instead of
        // dropping, coalesce into a single trailing update so the latest state is always rendered.
        if (isUpdating || now - lastUpdateDataTime < 100) {
            if (!pendingUpdate) {
                pendingUpdate = true
                scope.launch {
                    kotlinx.coroutines.delay(120)
                    pendingUpdate = false
                    updateData()
                }
            }
            return
        }
        lastUpdateDataTime = now
        isUpdating = true
        scope.launch {
            val (status, grantedCount, isEditMode) = withState(homeModel) {
                Triple(it.serviceStatus.invoke(), it.grantedAppCount, it.isEditMode)
            }
            val companionInstalled = withState(homeModel) { it.companionInstalled }
            val compatHubInstalled = withState(homeModel) { it.compatHubInstalled }
            val isOriginalShizukuRunning = withState(homeModel) { it.isOriginalShizukuRunning }

            if (status == null) {
                isUpdating = false
                return@launch
            }

            val hidden = ShizukuSettings.getHiddenHomeCards()

            withContext(Dispatchers.Main) {
                if (isDragging) {
                    isUpdating = false
                    return@withContext
                }

                lastRenderStatus = status
                lastRenderGrantedCount = grantedCount
                lastRenderIsEditMode = isEditMode
                lastRenderCompanionInstalled = companionInstalled
                lastRenderCompatHubInstalled = compatHubInstalled
                lastRenderIsOriginalShizukuRunning = isOriginalShizukuRunning
                lastRenderHidden = hidden

                val fixedCardCount = rebuildItems(
                    status, grantedCount, isEditMode, companionInstalled, compatHubInstalled,
                    isOriginalShizukuRunning, hidden
                )

                notifyDataSetChanged()

                val hasVisibleCards = itemCount > fixedCardCount
                onEmptyStateChanged?.invoke(!hasVisibleCards)

                isUpdating = false
            }
        }
    }

    /**
     * Clears and repopulates the adapter's backing item list in cardOrder sequence. Must run on
     * the main thread. Returns the number of fixed (non-draggable) cards added. Does not call
     * notifyDataSetChanged()/notifyItemMoved() — callers are responsible for the right notify.
     */
    private fun rebuildItems(
        status: ServiceStatus,
        grantedCount: Int?,
        isEditMode: Boolean,
        companionInstalled: Boolean,
        compatHubInstalled: Boolean,
        isOriginalShizukuRunning: Boolean,
        hidden: Set<String>
    ): Int {
        val adbPermission = status.permission
        val running = status.isRunning
        val isPrimaryUser = UserHandleCompat.myUserId() == 0
        val rootRestart = running && status.uid == 0

        clear()

        // Fixed cards
        var fixedCardCount = 0
        addItem(serverStatusCreator, status, ID_STATUS); fixedCardCount++
        if (isOriginalShizukuRunning) {
            addItem(startStockCreator, null, ID_START_VIA_STOCK); fixedCardCount++
        }
        if (adbPermission) {
            addItem(ManageAppsViewHolder.CREATOR, status to grantedCount, ID_APPS); fixedCardCount++
        }
        if (running && !adbPermission) {
            addItem(AdbPermissionLimitedViewHolder.CREATOR, status, ID_ADB_PERMISSION_LIMITED); fixedCardCount++
        }

        // Draggable cards
        cardOrder.forEach { id ->
            val isHidden = id.toString() in hidden
            if (isHidden && !isEditMode) return@forEach
            when (id) {
                ID_TERMINAL -> if (isEditMode || ShizukuSettings.showTerminalHome())
                    addItem(TerminalViewHolder.CREATOR, status, id)
                ID_START_ROOT -> if (isEditMode || (isPrimaryUser && (EnvironmentUtils.isRooted() || ShizukuSettings.isSamsungSystemUidEscalationEnabled())))
                    addItem(StartRootViewHolder.CREATOR, rootRestart, id)
                // Same gate as the wireless-adb card it sits above — the checklist only makes
                // sense where that start path exists at all.
                ID_BOOT_SETUP -> if (isEditMode || (isPrimaryUser && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || EnvironmentUtils.getAdbTcpPort() > 0)))
                    addItem(bootSetupCreator, null, id)
                ID_START_WADB -> if (isEditMode || (isPrimaryUser && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || EnvironmentUtils.getAdbTcpPort() > 0)))
                    addItem(startWadbCreator, null, id)
                ID_START_ADB -> if (isEditMode || (isPrimaryUser && ShizukuSettings.showStartAdbHome()))
                    addItem(StartAdbViewHolder.CREATOR, null, id)
                ID_AUTOMATION -> if (isEditMode || ShizukuSettings.showAutomationHome())
                    addItem(AutomationViewHolder.CREATOR, null, id)
                ID_BACKUP -> if (isEditMode || ShizukuSettings.showBackupHome())
                    addItem(AppBackupViewHolder.CREATOR, status, id)
                ID_LEARN_MORE -> if (isEditMode || ShizukuSettings.showLearnMoreHome())
                    addItem(LearnMoreViewHolder.CREATOR, null, id)
                ID_COMPANION -> {
                    // The compat hub is what lets third-party apps detect Shizuku+, so surface
                    // this card whenever it still needs action — the hub isn't installed yet, or
                    // stock Shizuku is present and conflicts — not only when companion mode is on.
                    // Otherwise (hub installed, no conflict) it stays opt-in via companion mode.
                    val needsAction = !compatHubInstalled || companionInstalled
                    if (isEditMode || ShizukuSettings.isCompanionModeEnabled() || needsAction)
                        addItem(companionCreator, Pair(companionInstalled, compatHubInstalled), id)
                }
            }
        }

        return fixedCardCount
    }

    override fun onBindViewHolder(holder: BaseViewHolder<*>, position: Int) {
        val id = getItemId(position)
        val hidden = ShizukuSettings.getHiddenHomeCards()
        holder.itemView.tag = id.toString() in hidden

        val removeBtn = holder.itemView.findViewById<android.view.View>(R.id.remove_btn)
        removeBtn?.setOnClickListener {
            HomeEditMode.removeCardCallback?.invoke(id)
        }

        super.onBindViewHolder(holder, position)

        // M3E entrance animation — only on first appearance per card id, not every recycle.
        if (!animatedIds.add(id)) {
            holder.itemView.alpha = 1f
            holder.itemView.translationY = 0f
            holder.itemView.scaleX = 1f
            holder.itemView.scaleY = 1f
            holder.itemView.translationZ = 0f
            return
        }
        val view = holder.itemView
        view.alpha = 0f
        view.translationY = 24f
        view.scaleX = 0.92f
        view.scaleY = 0.92f
        val animator = view.animate()
        if (animator != null) {
            val interp = android.view.animation.AnimationUtils.loadInterpolator(
                view.context, android.R.interpolator.fast_out_slow_in
            )
            animator.alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(af.shizuku.manager.ShizukuSettings.scaledAnimationDuration(400))
                .setStartDelay(af.shizuku.manager.ShizukuSettings.scaledAnimationDuration(position * 50L))
                .setInterpolator(interp)
                .start()
        } else {
            view.alpha = 1f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
        }
    }

    fun moveItem(fromPos: Int, toPos: Int) {
        val fromId = getItemId(fromPos)
        val toId = getItemId(toPos)
        val fromIdx = cardOrder.indexOf(fromId)
        val toIdx = cardOrder.indexOf(toId)
        if (fromIdx >= 0 && toIdx >= 0) {
            cardOrder.removeAt(fromIdx)
            cardOrder.add(toIdx, fromId)
        }
        // notifyItemMoved requires the backing list to already reflect the new order (stable IDs
        // are on); rebuild it synchronously here rather than relying on updateData(), which is
        // gated by isDragging during the drag gesture.
        val status = lastRenderStatus
        if (status != null) {
            rebuildItems(
                status, lastRenderGrantedCount, lastRenderIsEditMode, lastRenderCompanionInstalled,
                lastRenderCompatHubInstalled, lastRenderIsOriginalShizukuRunning, lastRenderHidden
            )
        }
        notifyItemMoved(fromPos, toPos)
    }

    fun persistCardOrder() {
        ShizukuSettings.setCardOrder(cardOrder.joinToString(","))
    }

    fun isDraggable(position: Int): Boolean {
        if (position < 0 || position >= itemCount) return false
        return getItemId(position) in DEFAULT_ORDER
    }
    private fun Long.str() = this.toString()
}
