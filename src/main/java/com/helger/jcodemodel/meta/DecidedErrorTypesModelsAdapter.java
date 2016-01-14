/**
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2010 Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright 2013-2016 Philip Helger + contributors
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
package com.helger.jcodemodel.meta;

import java.text.MessageFormat;
import java.util.Collection;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

import com.helger.jcodemodel.AbstractJClass;
import com.helger.jcodemodel.AbstractJType;
import com.helger.jcodemodel.EClassType;
import com.helger.jcodemodel.JClassAlreadyExistsException;
import com.helger.jcodemodel.JCodeModel;
import com.helger.jcodemodel.JDefinedClass;
import com.helger.jcodemodel.JMod;
import com.helger.jcodemodel.JPackage;

/**
 * @author Victor Nazarov &lt;asviraspossible@gmail.com&gt;
 */
class DecidedErrorTypesModelsAdapter
{
  private static final Logger logger = Logger.getLogger (DecidedErrorTypesModelsAdapter.class.getName ());

  static int toJMod (final Collection <Modifier> modifierCollection)
  {
    int modifiers = 0;
    for (final Modifier modifier : modifierCollection)
    {
      switch (modifier)
      {
        case ABSTRACT:
          modifiers |= JMod.ABSTRACT;
          break;
        case FINAL:
          modifiers |= JMod.FINAL;
          break;
        case NATIVE:
          modifiers |= JMod.NATIVE;
          break;
        case PRIVATE:
          modifiers |= JMod.PRIVATE;
          break;
        case PROTECTED:
          modifiers |= JMod.PROTECTED;
          break;
        case PUBLIC:
          modifiers |= JMod.PUBLIC;
          break;
        case STATIC:
          modifiers |= JMod.STATIC;
          break;
        case SYNCHRONIZED:
          modifiers |= JMod.SYNCHRONIZED;
          break;
        case TRANSIENT:
          modifiers |= JMod.TRANSIENT;
          break;
        case VOLATILE:
          modifiers |= JMod.VOLATILE;
          break;
        default:
          logger.log (Level.WARNING, "Skpping unsupported modifier: {0}", modifier);
      }
    }
    return modifiers;
  }

  private static EClassType toClassType (final ElementKind kind)
  {
    switch (kind)
    {
      case CLASS:
        return EClassType.CLASS;
      case ENUM:
        return EClassType.ENUM;
      case INTERFACE:
        return EClassType.INTERFACE;
      case ANNOTATION_TYPE:
        return EClassType.ANNOTATION_TYPE_DECL;
      default:
        throw new UnsupportedOperationException ("Unsupported ElementKind: " + kind);
    }
  }

  private final Elements _elementUtils;
  private final ErrorTypePolicy _errorTypePolicy;
  private final JCodeModel _codeModel;

  DecidedErrorTypesModelsAdapter (final JCodeModel codeModel,
                                  final Elements elementUtils,
                                  final ErrorTypePolicy errorTypePolicy)
  {
    this._elementUtils = elementUtils;
    this._errorTypePolicy = errorTypePolicy;
    this._codeModel = codeModel;
  }

  public JDefinedClass getClass (final TypeElement element) throws CodeModelBuildingException, ErrorTypeFound
  {
    final Element enclosingElement = element.getEnclosingElement ();
    if (enclosingElement instanceof PackageElement)
    {
      final PackageElement packageElement = (PackageElement) enclosingElement;
      final JPackage jpackage = _codeModel._package (packageElement.getQualifiedName ().toString ());
      final JDefinedClass result = jpackage._getClass (element.getSimpleName ().toString ());
      if (result != null)
        return result;

      final JDefinedClass jclass = defineClass (element);
      jclass.hide ();
      return jclass;
    }
    else
      if (enclosingElement instanceof TypeElement)
      {
        final JDefinedClass enclosingClass = getClass ((TypeElement) enclosingElement);
        for (final JDefinedClass innerClass : enclosingClass.classes ())
        {
          final String fullName = innerClass.fullName ();
          if (fullName != null && fullName.equals (element.getQualifiedName ().toString ()))
          {
            return innerClass;
          }
        }
        throw new IllegalStateException (MessageFormat.format ("Inner class should always be defined if outer class is defined: inner class {0}, enclosing class {1}",
                                                               element,
                                                               enclosingClass));
      }
      else
        throw new IllegalStateException ("Enclosing element should be package or class");
  }

  private JDefinedClass defineClass (final TypeElement element) throws CodeModelBuildingException, ErrorTypeFound
  {
    final Element enclosingElement = element.getEnclosingElement ();
    if (enclosingElement instanceof PackageElement)
    {
      final PackageElement packageElement = (PackageElement) enclosingElement;
      return defineTopLevelClass (element, new TypeEnvironment (packageElement.getQualifiedName ().toString ()));
    }

    // Only top-level classes can be directly defined
    return getClass (element);
  }

  private JDefinedClass defineTopLevelClass (final TypeElement element,
                                             final TypeEnvironment environment) throws CodeModelBuildingException,
                                                                                ErrorTypeFound
  {
    final EClassType classType = toClassType (element.getKind ());
    int modifiers = toJMod (element.getModifiers ());
    if (classType.equals (EClassType.INTERFACE))
    {
      modifiers &= ~JMod.ABSTRACT;
      modifiers &= ~JMod.STATIC;
    }
    final Element enclosingElement = element.getEnclosingElement ();
    if (!(enclosingElement instanceof PackageElement))
    {
      throw new IllegalStateException ("Expecting top level class");
    }
    final PackageElement packageElement = (PackageElement) enclosingElement;
    final JPackage _package = _codeModel._package (packageElement.getQualifiedName ().toString ());
    JDefinedClass newClass;
    try
    {
      newClass = _package._class (modifiers, element.getSimpleName ().toString (), classType);
    }
    catch (final JClassAlreadyExistsException ex)
    {
      throw new CodeModelBuildingException (ex);
    }
    declareInnerClasses (newClass, element, environment);
    final ClassFiller filler = new ClassFiller (_codeModel, this, newClass);
    filler.fillClass (element, environment);
    return newClass;
  }

  private void declareInnerClasses (final JDefinedClass klass,
                                    final TypeElement element,
                                    final TypeEnvironment environment) throws CodeModelBuildingException
  {
    for (final Element enclosedElement : element.getEnclosedElements ())
    {
      if (enclosedElement.getKind ().equals (ElementKind.INTERFACE) ||
          enclosedElement.getKind ().equals (ElementKind.CLASS))
      {
        final EClassType classType = toClassType (enclosedElement.getKind ());
        int modifiers = toJMod (enclosedElement.getModifiers ());
        if (classType.equals (EClassType.INTERFACE))
        {
          modifiers &= ~JMod.ABSTRACT;
          modifiers &= ~JMod.STATIC;
        }
        JDefinedClass enclosedClass;
        try
        {
          enclosedClass = klass._class (modifiers, enclosedElement.getSimpleName ().toString (), classType);
        }
        catch (final JClassAlreadyExistsException ex)
        {
          throw new CodeModelBuildingException (ex);
        }
        declareInnerClasses (enclosedClass, (TypeElement) enclosedElement, environment);
      }
    }
  }

  void defineInnerClass (final JDefinedClass enclosingClass,
                         final TypeElement element,
                         final TypeEnvironment environment) throws CodeModelBuildingException, ErrorTypeFound
  {
    for (final JDefinedClass innerClass : enclosingClass.classes ())
    {
      if (innerClass.fullName ().equals (element.getQualifiedName ().toString ()))
      {
        final ClassFiller filler = new ClassFiller (_codeModel, this, innerClass);
        filler.fillClass (element, environment);
        return;
      }
    }
    throw new IllegalStateException (MessageFormat.format ("Inner class should always be defined if outer class is defined: inner class {0}, enclosing class {1}",
                                                           element,
                                                           enclosingClass));
  }

  AbstractJClass ref (final TypeElement element) throws CodeModelBuildingException, ErrorTypeFound
  {
    try
    {
      final Class <?> klass = Class.forName (element.getQualifiedName ().toString ());
      final AbstractJType declaredClass = _codeModel.ref (klass);
      return (AbstractJClass) declaredClass;
    }
    catch (final ClassNotFoundException ex)
    {
      return getClass (element);
    }
  }

  AbstractJType toJType (final TypeMirror type, final TypeEnvironment environment) throws CodeModelBuildingException,
                                                                                   ErrorTypeFound
  {
    try
    {
      return type.accept (new TypeMirrorToJTypeVisitor (_codeModel, this, _errorTypePolicy, environment), null);
    }
    catch (final RuntimeErrorTypeFound ex)
    {
      throw ex.getCause ();
    }
    catch (final RuntimeCodeModelBuildingException ex)
    {
      throw ex.getCause ();
    }
  }

  Map <? extends ExecutableElement, ? extends AnnotationValue> getElementValuesWithDefaults (final AnnotationMirror annotation)
  {
    return _elementUtils.getElementValuesWithDefaults (annotation);
  }
}
