/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2010 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.helger.jcodemodel;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * Dynamically implements the typed annotation writer interfaces.
 * 
 * @author Kohsuke Kawaguchi
 */
class TypedAnnotationWriter <A extends Annotation, W extends JAnnotationWriter <A>> implements InvocationHandler, JAnnotationWriter <A>
{
  /**
   * This is what we are writing to.
   */
  private final JAnnotationUse use;

  /**
   * The annotation that we are writing.
   */
  private final Class <A> annotation;

  /**
   * The type of the writer.
   */
  private final Class <W> writerType;

  /**
   * Keeps track of writers for array members. Lazily created.
   */
  private Map <String, JAnnotationArrayMember> arrays;

  public TypedAnnotationWriter (final Class <A> annotation, final Class <W> writer, final JAnnotationUse use)
  {
    this.annotation = annotation;
    this.writerType = writer;
    this.use = use;
  }

  public JAnnotationUse getAnnotationUse ()
  {
    return use;
  }

  public Class <A> getAnnotationType ()
  {
    return annotation;
  }

  @SuppressWarnings ("unchecked")
  public Object invoke (final Object proxy, final Method method, final Object [] args) throws Throwable
  {

    if (method.getDeclaringClass () == JAnnotationWriter.class)
    {
      try
      {
        return method.invoke (this, args);
      }
      catch (final InvocationTargetException e)
      {
        throw e.getTargetException ();
      }
    }

    final String name = method.getName ();
    Object arg = null;
    if (args != null && args.length > 0)
      arg = args[0];

    // check how it's defined on the annotation
    final Method m = annotation.getDeclaredMethod (name);
    final Class <?> rt = m.getReturnType ();

    // array value
    if (rt.isArray ())
    {
      return addArrayValue (proxy, name, rt.getComponentType (), method.getReturnType (), arg);
    }

    // sub annotation
    if (Annotation.class.isAssignableFrom (rt))
    {
      final Class <? extends Annotation> r = (Class <? extends Annotation>) rt;
      return new TypedAnnotationWriter (r, method.getReturnType (), use.annotationParam (name, r)).createProxy ();
    }

    // scalar value

    if (arg instanceof JType)
    {
      final JType targ = (JType) arg;
      checkType (Class.class, rt);
      if (m.getDefaultValue () != null)
      {
        // check the default
        if (targ.equals (targ.owner ().ref ((Class) m.getDefaultValue ())))
          return proxy; // defaulted
      }
      use.param (name, targ);
      return proxy;
    }

    // other Java built-in types
    checkType (arg.getClass (), rt);
    if (m.getDefaultValue () != null && m.getDefaultValue ().equals (arg))
      // defaulted. no need to write out.
      return proxy;

    if (arg instanceof String)
    {
      use.param (name, (String) arg);
      return proxy;
    }
    if (arg instanceof Boolean)
    {
      use.param (name, (Boolean) arg);
      return proxy;
    }
    if (arg instanceof Integer)
    {
      use.param (name, (Integer) arg);
      return proxy;
    }
    if (arg instanceof Class)
    {
      use.param (name, (Class) arg);
      return proxy;
    }
    if (arg instanceof Enum)
    {
      use.param (name, (Enum) arg);
      return proxy;
    }

    throw new IllegalArgumentException ("Unable to handle this method call " + method.toString ());
  }

  @SuppressWarnings ("unchecked")
  private Object addArrayValue (final Object proxy,
                                final String name,
                                final Class itemType,
                                final Class expectedReturnType,
                                final Object arg)
  {
    if (arrays == null)
      arrays = new HashMap <String, JAnnotationArrayMember> ();
    JAnnotationArrayMember m = arrays.get (name);
    if (m == null)
    {
      m = use.paramArray (name);
      arrays.put (name, m);
    }

    // sub annotation
    if (Annotation.class.isAssignableFrom (itemType))
    {
      final Class <? extends Annotation> r = itemType;
      if (!JAnnotationWriter.class.isAssignableFrom (expectedReturnType))
        throw new IllegalArgumentException ("Unexpected return type " + expectedReturnType);
      return new TypedAnnotationWriter (r, expectedReturnType, m.annotate (r)).createProxy ();
    }

    // primitive
    if (arg instanceof JType)
    {
      checkType (Class.class, itemType);
      m.param ((JType) arg);
      return proxy;
    }
    checkType (arg.getClass (), itemType);
    if (arg instanceof String)
    {
      m.param ((String) arg);
      return proxy;
    }
    if (arg instanceof Boolean)
    {
      m.param ((Boolean) arg);
      return proxy;
    }
    if (arg instanceof Integer)
    {
      m.param ((Integer) arg);
      return proxy;
    }
    if (arg instanceof Class)
    {
      m.param ((Class) arg);
      return proxy;
    }
    // TODO: enum constant. how should we handle it?

    throw new IllegalArgumentException ("Unable to handle this method call ");
  }

  /**
   * Check if the type of the argument matches our expectation. If not, report
   * an error.
   */
  private void checkType (final Class <?> actual, final Class <?> expected)
  {
    if (expected == actual || expected.isAssignableFrom (actual))
      return; // no problem

    if (expected == JCodeModel.boxToPrimitive.get (actual))
      return; // no problem

    throw new IllegalArgumentException ("Expected " + expected + " but found " + actual);
  }

  /**
   * Creates a proxy and returns it.
   */
  @SuppressWarnings ("unchecked")
  private W createProxy ()
  {
    return (W) Proxy.newProxyInstance (SecureLoader.getClassClassLoader (writerType), new Class [] { writerType }, this);
  }

  /**
   * Creates a new typed annotation writer.
   */
  @SuppressWarnings ("unchecked")
  static <W extends JAnnotationWriter <?>> W create (final Class <W> w, final JAnnotatable annotatable)
  {
    final Class <? extends Annotation> a = findAnnotationType (w);
    return (W) new TypedAnnotationWriter (a, w, annotatable.annotate (a)).createProxy ();
  }

  private static Class <? extends Annotation> findAnnotationType (final Class <?> clazz)
  {
    for (final Type t : clazz.getGenericInterfaces ())
    {
      if (t instanceof ParameterizedType)
      {
        final ParameterizedType p = (ParameterizedType) t;
        if (p.getRawType () == JAnnotationWriter.class)
          return (Class <? extends Annotation>) p.getActualTypeArguments ()[0];
      }
      if (t instanceof Class <?>)
      {
        // recursive search
        final Class <? extends Annotation> r = findAnnotationType ((Class <?>) t);
        if (r != null)
          return r;
      }
    }
    return null;
  }
}
