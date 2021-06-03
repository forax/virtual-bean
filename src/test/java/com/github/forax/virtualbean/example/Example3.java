package com.github.forax.virtualbean.example;

import com.github.forax.virtualbean.BeanFactory;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandles;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.function.Supplier;

/**
 * Inject the local time. The local time implementation is managed by an injector
 * that associate a type to the local time implementation factory (a supplier).
 *
 * Compared to Guice, Spring or CDI, the injection is not done at the time the
 * bean is instantiated but later when the method annotated with @Inject is called.
 */
public class Example3 {
  @Retention(RetentionPolicy.RUNTIME)
  @interface Inject { }

  static class Injector {
    private final HashMap<Class<?>, Supplier<?>> map = new HashMap<>();

    public <T> void bind(Class<T> type, Supplier<? extends T> supplier) {
      map.put(type, supplier);
    }
    public <T> T getInstance(Class<T> type) {
      return type.cast(map.get(type).get());
    }
  }

  public static void main(String[] arguments) {
    var lookup = MethodHandles.lookup();
    var beanFactory = new BeanFactory(lookup);

    var injector = new Injector();
    beanFactory.registerInvocationHandler(Inject.class, (method, bean, args) -> injector.getInstance(method.getReturnType()));

    interface Clock {
      @Inject
      LocalTime current();
    }

    var clock = beanFactory.create(Clock.class);
    injector.bind(LocalTime.class, LocalTime::now);
    System.out.println(clock.current());
    System.out.println(clock.current());
  }
}
