package com.github.forax.virtualbean;

import org.junit.jupiter.api.Test;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static java.lang.invoke.MethodType.methodType;
import static org.junit.jupiter.api.Assertions.*;

public class ProxyGeneratorTest {
  @SuppressWarnings("unused")
  private static CallSite bsm(MethodHandles.Lookup lookup, String name, MethodType type, MethodHandle impl) {
    var target = MethodHandles.empty(type);
    return new ConstantCallSite(target);
  }

  private static final MethodHandle BSM;
  static {
    var lookup = MethodHandles.lookup();
    try {
      BSM = lookup.findStatic(ProxyGeneratorTest.class, "bsm", methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class, MethodHandle.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  public void createProxyFactoryDefaultMethod() throws Throwable {
    interface Foo {
      default int m() {
        return 42;
      }
    }
    var factory = ProxyGenerator.createProxyFactory(MethodHandles.lookup(), Foo.class, BSM);
    var proxy = (Foo) factory.invoke();
    assertEquals(42, proxy.m());
  }

  @Test
  public void createProxyFactoryVirtualBean() throws Throwable {
    interface Person {
      String getName();
      void setName(String name);
      int getAge();
      void setAge(int age);
    }
    var factory = ProxyGenerator.createProxyFactory(MethodHandles.lookup(), Person.class, BSM);
    var person = (Person) factory.invoke();
    person.setName("Bob");
    person.setAge(42);
    assertEquals("Bob", person.getName());
    assertEquals(42, person.getAge());
  }

  @Test
  public void createProxyFactoryVirtualBeanObjectMethod() throws Throwable {
    interface Person {
      @SuppressWarnings("unused")
      String getName();
      void setName(String name);

      @SuppressWarnings("unused")
      int getAge();
      void setAge(int age);
    }
    var factory = ProxyGenerator.createProxyFactory(MethodHandles.lookup(), Person.class, BSM);
    var bob1 = (Person) factory.invoke();
    bob1.setName("Bob");
    bob1.setAge(42);
    var bob2 = (Person) factory.invoke();
    bob2.setName("Bob");
    bob2.setAge(42);
    assertEquals(bob1, bob2);
    assertEquals(bob1.hashCode(), bob2.hashCode());
    assertEquals(bob1.toString(), bob2.toString());
  }
}