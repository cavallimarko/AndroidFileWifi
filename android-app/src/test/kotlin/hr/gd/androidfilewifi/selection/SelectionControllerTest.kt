package hr.gd.androidfilewifi.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionControllerTest {
    private val items = (0L..19L).toList()

    @Test fun longPressStartsSelectionAndSetsAnchor() {
        val c = SelectionController<Long>()
        c.longPress(items, 5)
        assertEquals(setOf(5L), c.keys)
        assertEquals(5, c.anchorIndex)
    }

    @Test fun secondLongPressSelectsInclusiveRangeForward() {
        val c = SelectionController<Long>()
        c.longPress(items, 3)
        c.longPress(items, 7)
        assertEquals((3L..7L).toSet(), c.keys)
    }

    @Test fun rangeWorksBackwards() {
        val c = SelectionController<Long>()
        c.longPress(items, 10)
        c.longPress(items, 6)
        assertEquals((6L..10L).toSet(), c.keys)
    }

    @Test fun rangeModeTapFirstTapLast() {
        val c = SelectionController<Long>()
        c.rangeMode = true
        c.longPress(items, 2)      // "first"
        c.tap(items, 8)            // "last"
        assertEquals((2L..8L).toSet(), c.keys)
    }

    @Test fun rangeAddsToExistingSelection() {
        val c = SelectionController<Long>()
        c.longPress(items, 0)
        c.tap(items, 1)            // toggle on, anchor = 1
        c.toggle(items, 15)        // anchor = 15
        c.selectRange(items, 12)
        assertEquals(setOf(0L, 1L, 12L, 13L, 14L, 15L), c.keys)
    }

    @Test fun rangeOverFullySelectedDeselects() {
        val c = SelectionController<Long>()
        c.longPress(items, 3)
        c.longPress(items, 6)      // 3..6 selected, anchor 6
        c.selectRange(items, 3)    // all already selected -> deselect 3..6
        assertTrue(c.keys.isEmpty())
        assertNull(c.anchorIndex)
    }

    @Test fun plainTapTogglesAndMovesAnchor() {
        val c = SelectionController<Long>()
        c.longPress(items, 1)
        c.tap(items, 4)
        assertEquals(setOf(1L, 4L), c.keys)
        assertEquals(4, c.anchorIndex)
        c.tap(items, 4)
        assertEquals(setOf(1L), c.keys)
    }

    @Test fun deselectingLastItemEndsSelection() {
        val c = SelectionController<Long>()
        c.longPress(items, 1)
        c.tap(items, 1)
        assertFalse(c.isActive)
        assertNull(c.anchorIndex)
    }

    @Test fun toggleGroupSelectsThenDeselects() {
        val c = SelectionController<Long>()
        val day = listOf(4L, 5L, 6L)
        c.toggleGroup(day)
        assertEquals(day.toSet(), c.keys)
        c.toggleGroup(day)
        assertTrue(c.keys.isEmpty())
    }

    @Test fun retainOnlyDropsHiddenItems() {
        val c = SelectionController<Long>()
        c.selectAll(items)
        c.retainOnly(listOf(1L, 2L))
        assertEquals(setOf(1L, 2L), c.keys)
    }

    @Test fun addRangeForDragSelect() {
        val c = SelectionController<Long>()
        c.longPress(items, 4)
        c.addRange(items, 4, 9)
        assertEquals((4L..9L).toSet(), c.keys)
        assertEquals(9, c.anchorIndex)
    }
}
