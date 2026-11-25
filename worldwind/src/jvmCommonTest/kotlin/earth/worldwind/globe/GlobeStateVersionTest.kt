package earth.worldwind.globe

import earth.worldwind.geom.Ellipsoid
import earth.worldwind.globe.projection.MercatorProjection
import earth.worldwind.globe.projection.PolarEquidistantProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [Globe.stateVersion] is the version of the last [Globe.state] read (the engine reads it once per frame): it changes
 * exactly when the state compares unequal to the previous one, and is never shared between globes, so caches can keep
 * the number instead of a state object.
 */
class GlobeStateVersionTest {
    /** The version of the current state, as a frame sees it after the engine read the state. */
    private fun Globe.frameVersion(): Long {
        state
        return stateVersion
    }

    @Test
    fun testSetWhenCreated() {
        assertNotEquals(0L, Globe().stateVersion)
    }

    @Test
    fun testStableWhileUnchanged() {
        val globe = Globe()
        val version = globe.frameVersion()
        repeat(3) { assertEquals(version, globe.frameVersion()) }
        // Settings outside the state do not change it
        globe.verticalExaggeration = 2.0
        globe.offset = Globe.Offset.Right
        assertEquals(version, globe.frameVersion())
    }

    @Test
    fun testChangesWithEachStateInput() {
        val globe = Globe()
        val versions = mutableListOf(globe.frameVersion())
        globe.projection = MercatorProjection()
        versions += globe.frameVersion()
        globe.ellipsoid = Ellipsoid.Krasovsky
        versions += globe.frameVersion()
        // A projection renamed in place, as the state compares projection names
        val polar = PolarEquidistantProjection()
        globe.projection = polar
        versions += globe.frameVersion()
        polar.isNorth = false
        versions += globe.frameVersion()
        assertEquals(versions.size, versions.toSet().size, "every change gives a new version: $versions")
    }

    @Test
    fun testUpdatedByReadingTheState() {
        // A frame's snapshot: a change shows in the version once the next frame reads the state
        val globe = Globe()
        val version = globe.frameVersion()
        globe.projection = MercatorProjection()
        assertEquals(version, globe.stateVersion, "not before the state is read")
        assertNotEquals(version, globe.frameVersion())
    }

    @Test
    fun testNotSharedBetweenGlobes() {
        val first = Globe()
        val second = Globe()
        assertEquals(first.state, second.state)
        assertNotEquals(first.stateVersion, second.stateVersion)
        assertNotEquals(first.id, second.id)
    }
}
