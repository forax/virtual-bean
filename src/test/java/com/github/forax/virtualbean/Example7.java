package com.github.forax.virtualbean;

import com.github.forax.virtualbean.BeanFactory.Advice;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;

import static java.lang.invoke.MethodHandles.lookup;

/**
 * Calls a listener each time a property annotated with EventEmitter is changed.
 */
public class Example7 {
  @Retention(RetentionPolicy.RUNTIME)
  @interface EventEmitter { }

  @FunctionalInterface
  interface Listener<B extends ActiveBean<B>> {
    record Event<B>(B source, Metadata.Property property) {}

    void fire(Event<B> event);
  }

  interface ActiveBean<B extends ActiveBean<B>> {
    void setListener(Listener<B> listener);
    Listener<B> getListener();
  }

  private static String propertyName(String name, int index) {
    return Character.toLowerCase(name.charAt(index)) + name.substring(index + 1);
  }

  public static void main(String[] args) {
    var beanFactory = new BeanFactory(lookup());
    beanFactory.registerAdvice(EventEmitter.class, Metadata::isSetter, new Advice() {
      @Override
      public void pre(Method method, Object bean, Object[] args) { }

      @Override
      @SuppressWarnings("rawtypes, unchecked")
      public void post(Method method, Object bean, Object[] args) {
        var source = (ActiveBean) bean;
        var listener = source.getListener();
        if (listener == null) {
          return;
        }
        var metadata = Metadata.of(method.getDeclaringClass());
        var propertyName = propertyName(method.getName(), 3);
        listener.fire(new Listener.Event<>(source, metadata.properties().value(propertyName)));
      }
    });

    interface UserBean extends ActiveBean<UserBean> {
      @EventEmitter
      void setLogin(String login);
      String getLogin();

      @EventEmitter
      void setAdmin(boolean admin);
      boolean isAdmin();
    }

    var userBean = beanFactory.create(UserBean.class);
    userBean.setListener(event -> System.out.println(event.source().getLogin() + " property " + event.property().name() + " has changed"));
    userBean.setLogin("Bob");
    userBean.setAdmin(true);
  }
}
