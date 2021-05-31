package com.github.forax.virtualbean;

import com.github.forax.virtualbean.BeanFactory.Advice;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.util.ArrayDeque;

import static java.lang.invoke.MethodHandles.lookup;

public class Example8 {
  @Retention(RetentionPolicy.RUNTIME)
  @interface Timed {}

  private static final ThreadLocal<ArrayDeque<Long>> TIMER = ThreadLocal.withInitial(ArrayDeque::new);

  public static void main(String[] args) {
    var beanFactory = new BeanFactory(lookup());
    beanFactory.registerAdvice(Timed.class, new Advice() {
      @Override
      public void pre(Method method, Object bean, Object[] args) {
        TIMER.get().add(System.nanoTime());
      }

      @Override
      public void post(Method method, Object bean, Object[] args) {
        var end = System.nanoTime();
        var stack = TIMER.get();
        var start = stack.pop();
        System.out.println("elapsed time " + (end -start) + " ns in " + method);
        if (stack.isEmpty()) {
          TIMER.remove();
        }
      }
    });

    interface HelloManager {
      @Timed
      default void sayHello(String text) {
        System.out.println("hello " + text);
      }
    }

    var helloManager = beanFactory.create(HelloManager.class);
    helloManager.sayHello("Bob");
  }
}
