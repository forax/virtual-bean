package com.github.forax.virtualbean;

import com.github.forax.virtualbean.BeanFactory.Advice;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Method;

import static java.lang.invoke.MethodHandles.constant;
import static java.lang.invoke.MethodHandles.empty;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.methodType;
import static org.junit.jupiter.api.Assertions.*;

public class BeanFactoryTest {
  @Test
  public void beanEntity() {
    interface Entity {
      String getName();
      void setName(String name);
      long getCount();
      void setCount(long cunt);
    }

    var factory = new BeanFactory(lookup());
    var entity = factory.create(Entity.class);
    entity.setName("foo");
    entity.setCount(13);
    assertAll(
        () -> assertEquals("foo", entity.getName()),
        () -> assertEquals(13L, entity.getCount())
    );
  }

  @Test
  public void beanService() {
    interface Service {
      default String sayHello(String name) {
        return "hello " + name;
      }
    }

    var factory = new BeanFactory(lookup());
    var service = factory.create(Service.class);
    assertEquals("hello Bob", service.sayHello("Bob"));
  }

  @Test
  public void beanObjectFail() {
    var factory = new BeanFactory(lookup());
    assertThrows(IllegalArgumentException.class, () -> factory.create(Object.class));
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
      Object bean;
      int preCalled;
      int postCalled;
    };
    factory.registerAdvice(SideEffect.class, new Advice() {
      @Override
      @SuppressWarnings("RedundantThrows")
      public void pre(Method method, Object bean, Object[] args) throws Throwable {
        box.preCalled++;
        assertAll(
            () -> assertEquals(Computation.class.getMethod("add", int.class, int.class), method),
            () -> assertEquals(box.bean, bean),
            () -> assertArrayEquals(new Object[] { 40, 2 }, args)
            );
      }

      @Override
      @SuppressWarnings("RedundantThrows")
      public void post(Method method, Object bean, Object[] args) throws Throwable {
        box.postCalled++;
        assertAll(
            () -> assertEquals(Computation.class.getMethod("add", int.class, int.class), method),
            () -> assertEquals(box.bean, bean),
            () -> assertArrayEquals(new Object[] { 40, 2 }, args)
        );
      }
    });
    var bean = factory.create(Computation.class);
    box.bean = bean;
    assertAll(
        () -> assertEquals(42, bean.add(40, 2)),
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
    factory.registerAdvice(SideEffect.class, __ -> false, new Advice() {
      @Override
      public void pre(Method method, Object bean, Object[] args) {
        fail();
      }

      @Override
      public void post(Method method, Object bean, Object[] args) {
        fail();
      }
    });
    var bean = factory.create(Computation.class);
    assertEquals(42, bean.add(40, 2));
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
    factory.registerAdvice(SideEffect.class, new Advice() {
      @Override
      public void pre(Method method, Object bean, Object[] args) {
        box.preCalled++;
      }

      @Override
      public void post(Method method, Object bean, Object[] args) {
        box.postCalled++;
      }
    });
    var bean = factory.create(Computation.class);
    assertEquals("foobar", bean.concat("foo", "bar"));
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
    factory.registerAdvice(SideEffect.class, new Advice() {
      @Override
      public void pre(Method method, Object bean, Object[] args) {
        box.preCalled++;
      }

      @Override
      public void post(Method method, Object bean, Object[] args) {
        box.postCalled++;
      }
    });
    var bean = factory.create(Computation.class);
    assertEquals(42L, bean.mult(21, 2));
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
    var advice1 = new Advice() {
      int preCalledAdvice1;
      int postCalledAdvice1;

      @Override
      public void pre(Method method, Object bean, Object[] args) {
        preCalledAdvice1++;
      }

      @Override
      public void post(Method method, Object bean, Object[] args) {
        postCalledAdvice1++;
      }
    };
    var advice2 = new Advice() {
      int preCalledAdvice2;
      int postCalledAdvice2;

      @Override
      public void pre(Method method, Object bean, Object[] args) {
        preCalledAdvice2++;
      }

      @Override
      public void post(Method method, Object bean, Object[] args) {
        postCalledAdvice2++;
      }
    };
    factory.registerAdvice(SideEffect.class, advice1);
    factory.registerAdvice(SideEffect.class, advice2);
    var bean = factory.create(FooService.class);
    bean.foo();
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
    var advice1 = new Advice() {
      int preCalledAdvice1;
      int postCalledAdvice1;

      @Override
      public void pre(Method method, Object bean, Object[] args) {
        preCalledAdvice1++;
      }

      @Override
      public void post(Method method, Object bean, Object[] args) {
        postCalledAdvice1++;
      }
    };
    var advice2 = new Advice() {
      int preCalledAdvice2;
      int postCalledAdvice2;

      @Override
      public void pre(Method method, Object bean, Object[] args) {
        preCalledAdvice2++;
      }

      @Override
      public void post(Method method, Object bean, Object[] args) {
        postCalledAdvice2++;
      }
    };
    factory.registerAdvice(SideEffect.class, advice1);
    factory.registerAdvice(SideEffect2.class, advice2);
    var bean = factory.create(FooService.class);
    bean.foo();
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
    var advice = new Advice() {
      @Override
      public void pre(Method method, Object bean, Object[] args) {
        box.preCalled++;
      }

      @Override
      public void post(Method method, Object bean, Object[] args) {
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
    var bean = factory.create(Computation.class);
    assertEquals(42L, bean.mult(21, 2));
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
    factory.registerInterceptor(SideEffect.class, interceptor);
    var bean = factory.create(Computation.class);

    // called twice
    assertEquals(42L, bean.div(84, 2));
    assertEquals(42L, bean.div(84, 2));

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
    BeanFactory.Interceptor interceptor = new Advice() {
      @Override
      public void pre(Method method, Object bean, Object[] args) {
        box.preCalled++;
      }

      @Override
      public void post(Method method, Object bean, Object[] args) {
         box.postCalled++;
      }
    }.asInterceptor();
    factory.registerInterceptor(SideEffect.class, interceptor);
    var bean = factory.create(Computation.class);

    var sum = 0L;
    for(var i = 0; i < 100_000; i++) {
      if (i == 50_000) {
        factory.unregisterInterceptor(SideEffect.class, interceptor);
      }
      sum = bean.add(sum, i);
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
    factory.registerInterceptor(SideEffect.class, (kind, method, type) -> { throw new AssertionError(); });
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
    factory.registerInterceptor(SideEffect.class, (kind, method, type) -> {
      return MethodHandles.empty(methodType(void.class));
    });
    var bean = factory.create(Computation.class);

    assertThrows(BootstrapMethodError.class, () -> bean.add(2, 3));
  }

  @interface BadAnnotationNoRuntimeRetention {}

  @Test
  public void registerAdviceBadAnnotation() {
    var beanFactory = new BeanFactory(lookup());
    var advice = new Advice() {
      @Override
      public void pre(Method method, Object bean, Object[] args) {
        fail();
      }

      @Override
      public void post(Method method, Object bean, Object[] args) {
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
    var advice = new Advice() {
      @Override
      public void pre(Method method, Object bean, Object[] args) {
        fail();
      }

      @Override
      public void post(Method method, Object bean, Object[] args) {
        fail();
      }
    };
    assertThrows(IllegalArgumentException.class,
        () -> beanFactory.registerAdvice((Class<? extends Annotation>)(Class<?>)Object.class, advice));
  }

  @Retention(RetentionPolicy.RUNTIME)
  private @interface ProvideImplementation {}

  @Test
  public void registerInvocationHandler() {
    interface Computation {
      @ProvideImplementation
      int add(int v1, int v2);
    }

    var factory = new BeanFactory(lookup());
    var box = new Object() {
      int invokeCalled;
      Object bean;
    };
    factory.registerInvocationHandler(ProvideImplementation.class, (method, bean, args) -> {
      assertEquals(Computation.class.getMethod("add", int.class, int.class), method);
      box.invokeCalled++;
      box.bean = bean;
      return ((Integer) args[0]) + (Integer) args[1];
    });
    var bean = factory.create(Computation.class);
    assertAll(
        () -> assertEquals(42, bean.add(40, 2)),
        () -> assertEquals(1, box.invokeCalled),
        () -> assertSame(bean, box.bean)
    );
  }

  @Test
  public void registerImplementor() {
    @ProvideImplementation
    interface Computation {
      int add(int v1, int v2);
    }

    var factory = new BeanFactory(lookup());
    factory.registerImplementor(ProvideImplementation.class, (method, type) -> {
      try {
        assertEquals(Computation.class.getMethod("add", int.class, int.class), method);
      } catch (NoSuchMethodException e) {
        throw new AssertionError(e);
      }
      assertEquals(methodType(int.class, Object.class, int.class, int.class), type);
      var constant = constant(int.class, 42);
      return MethodHandles.dropArguments(constant, 0, Object.class, int.class, int.class);
    });
    var bean = factory.create(Computation.class);
    assertEquals(42, bean.add(4, 5));
  }

  @Test
  public void registerImplementorReturnNull() {
    interface Computation {
      @ProvideImplementation
      int add(int v1, int v2);
    }

    var factory = new BeanFactory(lookup());
    factory.registerImplementor(ProvideImplementation.class, (method, type) -> null);
    var bean = factory.create(Computation.class);
    assertThrows(BootstrapMethodError.class, () -> bean.add(4, 5));
  }

  @Test
  public void registerImplementorWrongMethodType() {
    interface Computation {
      @ProvideImplementation
      int add(int v1, int v2);
    }

    var factory = new BeanFactory(lookup());
    factory.registerImplementor(ProvideImplementation.class, (method, type) -> empty(methodType(void.class)));
    var bean = factory.create(Computation.class);
    assertThrows(BootstrapMethodError.class, () -> bean.add(4, 5));
  }

  @Test
  public void noImplementor() {
    interface Computation {
      int add(int v1, int v2);
    }

    var factory = new BeanFactory(lookup());
    var bean = factory.create(Computation.class);
    assertThrows(NoSuchMethodError.class, () -> bean.add(4, 5));
  }

  @Test
  public void registerInvocationHandlerTwice() {
    var factory = new BeanFactory(lookup());
    factory.registerInvocationHandler(ProvideImplementation.class, (method, bean, args) -> fail());
    assertThrows(IllegalStateException.class, () -> factory.registerInvocationHandler(ProvideImplementation.class, (method, bean, args) -> fail()));
  }

  @Test
  public void registerImplementorTwice() {
    interface Computation {
      int add(int v1, int v2);
    }

    var factory = new BeanFactory(lookup());
    factory.registerImplementor(ProvideImplementation.class, (method, type) -> fail());
    assertThrows(IllegalStateException.class, () -> factory.registerImplementor(ProvideImplementation.class, (method, type) -> fail()));
  }

  @Retention(RetentionPolicy.RUNTIME)
  @interface ProvideImplementation2 { }

  @Test
  public void registerInvocationHandlesTwoAnnotations() {
    interface Computation {
      @ProvideImplementation @ProvideImplementation2
      int add(int v1, int v2);
    }

    var factory = new BeanFactory(lookup());
    factory.registerInvocationHandler(ProvideImplementation.class, (method, bean, args) -> fail());
    factory.registerInvocationHandler(ProvideImplementation2.class, (method, bean, args) -> fail());
    var bean = factory.create(Computation.class);
    assertThrows(BootstrapMethodError.class, () -> bean.add(4, 5));
  }

  @Test
  public void registerImplementorsTwoAnnotations() {
    interface Computation {
      @ProvideImplementation @ProvideImplementation2
      int add(int v1, int v2);
    }

    var factory = new BeanFactory(lookup());
    factory.registerImplementor(ProvideImplementation.class, (method, type) -> fail());
    factory.registerImplementor(ProvideImplementation2.class, (method, type) -> fail());
    var bean = factory.create(Computation.class);
    assertThrows(BootstrapMethodError.class, () -> bean.add(4, 5));
  }
}