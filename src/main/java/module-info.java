/**
 * A small yet powerful implementation of the concept of virtual bean.
 * <p>
 * A virtual bean is a bean specified using an interface.
 * This interface contains to kind of methods
 * <ul>
 *   <li>getter and setters, the implementation of those is automatically provided
 *   <li>services that are abstract or default method with annotations.
 *       The implementation of the services is defined by specifying interceptor
 *       that will be called if an annotation is present on the service.
 * </ul>
 *
 * see {@link com.github.forax.virtualbean.BeanFactory} for more info.
 */
module fr.umlv.virtualbean {
  requires org.objectweb.asm;
  requires org.objectweb.asm.util;  // for bytecode diagnostics
}