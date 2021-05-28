package com.github.forax.virtualbean;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.methodType;
import static org.junit.jupiter.api.Assertions.*;

public class BeanFactoryTest {
  @Test
  public void proxyEntity() {
    interface Entity {
      String getName();
      void setName(String name);
      long getCount();
      void setCount(long cunt);
    }

    var factory = new BeanFactory(lookup());
    var entity = factory.proxy(Entity.class);
    entity.setName("foo");
    entity.setCount(13);
    assertAll(
        () -> assertEquals("foo", entity.getName()),
        () -> assertEquals(13L, entity.getCount())
    );
  }

  @Test
  public void proxyService() {
    interface Service {
      default String sayHello(String name) {
        return "hello " + name;
      }
    }

    var factory = new BeanFactory(lookup());
    var service = factory.proxy(Service.class);
    assertEquals("hello Bob", service.sayHello("Bob"));
  }

  @Test
  public void proxyObjectFail() {
    var factory = new BeanFactory(lookup());
    assertThrows(IllegalArgumentException.class, () -> factory.proxy(Object.class));
  }

  @Retention(RetentionPolicy.RUNTIME)
  @interface SideEffect { }

  @Test
  public void registerAdvice() {
    interface Computation {
      @SideEffect
      default int add(int v1, int v2) {
        return v1 + v2;
      }
    }

    var factory = new BeanFactory(lookup());
    var box = new Object() {
      Object proxy;
      int preCalled;
      int postCalled;
    };
    factory.registerAdvice(SideEffect.class, new BeanFactory.Advice() {
      @Override
      @SuppressWarnings("RedundantThrows")
      public void pre(Method method, Object proxy, Object[] args) throws Throwable {
        box.preCalled++;
        assertAll(
            () -> assertEquals(Computation.class.getMethod("add", int.class, int.class), method),
            () -> assertEquals(box.proxy, proxy),
            () -> assertArrayEquals(new Object[] { 40, 2 }, args)
            );
      }

      @Override
      @SuppressWarnings("RedundantThrows")
      public void post(Method method, Object proxy, Object[] args) throws Throwable {
        box.postCalled++;
        assertAll(
            () -> assertEquals(Computation.class.getMethod("add", int.class, int.class), method),
            () -> assertEquals(box.proxy, proxy),
            () -> assertArrayEquals(new Object[] { 40, 2 }, args)
        );
      }
    });
    var proxy = factory.proxy(Computation.class);
    box.proxy = proxy;
    assertAll(
        () -> assertEquals(42, proxy.add(40, 2)),
        () -> assertEquals(1, box.preCalled),
        () -> assertEquals(1, box.postCalled)
    );
  }

  @Test
  public void registerAdviceFilteredOut() {
    interface Computation {
      @SideEffect
      default int add(int v1, int v2) {
        return v1 + v2;
      }
    }

    var factory = new BeanFactory(lookup());
    factory.registerAdvice(SideEffect.class, __ -> false, new BeanFactory.Advice() {
      @Override
      public void pre(Method method, Object proxy, Object[] args) {
        fail();
      }

      @Override
      public void post(Method method, Object proxy, Object[] args) {
        fail();
      }
    });
    var proxy = factory.proxy(Computation.class);
    assertEquals(42, proxy.add(40, 2));
  }

  @Test
  public void registerAdviceClassAnnotation() {
    @SideEffect
    interface Computation {
      default String concat(String v1, String v2) {
        return v1 + v2;
      }
    }

    var factory = new BeanFactory(lookup());
    var box = new Object() {
      int preCalled;
      int postCalled;
    };
    factory.registerAdvice(SideEffect.class, new BeanFactory.Advice() {
      @Override
      public void pre(Method method, Object proxy, Object[] args) {
        box.preCalled++;
      }

      @Override
      public void post(Method method, Object proxy, Object[] args) {
        box.postCalled++;
      }
    });
    var proxy = factory.proxy(Computation.class);
    assertEquals("foobar", proxy.concat("foo", "bar"));
    assertAll(
        () -> assertEquals(1, box.preCalled),
        () -> assertEquals(1, box.postCalled)
    );
  }

  @Test
  public void registerAdviceMethodParameterAnnotation() {
    interface Computation {
      default long mult(long v1, @SideEffect long v2) {
        return v1 * v2;
      }
    }

    var factory = new BeanFactory(lookup());
    var box = new Object() {
      int preCalled;
      int postCalled;
    };
    factory.registerAdvice(SideEffect.class, new BeanFactory.Advice() {
      @Override
      public void pre(Method method, Object proxy, Object[] args) {
        box.preCalled++;
      }

      @Override
      public void post(Method method, Object proxy, Object[] args) {
        box.postCalled++;
      }
    });
    var proxy = factory.proxy(Computation.class);
    assertEquals(42L, proxy.mult(21, 2));
    assertAll(
        () -> assertEquals(1, box.preCalled),
        () -> assertEquals(1, box.postCalled)
    );
  }

  @Test
  public void registerTwoAdvicesSameAnnotation() {
    interface FooService {
      @SideEffect
      default void foo() { }
    }

    var factory = new BeanFactory(lookup());
    var advice1 = new BeanFactory.Advice() {
      int preCalledAdvice1;
      int postCalledAdvice1;

      @Override
      public void pre(Method method, Object proxy, Object[] args) {
        preCalledAdvice1++;
      }

      @Override
      public void post(Method method, Object proxy, Object[] args) {
        postCalledAdvice1++;
      }
    };
    var advice2 = new BeanFactory.Advice() {
      int preCalledAdvice2;
      int postCalledAdvice2;

      @Override
      public void pre(Method method, Object proxy, Object[] args) {
        preCalledAdvice2++;
      }

      @Override
      public void post(Method method, Object proxy, Object[] args) {
        postCalledAdvice2++;
      }
    };
    factory.registerAdvice(SideEffect.class, advice1);
    factory.registerAdvice(SideEffect.class, advice2);
    var proxy = factory.proxy(FooService.class);
    proxy.foo();
    assertAll(
        () -> assertEquals(1, advice1.preCalledAdvice1),
        () -> assertEquals(1, advice1.postCalledAdvice1),
        () -> assertEquals(1, advice2.preCalledAdvice2),
        () -> assertEquals(1, advice2.postCalledAdvice2)
    );
  }

  @Retention(RetentionPolicy.RUNTIME)
  @interface SideEffect2 {}

  @Test
  public void registerTwoAdvicesDifferentAnnotations() {
    @SideEffect2
    interface FooService {
      @SideEffect
      default void foo() { }
    }

    var factory = new BeanFactory(lookup());
    var advice1 = new BeanFactory.Advice() {
      int preCalledAdvice1;
      int postCalledAdvice1;

      @Override
      public void pre(Method method, Object proxy, Object[] args) {
        preCalledAdvice1++;
      }

      @Override
      public void post(Method method, Object proxy, Object[] args) {
        postCalledAdvice1++;
      }
    };
    var advice2 = new BeanFactory.Advice() {
      int preCalledAdvice2;
      int postCalledAdvice2;

      @Override
      public void pre(Method method, Object proxy, Object[] args) {
        preCalledAdvice2++;
      }

      @Override
      public void post(Method method, Object proxy, Object[] args) {
        postCalledAdvice2++;
      }
    };
    factory.registerAdvice(SideEffect.class, advice1);
    factory.registerAdvice(SideEffect2.class, advice2);
    var proxy = factory.proxy(FooService.class);
    proxy.foo();
    assertAll(
        () -> assertEquals(1, advice1.preCalledAdvice1),
        () -> assertEquals(1, advice1.postCalledAdvice1),
        () -> assertEquals(1, advice2.preCalledAdvice2),
        () -> assertEquals(1, advice2.postCalledAdvice2)
    );
  }

  @Test
  public void registerInterceptor() {
    interface Computation {
      @SideEffect
      default long mult(long v1, long v2) {
        return v1 * v2;
      }
    }

    var factory = new BeanFactory(lookup());
    var box = new Object() {
      int preCalled;
      int postCalled;
    };
    var advice = new BeanFactory.Advice() {
      @Override
      public void pre(Method method, Object proxy, Object[] args) {
        box.preCalled++;
      }

      @Override
      public void post(Method method, Object proxy, Object[] args) {
        box.postCalled++;
      }
    };
    var interceptor = advice.asInterceptor();
    factory.registerInterceptor(SideEffect.class, m -> {
      try {
        assertEquals(Computation.class.getMethod("mult", long.class, long.class), m);
      } catch (NoSuchMethodException e) {
        throw new AssertionError(e);
      }
      return true;
    }, interceptor);
    var proxy = factory.proxy(Computation.class);
    assertEquals(42L, proxy.mult(21, 2));
    assertAll(
        () -> assertEquals(1, box.preCalled),
        () -> assertEquals(1, box.postCalled)
    );
  }

  @Test
  public void registerInterceptorButReturnNull() {
    interface Computation {
      @SideEffect
      default long div(long v1, long v2) {
        return v1 / v2;
      }
    }

    var factory = new BeanFactory(lookup());
    var box = new Object() {
      int preCalled;
      int postCalled;
    };
    BeanFactory.Interceptor interceptor = (kind, method, type) -> {
      switch (kind) {
        case PRE -> box.preCalled++;
        case POST -> box.postCalled++;
      }
      return null;
    };
    factory.registerInterceptor(SideEffect.class, __ -> true, interceptor);
    var proxy = factory.proxy(Computation.class);

    // called twice
    assertEquals(42L, proxy.div(84, 2));
    assertEquals(42L, proxy.div(84, 2));

    assertAll(
        () -> assertEquals(1, box.preCalled),
        () -> assertEquals(1, box.postCalled)
    );
  }

  @Test
  public void unregisterInterceptor() {
    interface Computation {
      @SideEffect
      default long add(long v1, long v2) {
        return v1 + v2;
      }
    }

    var factory = new BeanFactory(lookup());
    var box = new Object() {
      int preCalled;
      int postCalled;
    };
    BeanFactory.Interceptor interceptor = new BeanFactory.Advice() {
      @Override
      public void pre(Method method, Object proxy, Object[] args) {
        box.preCalled++;
      }

      @Override
      public void post(Method method, Object proxy, Object[] args) {
         box.postCalled++;
      }
    }.asInterceptor();
    factory.registerInterceptor(SideEffect.class, __ -> true, interceptor);
    var proxy = factory.proxy(Computation.class);

    var sum = 0L;
    for(var i = 0; i < 100_000; i++) {
      if (i == 50_000) {
        factory.unregisterInterceptor(SideEffect.class, interceptor);
      }
      sum = proxy.add(sum, i);
    }
    var result = sum;

    assertAll(
        () -> assertEquals(4_999_950_000L, result),
        () -> assertEquals(50_000, box.preCalled),
        () -> assertEquals(50_000, box.postCalled)
    );
  }

  @Test
  public void unregisterUnknownInterceptor() {
    interface Computation {
    }

    var factory = new BeanFactory(lookup());
    BeanFactory.Interceptor interceptor = (kind, method, type) -> null;
    assertThrows(IllegalStateException.class,
        () -> factory.unregisterInterceptor(SideEffect.class, interceptor));
  }

  @Test
  public void unregisterUnknownInterceptor2() {
    interface Computation {
    }

    var factory = new BeanFactory(lookup());
    factory.registerInterceptor(SideEffect.class,
        __ -> true,
        (kind, method, type) -> { throw new AssertionError(); });
    BeanFactory.Interceptor interceptor = (kind, method, type) -> null;
    assertThrows(IllegalStateException.class,
        () -> factory.unregisterInterceptor(SideEffect.class, interceptor));
  }

  @Test
  public void registerInterceptorWrongMethodTypeException() {
    interface Computation {
      @SideEffect
      default long add(long v1, long v2) {
        return v1 + v2;
      }
    }

    var factory = new BeanFactory(lookup());
    factory.registerInterceptor(SideEffect.class, __ -> true, (kind, method, type) -> {
      return MethodHandles.empty(methodType(void.class));
    });
    var proxy = factory.proxy(Computation.class);

    assertThrows(BootstrapMethodError.class, () -> proxy.add(2, 3));
  }

  @interface BadAnnotationNoRuntimeRetention {}

  @Test
  public void registerAdviceBadAnnotation() {
    var beanFactory = new BeanFactory(lookup());
    var advice = new BeanFactory.Advice() {
      @Override
      public void pre(Method method, Object proxy, Object[] args) {
        fail();
      }

      @Override
      public void post(Method method, Object proxy, Object[] args) {
        fail();
      }
    };
    assertThrows(IllegalArgumentException.class,
        () -> beanFactory.registerAdvice(BadAnnotationNoRuntimeRetention.class, advice));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void registerAdviceNotAnAnnotation() {
    var beanFactory = new BeanFactory(lookup());
    var advice = new BeanFactory.Advice() {
      @Override
      public void pre(Method method, Object proxy, Object[] args) {
        fail();
      }

      @Override
      public void post(Method method, Object proxy, Object[] args) {
        fail();
      }
    };
    assertThrows(IllegalArgumentException.class,
        () -> beanFactory.registerAdvice((Class<? extends Annotation>)(Class<?>)Object.class, advice));
  }
}