package com.github.forax.virtualbean;

import com.github.forax.virtualbean.BeanFactory.Advice;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public class Example2 {
  @Retention(RetentionPolicy.RUNTIME)
  @interface Entity { }

  @Entity
  interface UserBean {
    String getName();
    void setName(String name);
    String getEmail();
    void setEmail(String name);
  }

  @Retention(RetentionPolicy.RUNTIME)
  @interface Transactional { }

  interface UserManager {
    @Transactional
    default UserBean createUser(BeanFactory beanFactory, String name, String email) {
      var user = beanFactory.proxy(UserBean.class);
      user.setName(name);
      user.setEmail(email);

      EntityManager.current().update();
      return user;
    }
  }

  private static final ThreadLocal<EntityManager> ENTITY_MANAGERS = new ThreadLocal<>();

  record EntityManager(Set<Object> dirtySet) {
    public static EntityManager current() {
      return ENTITY_MANAGERS.get();
    }

    public void update() {
      System.out.println("dirtySet " + dirtySet);
      dirtySet.clear();
    }
  }

  public static void main(String[] args) {
    var lookup = MethodHandles.lookup();
    var beanFactory = new BeanFactory(lookup);

    beanFactory.registerAdvice(Entity.class, Metadata::isSetter, new Advice() {
      @Override
      public void pre(Method method, Object proxy, Object[] args) { }

      @Override
      public void post(Method method, Object proxy, Object[] args) {
        var entityManager = EntityManager.current();
        if (entityManager != null) {
          entityManager.dirtySet.add(proxy);
        }
      }
    });
    beanFactory.registerAdvice(Transactional.class, new Advice() {
      @Override
      public void pre(Method method, Object proxy, Object[] args) {
        var dirtySet = Collections.newSetFromMap(new IdentityHashMap<>());
        var entityManager = new EntityManager(dirtySet);
        ENTITY_MANAGERS.set(entityManager);
      }

      @Override
      public void post(Method method, Object proxy, Object[] args) {
        ENTITY_MANAGERS.remove();
      }
    });


    var userManager = beanFactory.proxy(UserManager.class);
    var user = userManager.createUser(beanFactory, "Duke", "duke@openjdk.java.net");
    System.out.println("user " + user.getName() + " " + user.getEmail());
  }
}
