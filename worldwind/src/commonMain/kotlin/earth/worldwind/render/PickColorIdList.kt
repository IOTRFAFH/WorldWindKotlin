package earth.worldwind.render

class PickColorIdList {
    companion object {
        private const val MAX_PICKED_OBJECT_ID = 0xFFFFFF
    }

    private val entries = mutableMapOf<Any, Entry<Any, Int>>()
    private val lruComparator =
        Comparator<Entry<Any, Int>> { lhs, rhs -> lhs.lastUsed.compareTo(rhs.lastUsed) }

    private val freeList = mutableListOf(Range(0, MAX_PICKED_OBJECT_ID))

    private var age = 0L
        // get() = ++field // Auto increment cache age on each access to its entries

    private class Entry<K, V>(val key: K, val value: V) {
        var lastUsed = 0L
    }

    // basically [lowerBound ... upperBound)
    private class Range(private var lowerBound: Int, private var upperBound: Int) {
        fun incrementLowerBound(): Int {
            return lowerBound++
        }

        fun isExhausted(): Boolean {
            return lowerBound == upperBound
        }

        fun contains(index: Int): Boolean {
            return index in lowerBound..<upperBound
        }
    }

    // TODO merge index with existing ranges
    private fun returnIndexToFreeList(index: Int) {
        freeList.add(Range(index, index + 1))
    }

    // TODO probably should return non-null Int, what to do when list is exhausted?
    private fun getIndexFromFreeList(): Int? {
        if (freeList.isEmpty())
            return null
        val range = freeList[0]
        val index = range.incrementLowerBound()
        if (range.isExhausted())
            freeList.remove(range)
        return index
    }

    fun clear() {
        entries.clear()
    }

    fun incAge() {
        ++age
    }

    operator fun get(key: Any) = entries[key]?.run {
        lastUsed = age
        value
    }

    // TODO probably should return non-null Int, what to do when list is exhausted?
    fun put(key: Any): Int? {
        val index = getIndexFromFreeList() ?: return null
        val newEntry = Entry(key, index)
        newEntry.lastUsed = age
        val oldEntry = entries.put(key, newEntry)
        if (oldEntry != null) {
            return oldEntry.value
        }
        return newEntry.value
    }

    fun remove(key: Any): Int? {
        val entry = entries[key] ?: return null
        val index = entry.value
        returnIndexToFreeList(index)
        entries.remove(key)
        return index
    }

    fun trimToAge(maxAge: Long) {
        // Sort the entries from least recently used to most recently used.
        val sortedEntries = assembleSortedEntries()

        // Remove the least recently used entries until the entry's age is within the specified maximum age.
        for (i in sortedEntries.indices) {
            val entry = sortedEntries[i]
            if (entry.lastUsed < (age - maxAge)) {
                remove(entry.key)
            } else break
        }
    }

    /*
    * Sort the entries from least recently used to most recently used.
    */
    private fun assembleSortedEntries() = entries.values.sortedWith(lruComparator)

}