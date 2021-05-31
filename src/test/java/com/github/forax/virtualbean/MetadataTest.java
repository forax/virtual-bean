package com.github.forax.virtualbean;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unused")
public class MetadataTest {
  @Test
  public void ofVirtualEntity() {
    interface Entity {
      int getAge();
      void setAge(int age);
    }
    var metadata = Metadata.of(Entity.class);
    assertNotNull(metadata);
  }
  @Test
  public void ofVirtualServiceDefaultMethod() {
    interface Service {
      default int foo(int value) { return value; }
    }
    var metadata = Metadata.of(Service.class);
    assertNotNull(metadata);
  }
  @Test
  public void ofVirtualServiceAbstractMethod() {
    interface Service {
      int foo(int value);
    }
    var metadata = Metadata.of(Service.class);
    assertNotNull(metadata);
  }

  @Test
  public void ofVirtualEntityNoGetter() {
    interface Entity {
      void setAge(int age);
    }
    assertThrows(IllegalStateException.class, () -> Metadata.of(Entity.class));
  }
  @Test
  public void ofVirtualEntityNoSetter() {
    interface Entity {
      int getAge();
    }
    assertThrows(IllegalStateException.class, () -> Metadata.of(Entity.class));
  }
  @Test
  public void ofVirtualEntityNotSameType() {
    interface Entity {
      int getAge();
      void setAge(String age);
    }
    assertThrows(IllegalStateException.class, () -> Metadata.of(Entity.class));
  }
  @Test
  public void ofVirtualEntityNotSameType2() {
    interface Entity {
      void setAge(int age);
      String getAge();
    }
    assertThrows(IllegalStateException.class, () -> Metadata.of(Entity.class));
  }

  @Test
  public void ofObjectFail() {
    assertThrows(IllegalArgumentException.class, () -> Metadata.of(Object.class));
  }

  @Test
  public void virtualEntityPropertiesEntrySet() {
    interface Entity {
      int getFoo();
      void setFoo(int age);
      String getBar();
      void setBar(String name);
    }
    var metadata = Metadata.of(Entity.class);
    var map = metadata.properties().entrySet().stream()
        .collect(toMap(
            Map.Entry::getKey,
            e -> e.getValue().type()));
    assertEquals(Map.of("foo", int.class, "bar", String.class), map);
  }

  @Test
  public void virtualEntityPropertiesForEach() {
    interface Entity {
      int getFoo();
      void setFoo(int age);
      String getBar();
      void setBar(String name);
    }
    var metadata = Metadata.of(Entity.class);
    var map = new HashMap<String, Class<?>>();
    metadata.properties()
        .forEach((name, property) -> map.put(name, property.type()));
    assertEquals(Map.of("foo", int.class, "bar", String.class), map);
  }

  @Test
  public void virtualEntityKeySetEntrySetAndValuesAreCached() {
    interface Bean {
      void setValue(boolean value);
      boolean isValue();

      default void service() {}
    }
    var metadata = Metadata.of(Bean.class);
    var properties = metadata.properties();
    var services = metadata.services();
    assertAll(
        () -> assertSame(properties.keySet(), properties.keySet()),
        () -> assertSame(properties.entrySet(), properties.entrySet()),
        () -> assertSame(properties.values(), properties.values()),

        () -> assertSame(services.keySet(), services.keySet()),
        () -> assertSame(services.entrySet(), services.entrySet()),
        () -> assertSame(services.values(), services.values())
    );
  }

  @Test
  public void virtualEntityStaticMethodsAreAllowed() {
    interface Bean {
      default void service() {}

      static void foo() { }
    }
    var metadata = Metadata.of(Bean.class);
    var services = metadata.services();
    var properties = metadata.properties();
    assertAll(
        () -> assertEquals(1, services.size()),
        () -> assertTrue(services.containsKey("service")),
        () -> assertEquals("service", services.value("service").name()),
        () -> assertTrue(properties.isEmpty())
    );
  }

  @Test
  public void virtualEntityObjectMethodsAreAllowed() {
    interface Bean {
      String toString();
      boolean equals(Object o);
      int hashCode();

      default void service() {}
    }
    var metadata = Metadata.of(Bean.class);
    var services = metadata.services();
    var properties = metadata.properties();
    assertAll(
        () -> assertEquals(1, services.size()),
        () -> assertTrue(services.containsKey("service")),
        () -> assertEquals("service", services.value("service").name()),
        () -> assertTrue(properties.isEmpty())
    );
  }

  @Test
  public void virtualBadPropertiesMultipleSetters() {
    interface Bean {
      void setName(int x);
      void setName(String x);
    }
    assertThrows(IllegalStateException.class, () -> Metadata.of(Bean.class));
  }

  @Test
  public void virtualBadServicesSeveralOverloads() {
    interface Bean {
      default void service() {}
      default void service(int x) {}
    }
    assertThrows(IllegalStateException.class, () -> Metadata.of(Bean.class));
  }

  @Test
  public void virtualEntityPropertiesKetSet() {
    interface Point {
      double getX();
      void setX(double x);
      double getY();
      void setY(double y);
    }
    var metadata = Metadata.of(Point.class);
    assertEquals(Set.of("x", "y"), metadata.properties().keySet());
  }

  @Test
  public void virtualEntityPropertiesValues() {
    interface Point {
      double getX();
      void setX(double x);
      double getY();
      void setY(double y);
    }
    var metadata = Metadata.of(Point.class);
    var set = metadata.properties().values().stream()
        .map(Metadata.Property::name)
        .collect(Collectors.toSet());
    assertEquals(Set.of("x", "y"), set);
  }

  @Test
  public void virtualEntityPropertiesValuesRandomAccess() {
    interface Point {
      double getX();
      void setX(double x);
      double getY();
      void setY(double y);
    }
    var metadata = Metadata.of(Point.class);
    var list = metadata.properties().values();
    var set = new HashSet<String>();
    for(var i = 0; i < list.size(); i++) {
      var name = list.get(i).name();
      set.add(name);
    }
    assertEquals(Set.of("x", "y"), set);
  }

  @Test
  public void virtualEntityPropertiesGetAndValue() {
    interface Entity {
      int getAge();
      void setAge(int age);
      String getName();
      void setName(String name);
    }
    var metadata = Metadata.of(Entity.class);
    var properties = metadata.properties();
    assertAll(
        () -> assertTrue(properties.containsKey("age")),
        () -> assertEquals("age", properties.get("age").name()),
        () -> assertEquals(int.class, properties.get("age").type()),
        () -> assertEquals("age", properties.value("age").name()),
        () -> assertEquals(int.class, properties.value("age").type()),

        () -> assertTrue(properties.containsKey("name")),
        () -> assertEquals("name", properties.get("name").name()),
        () -> assertEquals(String.class, properties.get("name").type()),
        () -> assertEquals("name", properties.value("name").name()),
        () -> assertEquals(String.class, properties.value("name").type())
    );
  }

  @Test
  public void virtualServiceServicesAbstract() {
    interface Service {
      int foo(int value);
      default int bar(int value) { return 0; }
    }
    var metadata = Metadata.of(Service.class);
    var services = metadata.services();
    assertAll(
        () -> assertTrue(metadata.properties().isEmpty()),
        () -> assertEquals(2, services.values().size()),
        () -> assertEquals("foo", services.value("foo").name()),
        () -> assertEquals("bar", services.value("bar").name())
        );
  }

  @Test
  public void virtualServiceServicesEntrySet() {
    interface Service {
      default String hello(String s) { return  s; }
      void empty();
    }
    var metadata = Metadata.of(Service.class);
    var map = metadata.services().entrySet().stream()
        .collect(toMap(
            Map.Entry::getKey,
            e -> e.getValue().method().getReturnType()));
    assertEquals(Map.of("hello", String.class, "empty", void.class), map);
  }

  @Test
  public void virtualServiceServicesKetSet() {
    interface Ops {
      default int add(int v1, int v2) { return v1 + v2; }
      int sub(int v1, int v2);
    }
    var metadata = Metadata.of(Ops.class);
    assertEquals(Set.of("add", "sub"), metadata.services().keySet());
  }

  @Test
  public void virtualServiceServicesValues() {
    interface Ops {
      default int add(int v1, int v2) { return v1 + v2; }
      int sub(int v1, int v2);
    }
    var metadata = Metadata.of(Ops.class);
    var set = metadata.services().values().stream()
        .map(Metadata.Service::name)
        .collect(Collectors.toSet());
    assertEquals(Set.of("add", "sub"), set);
  }

  @Test
  public void virtualEntityServicesValuesRandomAccess() {
    interface Ops {
      default int add(int v1, int v2) { return v1 + v2; }
      int sub(int v1, int v2);
    }
    var metadata = Metadata.of(Ops.class);
    var list = metadata.services().values();
    var set = new HashSet<String>();
    for(var i = 0; i < list.size(); i++) {
      var name = list.get(i).name();
      set.add(name);
    }
    assertEquals(Set.of("add", "sub"), set);
  }

  @Test
  public void virtualEntityServicesForEach() {
    interface Ops {
      default int add(int v1, int v2) { return v1 + v2; }
      int sub(int v1, int v2);
    }
    var metadata = Metadata.of(Ops.class);
    var set = new HashSet<String>();
    metadata.services()
        .forEach((name, __) -> set.add(name));
    assertEquals(Set.of("add", "sub"), set);
  }

  @Test
  public void virtualServiceServicesKeyIndex() {
    interface Ops {
      default int add(int v1, int v2) { return v1 + v2; }
      int sub(int v1, int v2);
    }
    var metadata = Metadata.of(Ops.class);
    var services = metadata.services();
    var addIndex = services.keyIndex("add");
    var subIndex = services.keyIndex("sub");
    assertAll(
        () -> assertEquals("add", services.key(addIndex)),
        () -> assertEquals("add", services.value(addIndex).name()),
        () -> assertEquals("sub", services.key(subIndex)),
        () -> assertEquals("sub", services.value(subIndex).name())
    );
  }

  @Test
  public void virtualServiceServicesNotFound() {
    interface FooService {
      void foo();
      default void bar() {}
    }
    var metadata = Metadata.of(FooService.class);
    var services = metadata.services();
    assertAll(
        () -> assertFalse(services.containsKey("baz")),
        () -> assertEquals(-1, services.keyIndex("baz")),
        () -> assertNull(services.get("baz")),
        () -> assertNull(services.getOrDefault("baz", null)),
        () -> assertNull(services.value("baz"))
    );
  }

  @Test
  public void virtualBeanEmptyNotFound() {
    interface EmptyService { }
    var metadata = Metadata.of(EmptyService.class);
    var services = metadata.services();
    assertAll(
        () -> assertFalse(services.containsKey("baz")),
        () -> assertEquals(-1, services.keyIndex("baz")),
        () -> assertNull(services.get("baz")),
        () -> assertNull(services.getOrDefault("baz", null)),
        () -> assertNull(services.value("baz"))
    );
  }

  @Test
  public void virtualServiceServicesGetAndValue() {
    interface Service {
      void foo(int value);
      default int parseInt(String s) { return Integer.parseInt(s); }
    }
    var metadata = Metadata.of(Service.class);
    var services = metadata.services();
    assertAll(
        () -> assertTrue(services.containsKey("foo")),
        () -> assertEquals("foo", services.get("foo").name()),
        () -> assertEquals("foo", services.get("foo").method().getName()),
        () -> assertEquals(void.class, services.get("foo").method().getReturnType()),
        () -> assertEquals("foo", services.value("foo").name()),
        () -> assertEquals("foo", services.value("foo").method().getName()),
        () -> assertEquals(void.class, services.value("foo").method().getReturnType()),

        () -> assertTrue(services.containsKey("parseInt")),
        () -> assertEquals("parseInt", services.get("parseInt").name()),
        () -> assertEquals("parseInt", services.get("parseInt").method().getName()),
        () -> assertEquals(int.class, services.get("parseInt").method().getReturnType()),
        () -> assertEquals("parseInt", services.value("parseInt").name()),
        () -> assertEquals("parseInt", services.value("parseInt").method().getName()),
        () -> assertEquals(int.class, services.value("parseInt").method().getReturnType())
    );
  }

  @Test
  public void isServiceOrAccessorOrGetterOrSetter() {
    interface Bean {
      void setName(String name);
      String getName();

      default void foo() {}
    }
    var metadata = Metadata.of(Bean.class);
    var properties = metadata.properties();
    var services = metadata.services();
    assertAll(
        () -> assertTrue(Metadata.isService(services.value("foo").method())),
        () -> assertTrue(Metadata.isGetter(properties.value("name").getter())),
        () -> assertTrue(Metadata.isSetter(properties.value("name").setter())),
        () -> assertTrue(Metadata.isAccessor(properties.value("name").getter())),
        () -> assertTrue(Metadata.isAccessor(properties.value("name").setter())),

        () -> assertFalse(Metadata.isService(properties.value("name").getter())),
        () -> assertFalse(Metadata.isService(properties.value("name").setter())),
        () -> assertFalse(Metadata.isGetter(services.value("foo").method())),
        () -> assertFalse(Metadata.isSetter(services.value("foo").method())),
        () -> assertFalse(Metadata.isAccessor(services.value("foo").method()))
    );
  }
}