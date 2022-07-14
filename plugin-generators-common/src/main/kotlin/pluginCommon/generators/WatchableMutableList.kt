/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package pluginCommon.generators

interface WatchableMutableListDelegate<T> {
    fun didAdd(item: T)
    fun didAdd(collection: Collection<T>)
    fun didRemove(item: T)
    fun didRemoveAll(collection: Collection<T>)
}
class WatchableMutableList<T>(val delegate: WatchableMutableListDelegate<T>) : MutableList<T> {
    private val wrappedList = mutableListOf<T>()
    override val size: Int
        get() = wrappedList.size

    override fun contains(element: T): Boolean = wrappedList.contains(element)
    override fun containsAll(elements: Collection<T>): Boolean = wrappedList.containsAll(elements)
    override fun get(index: Int): T = wrappedList.get(index)
    override fun indexOf(element: T): Int = wrappedList.indexOf(element)
    override fun isEmpty(): Boolean = wrappedList.isEmpty()
    override fun iterator(): MutableIterator<T> = wrappedList.iterator()
    override fun lastIndexOf(element: T): Int = wrappedList.lastIndexOf(element)
    override fun add(element: T): Boolean {
        val didAdd = wrappedList.add(element)
        if (didAdd) {
            delegate.didAdd(element)
        }
        return didAdd
    }

    override fun add(index: Int, element: T) {
        wrappedList.add(index, element)
        delegate.didAdd(element)
    }

    override fun addAll(index: Int, elements: Collection<T>): Boolean {
        val didAdd = wrappedList.addAll(index, elements)
        if (didAdd) {
            delegate.didAdd(elements)
        }
        return didAdd
    }

    override fun addAll(elements: Collection<T>): Boolean {
        val didAdd = wrappedList.addAll(elements)
        if (didAdd) {
            delegate.didAdd(elements)
        }
        return didAdd
    }

    override fun clear() = wrappedList.clear()
    override fun listIterator(): MutableListIterator<T> = wrappedList.listIterator()
    override fun listIterator(index: Int): MutableListIterator<T> = wrappedList.listIterator(index)

    override fun remove(element: T): Boolean {
        val didRemove = wrappedList.remove(element)
        if (didRemove) {
            delegate.didRemove(element)
        }
        return didRemove
    }

    override fun removeAll(elements: Collection<T>): Boolean {
        val didRemove = wrappedList.removeAll(elements)
        if (didRemove) {
            delegate.didRemoveAll(elements)
        }
        return didRemove
    }

    override fun removeAt(index: Int): T {
        val removedItem = wrappedList.removeAt(index)
        delegate.didRemove(removedItem)
        return removedItem
    }

    override fun retainAll(elements: Collection<T>): Boolean {
        val elementsToRemove = wrappedList.minus(elements)
        val elementsWereRemoved = wrappedList.retainAll(elements)
        if (elementsWereRemoved) {
            delegate.didRemoveAll(elementsToRemove)
        }
        return elementsWereRemoved
    }

    override fun set(index: Int, element: T): T {
        return if (wrappedList[index] == element) {
            element
        } else {
            val oldElement = wrappedList[index]
            wrappedList[index] = element
            delegate.didRemove(oldElement)
            delegate.didAdd(element)
            oldElement
        }
    }

    override fun subList(fromIndex: Int, toIndex: Int): MutableList<T> {
        return wrappedList.subList(fromIndex, toIndex)
    }
}
