package com.github.forax.virtualbean;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.SwitchPoint;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.empty;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.methodType;
import static java.util.Objects.requireNonNull;

/**
 * Class that creates and manages {@link Metadata virtual bean} instances.
 *
 * A bean factory
 * <ul>
 *   <li>is created with a {@link Lookup},
 *   <li>can {@link #registerAdvice(Class, Advice) register} {@link Advice}s and/or
 *           {@link #registerInterceptor(Class, Interceptor)} (Class, Advice) register} {@link Interceptor}s and
 *   <li>can {@link #create(Class) create} bean instances with methods that can be intercepted
 *       by the advices and interceptor registered in the past or in the future.
 * </ul>
 */
public class BeanFactory {
  private static final MethodHandle BSM;
  static {
    var lookup = lookup();
    try {
      BSM = lookup.findVirtual(BeanFactory.class, "bsm",
          methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class, MethodHandle.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * An interceptor returns a method handle that will be called before (@link Kind#pre) or
   * after {@link Kind#POST} a method.
   * 
   * @see BeanFactory#registerInterceptor(Class, Predicate, Interceptor)
   */
  @FunctionalInterface
  public interface Interceptor {
    /**
     * The kind of interceptors
     *
     * @see Interceptor#intercept(Kind, Method, MethodType)
     */
    enum Kind {
      /**
       * Indicate that the method handle returned by {@link Interceptor#intercept(Kind, Method, MethodType)}
       * will be called before the intercepted method.
       */
      PRE,

      /**
       * Indicate that the method handle returned by {@link Interceptor#intercept(Kind, Method, MethodType)}
       * will be called after the intercepted method.
       */
      POST
    }

    /**
     * Returns a method handle that will be called before (@link Kind#pre) or
     * after {@link Kind#POST} a method.
     *
     * The returned method handle should have the same {@link MethodHandle#type() method type} as
     * the method type pass as argument. The method type return type is always {@code void},
     * the first parameter type is always {@code Object}, the following parameter types are
     * the same as the intercepted method parameter types.
     *
     * An interceptor can be {@link BeanFactory#unregisterInterceptor(Class, Interceptor) unregistered},
     * in that case the method handles of the other interceptors may be lost, in that case,
     * this method may be called again to re-create the lost method handles.
     * Thus this method as to be idempotent.
     *
     * @param kind kind of interceptor
     * @param method the method that will decorated by the returned method handle
     * @param type the method type of the returned method handle
     * @return a method handle that will be called before or after the intercepted method with
     *         the same arguments as the method or {code null} if there is no interception.
     */
    MethodHandle intercept(Kind kind, Method method, MethodType type);
  }

  /**
   * Holder pattern to delay the initialization of PRE and POST until an advice need to be resolved.
   */
  private static class AdviceImpl {
    private static final MethodHandle PRE, POST;
    static {
      var lookup = lookup();
      var adviceMethodType = methodType(void.class, Method.class, Object.class, Object[].class);
      try {
        PRE = lookup.findVirtual(Advice.class, "pre", adviceMethodType);
        POST = lookup.findVirtual(Advice.class, "post", adviceMethodType);
      } catch(NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }
  }

  /**
   * An advice defines two methods that will be called around an intercepted method.
   *
   * This interface is easier to use that the {@link Interceptor} interface but
   * because it requires to boxed all arguments of the intercepted method calls,
   * using an advice is less performant than using an interceptor.
   *
   * @see BeanFactory#registerAdvice(Class, Predicate, Advice)
   */
  public interface Advice {
    /**
     * Called before the intercepted method with the method arguments boxed into an array.
     * @param method the intercepted method
     * @param bean the bean instance on which the method is called
     * @param args the method call arguments
     * @throws Throwable any exceptions
     */
    void pre(Method method, Object bean, Object[] args) throws Throwable;

    /**
     * Called after the intercepted method with the method arguments boxed into an array.
     * @param method the intercepted method
     * @param bean the bean instance on which the method is called
     * @param args the method call arguments
     * @throws Throwable any exceptions
     */
    void post(Method method, Object bean, Object[] args) throws Throwable;

    /**
     * Creates an interceptor from this advice.
     * @return an interceptor that will use the method {@link #pre(Method, Object, Object[])}
     * and {@link #post(Method, Object, Object[])} to do the interception
     */
    default Interceptor asInterceptor() {
      return (kind, method, type) -> {
        var adviceMethod = switch (kind) {
          case PRE -> AdviceImpl.PRE;
          case POST -> AdviceImpl.POST;
        };
        return insertArguments(adviceMethod, 0, this, method)
            .asCollector(Object[].class, type.parameterCount() - 1)
            .asType(type);
      };
    }
  }

  @FunctionalInterface
  public interface Implementor {
    MethodHandle implement(Method method, MethodType type);
  }

  private static class InvocationHandlerImpl {
    private static final MethodHandle INVOKE;
    static {
      var lookup = lookup();
      try {
        INVOKE = lookup.findVirtual(InvocationHandler.class, "invoke",
            methodType(Object.class, Method.class, Object.class, Object[].class));
      } catch(NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }
  }

  @FunctionalInterface
  public interface InvocationHandler {
    Object invoke(Method method, Object bean, Object[] args) throws Throwable;

    default Implementor asImplementor() {
      return (method, type) ->
          insertArguments(InvocationHandlerImpl.INVOKE, 0, this, method)
            .asCollector(Object[].class, type.parameterCount() - 1)
            .asType(type);
    }
  }

  /**
   * A tuple (methodFilter, Interceptor)
   */
  private record InterceptorData(Predicate<Method> methodFilter, Interceptor interceptor) { }

  private final Lookup lookup;
  private final Object switchPointLock = new Object();
  private SwitchPoint switchPoint;
  private final HashMap<Class<?>, Implementor> implementorMap = new HashMap<>();
  private final HashMap<Class<?>, MethodHandle> beanFactoryMap = new HashMap<>();
  private final HashMap<Class<?>, List<InterceptorData>> interceptorMap = new HashMap<>();

  /**
   * Creates a registry with a lookup, all proxies created by this registry will use that lookup,
   * thus will have the same access right as the class in which the {@link Lookup lookup} was created
   * @param lookup the lookup that will be used to {@link #create(Class) create the bean instances}
   */
  public BeanFactory(Lookup lookup) {
    this.lookup = requireNonNull(lookup);
    synchronized (switchPointLock) {
      switchPoint = new SwitchPoint();
    }
  }

  /**
   * Register an advice that will be called on all methods that either have their declaring class
   * annotated by the annotation, the method annotated by the annotation of one of the parameter or
   * return value annotated by the annotation. Annotations on types are not considered.
   *
   * This call is semantically equivalent to
   * <pre>
   *   registerAdvice(annotationType, __ -> true, advice)
   * </pre>
   *
   * @param annotationType the type of the annotation
   * @param advice the advice to call if the method is decorated by an annotation of the {@code annotationType}
   * @throws IllegalArgumentException if the annotation has a retention different
   *         from {@link RetentionPolicy#RUNTIME}
   */
  public void registerAdvice(Class<? extends Annotation> annotationType, Advice advice) {
    registerAdvice(annotationType, __ -> true, advice);
  }

  /**
   * Register an advice that will be called on all methods that either have their declaring class
   * annotated by the annotation, the method annotated by the annotation of one of the parameter or
   * return value annotated by the annotation. Annotations on types are not considered.
   *
   * This call is semantically equivalent to
   * <pre>
   *   registerInterceptor(annotationType, methodFilter, advice.asInterceptor())
   * </pre>
   *
   * @param annotationType the type of the annotation
   * @param methodFilter a predicate calls to ask if the advice applied to a particular method
   * @param advice the advice to call if the method is decorated by an annotation of the {@code annotationType}
   * @throws IllegalArgumentException if the annotation has a retention different
   *         from {@link RetentionPolicy#RUNTIME}
   *
   * @see #registerInterceptor(Class, Predicate, Interceptor)
   */
  public void registerAdvice(Class<? extends Annotation> annotationType, Predicate<Method> methodFilter, Advice advice) {
    registerInterceptor(annotationType, methodFilter, advice.asInterceptor());
  }

  /**
   * Register an interceptor that will be called that either have their declaring class
   * annotated by the annotation, the method annotated by the annotation of one of the parameter or
   * return value annotated by the annotation. Annotations on types are not considered.
   *
   * This call is semantically equivalent to
   * <pre>
   *   registerInterceptor(annotationType, __ -> true, interceptor)
   * </pre>
   *
   * @param annotationType the type of the annotation
   * @param interceptor the interceptor to call if the method is decorated by an annotation of the {@code annotationType}
   * @throws IllegalArgumentException if the annotation has a retention different
   *         from {@link RetentionPolicy#RUNTIME}
   */
  public void registerInterceptor(Class<? extends Annotation> annotationType, Interceptor interceptor) {
    registerInterceptor(annotationType, __ -> true, interceptor);
  }

  /**
   * Register an interceptor that will be called that either have their declaring class
   * annotated by the annotation, the method annotated by the annotation of one of the parameter or
   * return value annotated by the annotation. Annotations on types are not considered.
   *
   * @param annotationType the type of the annotation
   * @param methodFilter a predicate calls to ask if the interceptor applied to a particular method
   * @param interceptor the interceptor to call if the method is decorated by an annotation of the {@code annotationType}
   * @throws IllegalArgumentException if the annotation has a retention different
   *         from {@link RetentionPolicy#RUNTIME}
   */
  public void registerInterceptor(Class<? extends Annotation> annotationType, Predicate<Method> methodFilter, Interceptor interceptor) {
    requireNonNull(annotationType);
    requireNonNull(methodFilter);
    requireNonNull(interceptor);
    if (!annotationType.isAnnotation()) {
      throw new IllegalArgumentException("annotationType is not an annotation");
    }
    var retention = annotationType.getAnnotation(Retention.class);
    if (retention == null || retention.value() != RetentionPolicy.RUNTIME) {
      throw new IllegalArgumentException("@Retention of  " + annotationType.getName() + " should be RetentionPolicy.RUNTIME)");
    }
    interceptorMap.computeIfAbsent(annotationType, __ -> new ArrayList<>()).add(new InterceptorData(methodFilter, interceptor));
    invalidateSwitchPoint();
  }

  /**
   * Unregister an interceptor previously registered for an annotation type.
   * This operation can be quite slow because it will trigger at least the de-optimization of all codes
   * using that interceptor.
   *
   * @param annotationType the type of the annotation
   * @param interceptor the interceptor to unregister
   * @throws IllegalStateException if there is no interceptor registered for this annotation type
   */
  public void unregisterInterceptor(Class<? extends Annotation> annotationType, Interceptor interceptor) {
    Objects.requireNonNull(annotationType);
    Objects.requireNonNull(interceptor);
    interceptorMap.compute(annotationType, (__, interceptorDataList) -> {
      if (interceptorDataList == null) {
        throw new IllegalStateException("no interceptor registered for annotation " + annotationType);
      }
      boolean modified = interceptorDataList.removeIf(interceptorData -> interceptorData.interceptor.equals(interceptor));
      if (!modified) {
        throw new IllegalStateException("no interceptor " + interceptor + " is registered for annotation " + annotationType);
      }
      invalidateSwitchPoint();
      return interceptorDataList.isEmpty()? null: interceptorDataList;
    });
  }

  public void registerInvocationHandler(Class<?> beanType, InvocationHandler invocationHandler) {
    requireNonNull(beanType);
    requireNonNull(invocationHandler);
    registerImplementor(beanType, invocationHandler.asImplementor());
  }

  public void registerImplementor(Class<?> beanType, Implementor implementor) {
    requireNonNull(beanType);
    requireNonNull(implementor);
    var result = implementorMap.put(beanType, implementor);
    if (result != null) {
      throw new IllegalStateException("There already an existing implementor for " + beanType.getName());
    }
  }

  private List<InterceptorData> getInterceptorData(Class<?> annotationClass) {
    return interceptorMap.getOrDefault(annotationClass, List.of());
  }

  private void invalidateSwitchPoint() {
    synchronized (switchPointLock) {
      SwitchPoint.invalidateAll(new SwitchPoint[] { switchPoint });
      switchPoint = new SwitchPoint();
    }
  }

  /**
   * Call site that will be used to dynamically update {@link Interceptor.Kind#PRE} and
   * {@link Interceptor.Kind#POST} interceptors.
   */
  private final class InterceptorCallSite extends MutableCallSite {
    private static final MethodHandle INVALIDATE;
    static {
      try {
        INVALIDATE = lookup().findVirtual(InterceptorCallSite.class, "invalidate", methodType(MethodHandle.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private final Interceptor.Kind kind;
    private final Method method;

    private InterceptorCallSite(Interceptor.Kind kind, Method method, MethodType type) {
      super(type);
      this.kind = kind;
      this.method = method;
      setTarget(prepare(target(kind, method, type)));
    }

    /**
     * Called if a new interceptor was added in the past, changing the list of interceptors to call
     * @return all the interceptors to called flatten as one method handle
     */
    private MethodHandle invalidate() {
      var target = target(kind, method, type());
      setTarget(prepare(target));
      return target;
    }

    /**
     * Wrap the target with a guard that check that the target is still valid.
     * @param target all the interceptors to called flatten as one method handle
     * @return the guard
     */
    private MethodHandle prepare(MethodHandle target) {
      var invoker = MethodHandles.exactInvoker(type());
      var fallback = MethodHandles.foldArguments(invoker, INVALIDATE.bindTo(this));

      SwitchPoint switchPoint;
      synchronized (switchPointLock) {
        switchPoint = BeanFactory.this.switchPoint;
      }
      return switchPoint.guardWithTest(target, fallback);
    }
  }

  /**
   * Call the registered interceptors and flatten all method handles into one method handle
   *
   * @param kind the kind of interceptor {@link Interceptor.Kind#PRE} or {@link Interceptor.Kind#POST}
   * @param method the intercepted method
   * @param type the method type of the resulting method handle
   * @return a method handle combining all the intercepting method handles
   */
  private MethodHandle target(Interceptor.Kind kind, Method method, MethodType type) {
    var interceptors =
        Stream.of(
            interceptorStream(Arrays.stream(method.getDeclaringClass().getAnnotations()), method),
            interceptorStream(Arrays.stream(method.getAnnotations()), method),
            interceptorStream(Arrays.stream(method.getParameterAnnotations()).flatMap(Arrays::stream), method)
        ).flatMap(s -> s).collect(Collectors.toSet());

    // System.err.println("method " + kind + " " + method.getName() + " interceptors " + interceptors);

    return flattenInterceptors(interceptors, method, kind, type);
  }

  /**
   * Called by each bean methods to get create a call site before and after the implementation
   * @param lookup the lookup corresponding to the proxy class
   * @param name either {@code pre}, {@code post} or {@code impl}.
   * @param type the method type, the same signature as the intercepted method but
   *             the bean instance is passed as first parameter, typed as {code Object},
   *             and the return type is always {0code void}
   * @param intercepted a method handle corresponding to the intercepted method
   * @return a new call site
   */
  private CallSite bsm(MethodHandles.Lookup lookup, String name, MethodType type, MethodHandle intercepted) {
    var mhInfo = lookup.revealDirect(intercepted);
    var method = mhInfo.reflectAs(Method.class, lookup);
    return switch(name) {
      case "pre" -> new InterceptorCallSite(Interceptor.Kind.PRE, method, type);
      case "post" -> new InterceptorCallSite(Interceptor.Kind.POST, method, type);
      case "invoke" -> new ConstantCallSite(implementorTarget(method, type));
      default -> throw new LinkageError("unknown feature " + name);
    };
  }

  private static class ImplementorImpl {
    private static final MethodHandle NO_IMPLEMENTATION;
    static {
      var lookup = MethodHandles.lookup();
      try {
        NO_IMPLEMENTATION = lookup.findStatic(ImplementorImpl.class, "noImplementation", methodType(void.class, Method.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private static void noImplementation(Method method) {
      throw new NoSuchMethodError("no implementor for method " + method);
    }
  }

  private MethodHandle implementorTarget(Method method, MethodType type) {
    var implementor = implementorMap.get(method.getDeclaringClass());
    if (implementor == null) {  // linkage error
      var mh = ImplementorImpl.NO_IMPLEMENTATION.bindTo(method);
      return dropArguments(mh, 0, type.parameterList()).asType(type);
    }
    var mh = implementor.implement(method, type);
    if (mh == null) {
      throw new NullPointerException("implementor of method " + method + " returns null");
    }
    if (!type.equals(mh.type())) {
      throw new WrongMethodTypeException("method " + method + ", invalid implementor " + implementor + " " + mh + " is not compatible with " + type);
    }
    return mh;
  }

  private Stream<Interceptor> interceptorStream(Stream<Annotation> annotations, Method method) {
    return annotations
        .flatMap(annotation ->
            getInterceptorData(annotation.annotationType()).stream()
                .filter(interceptorData -> interceptorData.methodFilter.test(method))
                .map(InterceptorData::interceptor)
        );
  }

  private static MethodHandle flattenInterceptors(Set<Interceptor> interceptors, Method method, Interceptor.Kind kind, MethodType type) {
    MethodHandle target = null;
    for(var interceptor: interceptors) {
      var mh = interceptor.intercept(kind, method, type);
      if (mh == null) {
        continue;  // skip
      }
      if (!mh.type().equals(type)) {
        throw new WrongMethodTypeException("method " + method + ", invalid interceptor " + interceptor + " " + mh + " is not compatible with " + type);
      }
      if (target == null) {
        target = mh;
      } else {
        target = MethodHandles.foldArguments(target, mh);
      }
    }

    if (target != null) {
      return target;
    }
    return empty(type);
  }

  /**
   * Create an instance from an interface defining {@link Metadata#properties()} and
   * {@link Metadata#services()}.
   *
   * @param type an interface that defines {@link Metadata#properties()} and {@link Metadata#services()}
   * @param <I> the type of the interface
   * @return a new instance of the interface
   * @throws IllegalArgumentException if type is not an interface
   * @throws IllegalStateException if the interface does not describe a virtual bean
   *
   * @see Metadata#of(Class)
   */
  public <I> I create(Class<I> type) {
    requireNonNull(type);
    if (!(type.isInterface())) {
      throw new IllegalArgumentException("type " + type.getName() + " should be an interface");
    }
    var factory = beanFactory(type);
    try {
      return type.cast(factory.invoke());
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable throwable) {
      throw new UndeclaredThrowableException(throwable);
    }
  }

  private MethodHandle beanFactory(Class<?> type) {
    return beanFactoryMap.computeIfAbsent(type, t -> ProxyGenerator.createProxyFactory(lookup, t, BSM.bindTo(this)));
  }
}
