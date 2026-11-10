package earth.worldwind.shape

import earth.worldwind.shape.PlacemarkCells.Companion.indexOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Checks [PlacemarkCells]' quadtree addressing: the top-level 90 degree cells and the child chosen at each level.
 */
class PlacemarkCellsIndexTest {
    @Test
    fun topLevelCellsAreNinetyDegrees() {
        // Two rows from the south pole, four columns from the antimeridian.
        assertEquals(0, indexOf(-45.0, -135.0, 0, 0, 0, 2, 4))
        assertEquals(3, indexOf(-45.0, 135.0, 0, 0, 0, 2, 4))
        assertEquals(6, indexOf(48.5, 31.0, 0, 0, 0, 2, 4)) // Ukraine: north row, third column
        assertEquals(7, indexOf(89.9, 179.9, 0, 0, 0, 2, 4))
    }

    @Test
    fun poleAndAntimeridianStayInTheLastCell() {
        assertEquals(7, indexOf(90.0, 180.0, 0, 0, 0, 2, 4))
        assertEquals(0, indexOf(-90.0, -180.0, 0, 0, 0, 2, 4))
    }

    @Test
    fun childIndexFollowsTheLocation() {
        // Level 1 cells are 45 degrees; the parent (row 1, column 2) spans 0..90 N, 0..90 E.
        assertEquals(0, indexOf(10.0, 10.0, 1, 2, 4, 2, 2)) // south-west
        assertEquals(1, indexOf(10.0, 60.0, 1, 2, 4, 2, 2)) // south-east
        assertEquals(2, indexOf(60.0, 10.0, 1, 2, 4, 2, 2)) // north-west
        assertEquals(3, indexOf(60.0, 60.0, 1, 2, 4, 2, 2)) // north-east
    }

    @Test
    fun deepLevelsResolveSmallCells() {
        // Level 12 cells are 90 / 4096 degrees. Two points 0.03 degrees apart fall in different columns.
        val delta = 90.0 / 4096
        val row = ((48.5 + 90.0) / delta).toInt()
        val column = ((31.0 + 180.0) / delta).toInt()
        assertEquals(0, indexOf(48.5, 31.0, 12, row, column, 2, 2))
        assertEquals(1, indexOf(48.5, 31.0 + delta, 12, row, column, 2, 2))
    }
}
