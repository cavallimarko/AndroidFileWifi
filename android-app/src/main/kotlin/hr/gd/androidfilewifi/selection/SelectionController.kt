package hr.gd.androidfilewifi.selection

/**
 * Pure selection logic (no Android dependencies, so it is unit-testable).
 *
 * Items are identified by a key [K]. Range operations work on indices into the list of keys
 * that is currently displayed (after filter / sort), which the caller passes in.
 */
class SelectionController<K> {
    private val selected = LinkedHashSet<K>()
    var anchorIndex: Int? = null
        private set

    /** When true, a plain tap acts like a range-end tap (tap first, tap last). */
    var rangeMode: Boolean = false

    val keys: Set<K> get() = selected
    val size: Int get() = selected.size
    val isActive: Boolean get() = selected.isNotEmpty()

    fun isSelected(key: K) = key in selected

    /** Long-press on an item: starts selection, or completes a range if one is already active. */
    fun longPress(displayed: List<K>, index: Int) {
        if (!selected.isEmpty() && anchorIndex != null) selectRange(displayed, index)
        else {
            selected += displayed[index]
            anchorIndex = index
        }
    }

    /** Tap while selection is active. */
    fun tap(displayed: List<K>, index: Int) {
        if (rangeMode && anchorIndex != null && selected.isNotEmpty()) selectRange(displayed, index)
        else toggle(displayed, index)
    }

    fun toggle(displayed: List<K>, index: Int) {
        val key = displayed[index]
        if (!selected.remove(key)) selected += key
        anchorIndex = if (selected.isEmpty()) null else index
    }

    /**
     * Selects everything between the anchor and [index], inclusive, in either direction.
     * If the whole range is already selected it deselects it instead.
     */
    fun selectRange(displayed: List<K>, index: Int) {
        val anchor = anchorIndex?.coerceIn(0, displayed.lastIndex) ?: run {
            selected += displayed[index]
            anchorIndex = index
            return
        }
        val range = displayed.subList(minOf(anchor, index), maxOf(anchor, index) + 1)
        if (range.all { it in selected }) selected.removeAll(range.toSet()) else selected.addAll(range)
        anchorIndex = index
        if (selected.isEmpty()) anchorIndex = null
    }

    /** Adds every key in the index range without toggling (used by drag-select). */
    fun addRange(displayed: List<K>, from: Int, to: Int) {
        selected.addAll(displayed.subList(minOf(from, to), maxOf(from, to) + 1))
        anchorIndex = to
    }

    fun selectAll(displayed: List<K>) {
        selected.addAll(displayed)
        if (anchorIndex == null && displayed.isNotEmpty()) anchorIndex = 0
    }

    /** Select, or if they are all selected already, deselect the given group (e.g. one day). */
    fun toggleGroup(group: List<K>) {
        if (group.isEmpty()) return
        if (group.all { it in selected }) selected.removeAll(group.toSet()) else selected.addAll(group)
        if (selected.isEmpty()) anchorIndex = null
    }

    fun clear() {
        selected.clear()
        anchorIndex = null
    }

    /** Drops keys that are no longer displayed (e.g. after changing filter). */
    fun retainOnly(displayed: Collection<K>) {
        selected.retainAll(displayed.toSet())
        if (selected.isEmpty()) anchorIndex = null
    }
}
