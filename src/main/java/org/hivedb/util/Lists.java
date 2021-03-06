package org.hivedb.util;

import java.util.*;

public class Lists {
  public static <T> List<T> newArrayList() {
    return new ArrayList<T>();
  }

  public static <T> List<T> newList(T... items) {
    return Arrays.asList(items);
  }

  public static <T> T random(Collection<T> items) {
    int pick = new Random().nextInt(items.size());
    List<T> list = new ArrayList<T>(items);
    return list.get(pick);
  }

  public static <T> List<T> newList(Collection<T> items) {
    List<T> list = new ArrayList<T>();
    list.addAll(items);
    return list;
  }

  public static <T> T[] copyInto(T[] source, T[] target) {
    for (int i = 0; i < source.length; i++)
      target[i] = source[i];
    return target;
  }

  public static int[] copyInto(int[] source, int[] target) {
    for (int i = 0; i < source.length; i++)
      target[i] = source[i];
    return target;
  }

  public static boolean or(Iterable<Boolean> bools) {
    boolean b = false;
    for (Boolean v : bools)
      b |= v;
    return b;
  }

  public static boolean or(boolean... bools) {
    boolean b = false;
    for (Boolean v : bools)
      b |= v;
    return b;
  }
}
