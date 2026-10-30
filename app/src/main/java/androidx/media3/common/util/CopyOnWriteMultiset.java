package androidx.media3.common.util;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CopyOnWriteMultiset<E extends Object> implements Iterable<E> {

  private final Object lock;

  @GuardedBy("lock")
  private final Map<E, Integer> elementCounts;

  @GuardedBy("lock")
  private Set<E> elementSet;

  @GuardedBy("lock")
  private List<E> elements;

  public CopyOnWriteMultiset() {
    lock = new Object();
    elementCounts = new HashMap<>();
    elementSet = Collections.emptySet();
    elements = Collections.emptyList();
  }

  public void add(E element) {
    synchronized (lock) {
      List<E> elements = new ArrayList<>(this.elements);
      elements.add(element);
      this.elements = Collections.unmodifiableList(elements);

      @Nullable Integer count = elementCounts.get(element);
      if (count == null) {
        Set<E> elementSet = new HashSet<>(this.elementSet);
        elementSet.add(element);
        this.elementSet = Collections.unmodifiableSet(elementSet);
      }
      elementCounts.put(element, count != null ? count + 1 : 1);
    }
  }

  public void remove(E element) {
    synchronized (lock) {
      @Nullable Integer count = elementCounts.get(element);
      if (count == null) {
        return;
      }

      List<E> elements = new ArrayList<>(this.elements);
      elements.remove(element);
      this.elements = Collections.unmodifiableList(elements);

      if (count == 1) {
        elementCounts.remove(element);
        Set<E> elementSet = new HashSet<>(this.elementSet);
        elementSet.remove(element);
        this.elementSet = Collections.unmodifiableSet(elementSet);
      } else {
        elementCounts.put(element, count - 1);
      }
    }
  }

  public Set<E> elementSet() {
    synchronized (lock) {
      return elementSet;
    }
  }

  @Override
  public Iterator<E> iterator() {
    synchronized (lock) {
      return elements.iterator();
    }
  }

  public int count(E element) {
    synchronized (lock) {
      return elementCounts.containsKey(element) ? elementCounts.get(element) : 0;
    }
  }
}
