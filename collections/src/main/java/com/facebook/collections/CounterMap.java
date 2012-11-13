package com.facebook.collections;

import com.facebook.collectionsbase.Mapper;
import com.google.common.collect.Iterators;

import java.util.AbstractMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * maps keys of type K to a long.  Thread-safe and cleans up keys when
 * the count reaches 0
 *
 * @param <K> key type for the map
 */
public class CounterMap<K> implements Iterable<Map.Entry<K, Long>> {
  private static final AtomicLong ZERO = new AtomicLong(0);

  private final ConcurrentMap<K, AtomicLong> counters =
    new ConcurrentHashMap<K, AtomicLong>();
  private final ReadWriteLock removalLock = new ReentrantReadWriteLock();

  /**
   * adds a value to key; will create a counter if it doesn't exist already.
   * conversely, since 0 is the value returned for keys not present, if the
   * counter value reaches 0, it is removed
   *
   * @param key - key to add the delta to
   * @param delta - positive/negative amount to increment a counter
   * @return the new value after updating
   */
  public long addAndGet(K key, long delta) {
    long retVal;
    // ensure that no key is removed while we are updating the counter
    removalLock.readLock().lock();
    try {
      retVal = getCounter(key).addAndGet(delta);
    } finally {
      removalLock.readLock().unlock();
    }

    if (retVal == 0) {
      tryCleanup(key);
    }

    return retVal;
  }

  /**
   * adds a value to key; will create a counter if it doesn't exist already.
   * conversely, since 0 is the value returned for keys not present, if the
   * counter value reaches 0, it is removed
   *
   * @param key - key to add the delta to
   * @param delta - positive/negative amount to increment a counter
   * @return the old value before updating
   */
  public long getAndAdd(K key, long delta) {
    long retVal;
    // ensure that no key is removed while we are updating the counter
    removalLock.readLock().lock();
    try {
      retVal = getCounter(key).getAndAdd(delta);
    } finally {
      removalLock.readLock().unlock();
    }

    if (retVal + delta == 0) {
      tryCleanup(key);
    }

    return retVal;
  }

  private AtomicLong getCounter(K key) {
    AtomicLong counter = counters.get(key);
    if (counter == null) {
      AtomicLong newCounter = new AtomicLong(0);
      AtomicLong oldCounter = counters.putIfAbsent(key, newCounter);
      counter = (oldCounter == null) ? newCounter : oldCounter;
    }
    return counter;
  }

  private void tryCleanup(K key) {
    removalLock.writeLock().lock();
    try {
      counters.remove(key, ZERO);
    } finally {
      removalLock.writeLock().unlock();
    }
  }

  /**
   *
   * @param key
   * @return value removed if present, null otherwise
   */
  public AtomicLong remove(K key) {
    // no locking, this is an unconditional remove
    return counters.remove(key);
  }

  /**
   *
   * @param key
   * @return value of a counter.  Returns 0 if not present
   */
  public long get(K key) {
    AtomicLong counter = counters.get(key);

    if (counter == null) {
      return 0;
    }

    return counter.get();
  }

  @Override
  public Iterator<Map.Entry<K, Long>> iterator() {
    return Iterators.unmodifiableIterator(
      new TranslatingIterator<Map.Entry<K, AtomicLong>, Map.Entry<K, Long>>(
        new Mapper<Entry<K, AtomicLong>, Entry<K, Long>>() {
          @Override
          public Map.Entry<K, Long> map(Map.Entry<K, AtomicLong> input) {
            return new AbstractMap.SimpleImmutableEntry<K, Long>(
              input.getKey(), input.getValue().get()
            );
          }
        },
        counters.entrySet().iterator()
      )
    );
  }
}
