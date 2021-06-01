package com.github.forax.virtualbean.example;

import com.github.forax.virtualbean.BeanFactory;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandles;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Inject a supplier from a registry
 */
public class Example3 {
  @Retention(RetentionPolicy.RUNTIME)
  @interface Inject { }

  static class Registry {
    private final HashMap<Class<?>, Supplier<?>> map = new HashMap<>();

    public <T> void register(Class<T> type, Supplier<? extends T> supplier) {
      map.put(type, supplier);
    }
    public <T> T lookup(Class<T> type) {
      return type.cast(map.get(type).get());
    }
  }

  public static void main(String[] args) {
    var lookup = MethodHandles.lookup();
    var beanFactory = new BeanFactory(lookup);

    var registry = new Registry();
    beanFactory.registerInvocationHandler(Inject.class, (method, bean, args1) -> registry.lookup(method.getReturnType()));

    interface Clock {
      @Inject
      LocalTime now();
    }

    var clock = beanFactory.create(Clock.class);
    registry.register(LocalTime.class, LocalTime::now);
    System.out.println(clock.now());
    System.out.println(clock.now());
  }
}
