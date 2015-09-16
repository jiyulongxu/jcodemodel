/**
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2010 Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright 2013-2015 Philip Helger
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

import java.io.Closeable;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import com.helger.jcodemodel.util.ClassNameComparator;
import com.helger.jcodemodel.util.JCValueEnforcer;
import com.helger.jcodemodel.util.NullWriter;

/**
 * This is a utility class for managing indentation and other basic formatting
 * for PrintWriter.
 */
@NotThreadSafe
public class JFormatter implements Closeable
{
  /**
   * Used during the optimization of class imports. List of
   * {@link AbstractJClass}es whose short name is the same.
   *
   * @author Ryan.Shoemaker@Sun.COM
   */
  private final class Usages
  {
    private final String m_sName;

    private final List <AbstractJClass> m_aReferencedClasses = new ArrayList <AbstractJClass> ();

    /** true if this name is used as an identifier (like a variable name.) **/
    private boolean m_bIsVariableName;

    public Usages (@Nonnull final String sName)
    {
      m_sName = sName;
    }

    /**
     * @return true if the short name is ambiguous in context of enclosingClass
     *         and classes with this name can't be imported.
     */
    public boolean isAmbiguousIn (final JDefinedClass enclosingClass)
    {
      // more than one type with the same name
      if (m_aReferencedClasses.size () > 1)
        return true;

      // an id and (at least one) type with the same name
      if (m_bIsVariableName && !m_aReferencedClasses.isEmpty ())
        return true;

      // no references is always unambiguous
      if (m_aReferencedClasses.isEmpty ())
        return false;

      AbstractJClass aSingleRef = m_aReferencedClasses.get (0);
      if (aSingleRef instanceof JAnonymousClass)
      {
        aSingleRef = aSingleRef._extends ();
      }

      // special case where a generated type collides with a type in package
      // java.lang
      if (aSingleRef._package () == JFormatter.this.m_aPckJavaLang)
      {
        // make sure that there's no other class with this name within the
        // same package
        for (final JDefinedClass aClass : enclosingClass._package ().classes ())
        {
          // even if this is the only "String" class we use,
          // if the class called "String" is in the same package,
          // we still need to import it.
          if (aClass.name ().equals (aSingleRef.name ()))
          {
            // collision
            return true;
          }
        }
      }

      return false;
    }

    public boolean addReferencedType (@Nonnull final AbstractJClass clazz)
    {
      if (false)
        System.out.println ("Adding referenced type[" + m_sName + "]: " + clazz.fullName ());
      if (m_aReferencedClasses.contains (clazz))
        return false;
      return m_aReferencedClasses.add (clazz);
    }

    public boolean containsReferencedType (@Nullable final AbstractJClass clazz)
    {
      return m_aReferencedClasses.contains (clazz);
    }

    @Nonnull
    public AbstractJClass getSingleReferencedType ()
    {
      assert m_aReferencedClasses.size () == 1;
      return m_aReferencedClasses.get (0);
    }

    @Nonnull
    public List <AbstractJClass> getReferencedTypes ()
    {
      return m_aReferencedClasses;
    }

    public void setVariableName ()
    {
      // FIXME: Strangely special processing of inner-classes references
      // Why is this?
      // Should this be removed?
      for (final AbstractJClass aRefedType : m_aReferencedClasses)
      {
        if (aRefedType.outer () != null)
        {
          m_bIsVariableName = false;
          return;
        }
      }
      m_bIsVariableName = true;
    }

    /**
     * @return true if this name is used as an identifier (like a variable
     *         name.).
     */
    public boolean isVariableName ()
    {
      return m_bIsVariableName;
    }

    /**
     * @return true if this name is used as an type name (like class name.)
     */
    public boolean isTypeName ()
    {
      return !m_aReferencedClasses.isEmpty ();
    }

    @Override
    public String toString ()
    {
      final StringBuilder aSB = new StringBuilder ("Usages[").append (m_sName).append ("]");
      aSB.append ("; isVarName=").append (m_bIsVariableName);
      aSB.append ("; refedClasses=").append (m_aReferencedClasses);
      return aSB.toString ();
    }
  }

  private static enum EMode
  {
   /**
    * Collect all the type names and identifiers. In this mode we don't actually
    * generate anything.
    */
    COLLECTING,
   /**
    * Print the actual source code.
    */
    PRINTING,

   /**
    * Find any error types in output code. In this mode we don't actually
    * generate anything. <br/>
    * Only used by {@link JFormatter#containsErrorTypes(JDefinedClass)
    * containsErrorTypes} method
    */
    FIND_ERROR_TYPES
  }

  public static final String DEFAULT_INDENT_SPACE = "    ";

  /**
   * Special character token we use to differentiate '&gt;' as an operator and
   * '&gt;' as the end of the type arguments. The former uses '&gt;' and it
   * requires a preceding whitespace. The latter uses this, and it does not have
   * a preceding whitespace.
   */
  /* package */static final char CLOSE_TYPE_ARGS = '\uFFFF';

  /**
   * all classes and ids encountered during the collection mode.<br>
   * map from short type name to {@link Usages} (list of {@link AbstractJClass}
   * and ids sharing that name)
   **/
  private final Map <String, Usages> m_aCollectedReferences = new HashMap <String, Usages> ();

  /**
   * set of imported types (including package java types, even though we won't
   * generate imports for them)
   */
  private final Set <AbstractJClass> m_aImportedClasses = new HashSet <AbstractJClass> ();

  /**
   * The current running mode. Set to PRINTING so that a casual client can use a
   * formatter just like before.
   */
  private EMode m_eMode = EMode.PRINTING;

  /**
   * Current number of indentation strings to print
   */
  private int m_nIndentLevel;

  /**
   * String to be used for each indentation. Defaults to four spaces.
   */
  private final String m_sIndentSpace;

  /**
   * Stream associated with this JFormatter
   */
  private final PrintWriter m_aPW;

  private char m_cLastChar = 0;
  private boolean m_bAtBeginningOfLine = true;
  private JPackage m_aPckJavaLang;

  /**
   * Only used by {@link JFormatter#containsErrorTypes(JDefinedClass)
   * containsErrorTypes} method
   */
  private boolean m_bContainsErrorTypes;

  /**
   * Creates a JFormatter.
   *
   * @param aPW
   *        {@link PrintWriter} to {@link JFormatter} to use. May not be
   *        <code>null</code>.
   * @param sIndentSpace
   *        Incremental indentation string, similar to tab value. May not be
   *        <code>null</code>.
   */
  public JFormatter (@Nonnull final PrintWriter aPW, @Nonnull final String sIndentSpace)
  {
    JCValueEnforcer.notNull (aPW, "PrintWriter");
    JCValueEnforcer.notNull (sIndentSpace, "IndentSpace");

    m_aPW = aPW;
    m_sIndentSpace = sIndentSpace;
  }

  /**
   * Creates a formatter with default incremental indentations of four spaces.
   */
  public JFormatter (@Nonnull final PrintWriter aPW)
  {
    this (aPW, DEFAULT_INDENT_SPACE);
  }

  /**
   * Creates a formatter with default incremental indentations of four spaces.
   */
  public JFormatter (@Nonnull final Writer aWriter)
  {
    this (aWriter instanceof PrintWriter ? (PrintWriter) aWriter : new PrintWriter (aWriter));
  }

  /**
   * Closes this formatter.
   */
  public void close ()
  {
    m_aPW.close ();
  }

  /**
   * Returns true if we are in the printing mode, where we actually produce
   * text. The other mode is the "collecting mode'
   */
  public boolean isPrinting ()
  {
    return m_eMode == EMode.PRINTING;
  }

  /**
   * Decrement the indentation level.
   */
  @Nonnull
  public JFormatter outdent ()
  {
    m_nIndentLevel--;
    return this;
  }

  /**
   * Increment the indentation level.
   */
  @Nonnull
  public JFormatter indent ()
  {
    m_nIndentLevel++;
    return this;
  }

  private static boolean _needSpace (final char c1, final char c2)
  {
    if ((c1 == ']') && (c2 == '{'))
      return true;
    if (c1 == ';')
      return true;
    if (c1 == CLOSE_TYPE_ARGS)
    {
      // e.g., "public Foo<Bar> test;"
      if (c2 == '(')
      {
        // but not "new Foo<Bar>()"
        return false;
      }
      return true;
    }
    if ((c1 == ')') && (c2 == '{'))
      return true;
    if ((c1 == ',') || (c1 == '='))
      return true;
    if (c2 == '=')
      return true;
    if (Character.isDigit (c1))
    {
      if ((c2 == '(') || (c2 == ')') || (c2 == ';') || (c2 == ','))
        return false;
      return true;
    }
    if (Character.isJavaIdentifierPart (c1))
    {
      switch (c2)
      {
        case '{':
        case '}':
        case '+':
        case '-':
        case '>':
        case '@':
          return true;
        default:
          return Character.isJavaIdentifierStart (c2);
      }
    }
    if (Character.isJavaIdentifierStart (c2))
    {
      switch (c1)
      {
        case ']':
        case ')':
        case '}':
        case '+':
          return true;
        default:
          return false;
      }
    }
    if (Character.isDigit (c2))
    {
      if (c1 == '(')
        return false;
      return true;
    }
    return false;
  }

  private void _spaceIfNeeded (final char c)
  {
    if (m_bAtBeginningOfLine)
    {
      for (int i = 0; i < m_nIndentLevel; i++)
        m_aPW.print (m_sIndentSpace);
      m_bAtBeginningOfLine = false;
    }
    else
      if (m_cLastChar != 0 && _needSpace (m_cLastChar, c))
        m_aPW.print (' ');
  }

  /**
   * Print a char into the stream
   *
   * @param c
   *        the char
   * @return this
   */
  @Nonnull
  public JFormatter print (final char c)
  {
    if (m_eMode == EMode.PRINTING)
    {
      if (c == CLOSE_TYPE_ARGS)
      {
        m_aPW.print ('>');
      }
      else
      {
        _spaceIfNeeded (c);
        m_aPW.print (c);
      }
      m_cLastChar = c;
    }
    return this;
  }

  /**
   * Print a String into the stream
   *
   * @param s
   *        the String
   * @return this
   */
  @Nonnull
  public JFormatter print (@Nonnull final String s)
  {
    if (m_eMode == EMode.PRINTING && s.length () > 0)
    {
      _spaceIfNeeded (s.charAt (0));
      m_aPW.print (s);
      m_cLastChar = s.charAt (s.length () - 1);
    }
    return this;
  }

  @Nonnull
  public JFormatter type (@Nonnull final AbstractJType aType)
  {
    if (aType.isReference ())
      return type ((AbstractJClass) aType);
    return generable (aType);
  }

  /**
   * Print a type name.
   * <p>
   * In the collecting mode we use this information to decide what types to
   * import and what not to.
   */
  @Nonnull
  public JFormatter type (@Nonnull final AbstractJClass aType)
  {
    switch (m_eMode)
    {
      case FIND_ERROR_TYPES:
        if (aType.isError ())
          m_bContainsErrorTypes = true;
        break;
      case PRINTING:
        // many of the JTypes in this list are either primitive or belong to
        // package java so we don't need a FQCN
        if (m_aImportedClasses.contains (aType) || aType._package () == m_aPckJavaLang)
        {
          // FQCN imported or not necessary, so generate short name
          print (aType.name ());
        }
        else
        {
          final AbstractJClass aOuter = aType.outer ();
          if (aOuter != null)
            type (aOuter).print ('.').print (aType.name ());
          else
          {
            // collision was detected, so generate FQCN
            print (aType.fullName ());
          }
        }
        break;
      case COLLECTING:
        if (false)
        {
          final AbstractJClass aOuter = aType.outer ();
          if (aOuter != null)
          {
            // If an outer type is available, collect this one as well
            type (aOuter);
          }
        }

        final String sShortName = aType.name ();
        Usages aUsages = m_aCollectedReferences.get (sShortName);
        if (aUsages == null)
        {
          aUsages = new Usages (sShortName);
          m_aCollectedReferences.put (sShortName, aUsages);
        }
        aUsages.addReferencedType (aType);
        break;
    }
    return this;
  }

  /**
   * Print an identifier
   */
  @Nonnull
  public JFormatter id (@Nonnull final String id)
  {
    switch (m_eMode)
    {
      case PRINTING:
        print (id);
        break;
      case COLLECTING:
        // see if there is a type name that collides with this id
        Usages aUsages = m_aCollectedReferences.get (id);
        if (aUsages == null)
        {
          // not a type, but we need to create a place holder to
          // see if there might be a collision with a type
          aUsages = new Usages (id);
          m_aCollectedReferences.put (id, aUsages);
        }
        aUsages.setVariableName ();
        break;
    }
    return this;
  }

  /**
   * Print a new line into the stream
   */
  @Nonnull
  public JFormatter newline ()
  {
    if (m_eMode == EMode.PRINTING)
    {
      m_aPW.println ();
      m_cLastChar = 0;
      m_bAtBeginningOfLine = true;
    }
    return this;
  }

  /**
   * Cause the JGenerable object to generate source for iteself
   *
   * @param g
   *        the JGenerable object
   */
  @Nonnull
  public JFormatter generable (@Nonnull final IJGenerable g)
  {
    g.generate (this);
    return this;
  }

  /**
   * Produces {@link IJGenerable}s separated by ','
   */
  @Nonnull
  public JFormatter generable (@Nonnull final Collection <? extends IJGenerable> list)
  {
    boolean first = true;
    if (!list.isEmpty ())
    {
      for (final IJGenerable item : list)
      {
        if (!first)
          print (',');
        generable (item);
        first = false;
      }
    }
    return this;
  }

  /**
   * Cause the JDeclaration to generate source for itself
   *
   * @param d
   *        the JDeclaration object
   */
  @Nonnull
  public JFormatter declaration (@Nonnull final IJDeclaration d)
  {
    d.declare (this);
    return this;
  }

  /**
   * Cause the JStatement to generate source for itself
   *
   * @param s
   *        the JStatement object
   */
  @Nonnull
  public JFormatter statement (@Nonnull final IJStatement s)
  {
    s.state (this);
    return this;
  }

  /**
   * Cause the {@link JVar} to generate source for itself
   *
   * @param v
   *        the {@link JVar} object
   */
  @Nonnull
  public JFormatter var (@Nonnull final JVar v)
  {
    v.bind (this);
    return this;
  }

  /**
   * Generates the whole source code out of the specified class.
   */
  void write (@Nonnull final JDefinedClass aClassToBeWritten)
  {
    m_aPckJavaLang = aClassToBeWritten.owner ()._package ("java.lang");

    // first collect all the types and identifiers
    m_eMode = EMode.COLLECTING;
    m_aCollectedReferences.clear ();
    m_aImportedClasses.clear ();
    declaration (aClassToBeWritten);

    // collate type names and identifiers to determine which types can be
    // imported
    for (final Usages aUsages : m_aCollectedReferences.values ())
    {
      if (!aUsages.isAmbiguousIn (aClassToBeWritten) && !aUsages.isVariableName ())
      {
        final AbstractJClass aReferencedClass = aUsages.getSingleReferencedType ();

        if (_shouldBeImported (aReferencedClass, aClassToBeWritten))
        {
          m_aImportedClasses.add (aReferencedClass);
        }
        else
        {
          _importOuterClassIfCausesNoAmbiguities (aReferencedClass, aClassToBeWritten);
        }
      }
      else
      {
        if (aUsages.isTypeName ())
          for (final AbstractJClass reference : aUsages.getReferencedTypes ())
          {
            _importOuterClassIfCausesNoAmbiguities (reference, aClassToBeWritten);
          }
      }
    }

    // the class itself that we will be generating is always accessible
    m_aImportedClasses.add (aClassToBeWritten);

    // then print the declaration
    m_eMode = EMode.PRINTING;

    assert aClassToBeWritten.parentContainer ().isPackage () : "this method is only for a pacakge-level class";
    final JPackage aPackage = (JPackage) aClassToBeWritten.parentContainer ();
    if (!aPackage.isUnnamed ())
    {
      newline ().declaration (aPackage);
      newline ();
    }

    // generate import statements
    final AbstractJClass [] imports = m_aImportedClasses.toArray (new AbstractJClass [m_aImportedClasses.size ()]);
    Arrays.sort (imports, ClassNameComparator.getInstance ());
    boolean bAnyImport = false;
    for (AbstractJClass aImportClass : imports)
    {
      // suppress import statements for primitive types, built-in types,
      // types in the root package, and types in
      // the same package as the current type
      if (!_isImplicitlyImported (aImportClass, aClassToBeWritten))
      {
        if (aImportClass instanceof JNarrowedClass)
        {
          // Never imported narrowed class but the erasure only
          aImportClass = aImportClass.erasure ();
        }

        print ("import").print (aImportClass.fullName ()).print (';').newline ();
        bAnyImport = true;
      }
    }

    if (bAnyImport)
      newline ();

    declaration (aClassToBeWritten);
  }

  /**
   * determine if an import statement should be used for given class. This is a
   * matter of style and convention
   *
   * @param aReference
   *        {@link AbstractJClass} referenced class
   * @param aGeneratedClass
   *        {@link AbstractJClass} currently generated class
   * @return true if an import statement can be used to shorten references to
   *         referenced class
   */
  private boolean _shouldBeImported (@Nonnull final AbstractJClass aReference,
                                     @Nonnull final JDefinedClass aGeneratedClass)
  {
    AbstractJClass aRealReference = aReference;
    if (aRealReference instanceof JAnonymousClass)
    {
      // get the super class of the anonymous class
      aRealReference = ((JAnonymousClass) aRealReference)._extends ();
    }
    if (aRealReference instanceof JNarrowedClass)
    {
      // Remove the generic arguments
      aRealReference = aRealReference.erasure ();
    }

    // Is it an inner class?
    final AbstractJClass aOuter = aRealReference.outer ();
    if (aOuter != null)
    {
      // Import inner class only when it's name contain a name of enclosing
      // class.
      // In such case no information is lost when we refer to inner class
      // without mentioning
      // it's enclosing class
      if (aRealReference.name ().contains (aOuter.name ()) && _shouldBeImported (aOuter, aGeneratedClass))
        return true;

      // Do not import inner classes in all other cases to aid
      // understandability/readability.
      return false;
    }

    return true;
  }

  /**
   * determine if an import statement should be suppressed
   *
   * @param aReference
   *        {@link AbstractJClass} that may or may not have an import
   * @param aGeneratingClass
   *        {@link AbstractJClass} that is the current class being processed
   * @return true if an import statement should be suppressed, false otherwise
   */
  private boolean _isImplicitlyImported (@Nonnull final AbstractJClass aReference, @Nonnull final AbstractJClass clazz)
  {
    AbstractJClass aRealReference = aReference;
    if (aRealReference instanceof JAnonymousClass)
    {
      // Get the super class of the anonymous class
      aRealReference = ((JAnonymousClass) aRealReference)._extends ();
    }
    if (aRealReference instanceof JNarrowedClass)
    {
      // Remove generic type arguments
      aRealReference = aRealReference.erasure ();
    }

    final JPackage aPackage = aRealReference._package ();
    if (aPackage == null)
    {
      // May be null for JTypeVar and JTypeWildcard
      return true;
    }

    if (aPackage.isUnnamed ())
    {
      // Root package - no need to import something
      return true;
    }

    if (aPackage == m_aPckJavaLang)
    {
      // no need to explicitly import java.lang classes
      return true;
    }

    // All pkg local classes do not need an
    // import stmt for ref, except for inner classes
    if (aPackage == clazz._package ())
    {
      AbstractJClass aOuter = aRealReference.outer ();
      if (aOuter == null) // top-level class
      {
        // top-level package-local class needs no explicit import
        return true;
      }

      // inner-class
      AbstractJClass aTopLevelClass = aOuter;
      aOuter = aTopLevelClass.outer ();
      while (aOuter != null)
      {
        aTopLevelClass = aOuter;
        aOuter = aTopLevelClass.outer ();
      }

      // if reference is inner-class and
      // reference's top-level class is generated clazz,
      // i. e. reference is enclosed in generated clazz,
      // then it needs no explicit import statement.
      return aTopLevelClass == clazz;
    }
    return false;
  }

  /**
   * If reference is inner-class adds some outer class to the list of imported
   * classes if it
   *
   * @param clazz
   *        {@link AbstractJClass} that may or may not have an import
   * @param aGeneratingClass
   *        {@link AbstractJClass} that is the current class being processed
   * @return true if an import statement should be suppressed, false otherwise
   */
  private void _importOuterClassIfCausesNoAmbiguities (@Nonnull final AbstractJClass reference,
                                                       @Nonnull final JDefinedClass clazz)
  {
    final AbstractJClass aOuter = reference.outer ();
    if (aOuter != null)
    {
      if (_causesNoAmbiguities (aOuter, clazz) && _shouldBeImported (aOuter, clazz))
        m_aImportedClasses.add (aOuter);
      else
        _importOuterClassIfCausesNoAmbiguities (aOuter, clazz);
    }
  }

  private boolean _causesNoAmbiguities (@Nonnull final AbstractJClass reference, @Nonnull final JDefinedClass clazz)
  {
    final Usages aUsages = m_aCollectedReferences.get (reference.name ());
    if (aUsages == null)
      return true;
    return !aUsages.isAmbiguousIn (clazz) && aUsages.containsReferencedType (reference);
  }

  public static boolean containsErrorTypes (@Nonnull final JDefinedClass c)
  {
    final JFormatter aFormatter = new JFormatter (NullWriter.getInstance ());
    aFormatter.m_eMode = EMode.FIND_ERROR_TYPES;
    aFormatter.m_bContainsErrorTypes = false;
    aFormatter.declaration (c);
    return aFormatter.m_bContainsErrorTypes;
  }
}
