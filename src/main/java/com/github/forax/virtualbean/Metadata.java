package com.github.forax.virtualbean;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Set;
import java.util.function.BiConsumer;

import static java.lang.invoke.MethodType.methodType;

/**
 * The metadata describing a virtual bean.
 *
 * A virtual bean is an interface with
 * <ul>
 *   <li>properties define as pairs of abstract getter and setter,
 *     by example {@code type getX()} and {@code setX(type)} define a property {@code x}.
 *   <li>services define as default methods,
 *     any default is a service.
 * </ul>
 */
public record Metadata(Table<Property> properties, Table<Service> services) {
  /**
   * Describe a property with a {@link #name()}, a {@link #type()}, a {@link #getter()}
   * and a {@link #setter()}.
   */
  public record Property(String name, Class<?> type, Method getter, Method setter) {
    private Property withGetter(Method getter) {
      return new Property(name, type, getter, setter);
    }
    private Property withSetter(Method setter) {
      return new Property(name, type, getter, setter);
    }
  }

  /**
   * Describe a service wiht a name and a default method.
   */
  public record Service(String name, Method method) {}

  /**
   * An unmodifiable specialized {@link Map} with keys being [@code String} and
   * with the entries being ordered (see {@link #values()}).
   *
   * This interface is used to store {@link Metadata metadata} properties and services.
   *
   * @param <V> type of the value
   */
  public interface Table<V> extends Map<String, V> {
    /**
     * Returns an unmodifiable list of all values.
     * @return an unmodifiable list of all values.
     */
    List<V> values();

    /**
     * Return the key index from the kay name.
     * @param key the key name
     * @return the key index or -1 if there is no key for the key name.
     */
    int keyIndex(String key);

    /**
     * Returns the key at index {@code index}.
     * @param index the index of the key
     * @return the key at index {@code index}.
     * @throws IndexOutOfBoundsException if the index if not between 0 and size.
     */
    String key(int index);

    /**
     * Returns the value at index {@code index}.
     * @param index the index of the value
     * @return the value at index {@code index}.
     * @throws IndexOutOfBoundsException if the index if not between 0 and size.
     */
    V value(int index);

    /**
     * Returns the value for a {@code key} or {@code null}.
     * @param key the key of the value
     * @return the value corresponding to {@code key} or {@code null}
     * @throws NullPointerException if the key is {@code null}
     */
    V value(String key);
  }

  private static final class TableImpl<V> extends AbstractMap<String, V> implements Table<V> {
    private final int[] indexes;
    private final Object[] entries;

    private TableImpl(int capacity) {
      indexes = new int[capacity << 1];
      entries = new Object[capacity << 1];
    }

    @Override
    public int size() {
      return indexes.length >> 1;
    }

    @Override
    public String key(int index) {
      Objects.checkIndex(index, size());
      return (String) entries[index << 1];
    }

    @Override
    @SuppressWarnings("unchecked")
    public V value(int index) {
      Objects.checkIndex(index, size());
      return (V) entries[(index << 1) + 1];
    }

    @Override
    public int keyIndex(String name) {
      Objects.requireNonNull(name);
      var hash = name.hashCode();
      var indexes = this.indexes;
      if (indexes.length == 0) {
        return -1;
      }
      var entries = this.entries;
      var index = hash & (indexes.length - 1);
      for(;;) {
        var result = indexes[index];
        if (result == 0) {
          return -1;
        }
        var entryIndex = result - 1;
        var entryKey = (String) entries[entryIndex];
        if (entryKey.hashCode() == hash && entryKey.equals(name)) {
          return entryIndex >> 1;
        }
        index = (index + 1) & (indexes.length - 1);
      }
    }

    @Override
    public V get(Object key) {
      return getOrDefault(key, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public V getOrDefault(Object key, V defaultValue) {
      Objects.requireNonNull(key);
      var name = (String) key;
      var hash = name.hashCode();
      var indexes = this.indexes;
      if (indexes.length == 0) {
        return defaultValue;
      }
      var entries = this.entries;
      var index = hash & (indexes.length - 1);
      for(;;) {
        var result = indexes[index];
        if (result == 0) {
          return defaultValue;
        }
        var entryIndex = result - 1;
        var entryKey = (String) entries[entryIndex];
        if (entryKey.hashCode() == hash && entryKey.equals(name)) {
          return (V) entries[entryIndex + 1];
        }
        index = (index + 1) & (indexes.length - 1);
      }
    }

    @Override
    public V value(String key) {
      return getOrDefault(key, null);
    }

    @Override
    public boolean containsKey(Object key) {
      Objects.requireNonNull(key);
      var name = (String) key;
      var hash = name.hashCode();
      var indexes = this.indexes;
      if (indexes.length == 0) {
        return false;
      }
      var entries = this.entries;
      var index = hash & (indexes.length - 1);
      for(;;) {
        var result = indexes[index];
        if (result == 0) {
          return false;
        }
        var entryIndex = result - 1;
        var entryKey = (String) entries[entryIndex];
        if (entryKey.hashCode() == hash && entryKey.equals(name)) {
          return true;
        }
        index = (index + 1) & (indexes.length - 1);
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void forEach(BiConsumer<? super String, ? super V> action) {
      Objects.requireNonNull(action);
      var entries = this.entries;
      for(var index = 0; index < entries.length; index += 2) {
        action.accept((String) entries[index], (V) entries[index + 1]);
      }
    }

    TableImpl<V> addAll(Map<? extends String, ? extends V> map) {
      var size = 0;
      var indexes = this.indexes;
      var entries = this.entries;
      loop: for(var entry: map.entrySet()) {
        var name = entry.getKey();
        var value = entry.getValue();
        var hash = name.hashCode();
        var index = hash & (indexes.length - 1);
        for (; ; ) {
          var result = indexes[index];
          if (result == 0) {
            var entryIndex = size++ << 1;
            indexes[index] = entryIndex + 1;
            entries[entryIndex] = name;
            entries[entryIndex + 1] = value;
            continue loop;
          }
          index = (index + 1) & (indexes.length - 1);
        }
      }
      return this;
    }

    private Set<Entry<String, V>> entrySet;
    private Set<String> keySet;
    private List<V> values;

    @Override
    public Set<Entry<String, V>> entrySet() {
      if (entrySet != null) {
        //noinspection AssignmentOrReturnOfFieldWithMutableType
        return entrySet;
      }
      var size = entries.length >> 1;
      return entrySet = new AbstractSet<>() {
        @Override
        public int size() {
          return size;
        }

        @Override
        public Iterator<Entry<String, V>> iterator() {
          var entries2 = entries;
          return new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
              return index < entries2.length;
            }

            @Override
            @SuppressWarnings("unchecked")
            public Entry<String, V> next() {
              if (!hasNext()) {
                throw new NoSuchElementException();
              }
              var entry = Map.entry((String) entries2[index], (V) entries2[index + 1]);
              index += 2;
              return entry;
            }
          };
        }
      };
    }

    @Override
    public Set<String> keySet() {
      if (keySet != null) {
        //noinspection AssignmentOrReturnOfFieldWithMutableType
        return keySet;
      }
      var size = entries.length >> 1;
      return keySet = new AbstractSet<>() {
        @Override
        public int size() {
          return size;
        }

        @Override
        public Iterator<String> iterator() {
          var entries2 = entries;
          return new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
              return index < entries2.length;
            }

            @Override
            public String next() {
              if (!hasNext()) {
                throw new NoSuchElementException();
              }
              var key = (String) entries2[index];
              index += 2;
              return key;
            }
          };
        }

        @Override
        public boolean contains(Object o) {
          return containsKey(o);
        }
      };
    }

    @Override
    public List<V> values() {
      if (values != null) {
        //noinspection AssignmentOrReturnOfFieldWithMutableType
        return values;
      }
      var entries = this.entries;
      var size = entries.length >> 1;
      class ValueList extends AbstractList<V> implements RandomAccess {
        @Override
        public int size() {
          return size;
        }

        @Override
        @SuppressWarnings("unchecked")
        public V get(int index) {
          Objects.checkIndex(index, size);
          return (V) entries[(index << 1) + 1];
        }

        @Override
        public Iterator<V> iterator() {
          //noinspection UnnecessaryLocalVariable
          var entries2 = entries;
          return new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
              return index < entries2.length;
            }

            @Override
            @SuppressWarnings("unchecked")
            public V next() {
              if (!hasNext()) {
                throw new NoSuchElementException();
              }
              var value = entries2[index + 1];
              index += 2;
              return (V) value;
            }
          };
        }
      }
      return values = new ValueList();
    }
  }

  private static final ClassValue<Metadata> METADATA = new ClassValue<>() {
    @Override
    protected Metadata computeValue(Class<?> type) {
      return createMetadata(type.getMethods());
    }
  };

  /**
   * Returns the metadata representing of the virtual bean..
   * @param type an interface defining a virtual bean
   * @return the metadata of the virtual bean.
   * @throws IllegalArgumentException if type is not an interface
   * @throws IllegalStateException if the interface does not describe a virtual bean
   */
  public static Metadata of(Class<?> type) {
    if (!type.isInterface()) {
      throw new IllegalArgumentException("type " + type.getName() + " is not an interface");
    }
    return METADATA.get(type);
  }

  private static String propertyName(String name, int index) {
    return Character.toLowerCase(name.charAt(index)) + name.substring(index + 1);
  }

  private static Metadata createMetadata(Method[] methods) {
    var propertyMap = new LinkedHashMap<String, Property>();
    var serviceMap = new LinkedHashMap<String, Service>();
    for(var method: methods) {
      if (Modifier.isStatic(method.getModifiers())) {
        continue;  // skip
      }

      var name = method.getName();
      if (method.isDefault()) {
        var result = serviceMap.put(name, new Service(name, method));
        if (result != null) {
          throw new IllegalStateException("duplicate service with the same name " + name);
        }
        continue;
      }

      String propertyName;
      Class<?> type;
      boolean getter;
      var nameLength = name.length();
      var parameterCount = method.getParameterCount();
      if (nameLength > 2 && name.startsWith("is") && parameterCount == 0) {
        propertyName = propertyName(name, 2);
        type = method.getReturnType();
        getter = true;
      } else {
        if (nameLength > 3 && name.startsWith("get") && parameterCount == 0) {
          propertyName = propertyName(name, 3);
          type = method.getReturnType();
          getter = true;
        } else {
          if (nameLength > 3 && name.startsWith("set") && parameterCount == 1) {
            propertyName = propertyName(name, 3);
            type = method.getParameterTypes()[0];
            getter = false;
          } else {
            var desc = methodType(method.getReturnType(), method.getParameterTypes()).descriptorString();
            switch(name + desc) {
              //noinspection HardcodedFileSeparator
              case "toString()Ljava/lang/String;",
                   "equals(Ljava/lang/Object;)Z",
                   "hashCode()I" -> {
                continue;
              }
              default -> throw new IllegalStateException("invalid property or service" + method);
            }
          }
        }
      }
      propertyMap.compute(propertyName, (key, p) -> {
        if (getter) {
          if (p == null) {
            return new Property(propertyName, type, method, null);
          }
          if (p.getter != null) {
            throw new IllegalStateException("multiple getters for property " + propertyName);
          }
          if (type != p.type) {
            throw new IllegalStateException("getter and setter disagreeing on the type for property " + propertyName);
          }
          return p.withGetter(method);
        }
        if (p == null) {
          return new Property(propertyName, type, null, method);
        }
        if (p.setter != null) {
          throw new IllegalStateException("multiple setters for property " + propertyName);
        }
        if (type != p.type) {
          throw new IllegalStateException("getter and setter disagreeing on the type for property " + propertyName);
        }
        return p.withSetter(method);
      });
    }

    // validate
    for(var property: propertyMap.values()) {
      if (property.getter == null) {
        throw new IllegalStateException("property " + property.name + " has no getter");
      }
      if (property.setter == null) {
        throw new IllegalStateException("property " + property.name + " has no setter");
      }
    }

    return new Metadata(
        new TableImpl<Property>(propertyMap.size()).addAll(propertyMap),
        new TableImpl<Service>(serviceMap.size()).addAll(serviceMap));
  }

  /**
   * Returns true if the method is a service
   * @param m a method
   * @return true if the method is a service
   * @throws IllegalArgumentException if declaring class of the method is not an interface
   * @throws IllegalStateException if the declaring class of the method does not describe a virtual bean
   */
  public static boolean isService(Method m) {
    Metadata.of(m.getDeclaringClass());
    return m.isDefault();
  }

  /**
   * Returns true if the method is a getter or a setter
   * @param m a method
   * @return true if the method is a getter or a setter
   * @throws IllegalArgumentException if declaring class of the method is not an interface
   * @throws IllegalStateException if the declaring class of the method does not describe a virtual bean
   */
  public static boolean isAccessor(Method m) {
    Metadata.of(m.getDeclaringClass());
    var name = m.getName();
    return !m.isDefault() && (name.startsWith("get") || name.startsWith("set") || name.startsWith("is"));
  }

  /**
   * Returns true if the method is a getter
   * @param m a method
   * @return true if the method is a getter
   * @throws IllegalArgumentException if declaring class of the method is not an interface
   * @throws IllegalStateException if the declaring class of the method does not describe a virtual bean
   */
  public static boolean isGetter(Method m) {
    Metadata.of(m.getDeclaringClass());
    var name = m.getName();
    return !m.isDefault() && (name.startsWith("get") || name.startsWith("is"));
  }

  /**
   * Returns true if the method is a setter
   * @param m a method
   * @return true if the method is a setter
   * @throws IllegalArgumentException if declaring class of the method is not an interface
   * @throws IllegalStateException if the declaring class of the method does not describe a virtual bean
   */
  public static boolean isSetter(Method m) {
    Metadata.of(m.getDeclaringClass());
    var name = m.getName();
    return !m.isDefault() && name.startsWith("set");
  }
}
