/*     ___ ____ ___   __   ___   ___
**    / _// __// _ | / /  / _ | / _ \  Scala classfile decoder
**  __\ \/ /__/ __ |/ /__/ __ |/ ___/  (c) 2003-2010, LAMP/EPFL
** /____/\___/_/ |_/____/_/ |_/_/      http://scala-lang.org/
**
*/


package org.jetbrains.plugins.scala.decompiler.scalasig

import java.lang.StringBuilder
import java.util.regex.Pattern

import org.apache.commons.lang.StringEscapeUtils

import scala.annotation.{switch, tailrec}
import scala.collection.mutable
import scala.reflect.NameTransformer
import scala.tools.scalap.scalax.util.StringUtil

sealed abstract class Verbosity
case object ShowAll extends Verbosity
case object HideClassPrivate extends Verbosity
case object HideInstancePrivate extends Verbosity

//This class is from scalap, refactored to work with new types
class ScalaSigPrinter(builder: StringBuilder, verbosity: Verbosity) {
  import ScalaSigPrinter._

  def this(builder: StringBuilder, printPrivates: Boolean) = this(builder: StringBuilder, if (printPrivates) ShowAll else HideClassPrivate)

  def print(s: String): Unit = builder.append(s)

  def result: String = builder.toString

  private val currentTypeParameters: mutable.HashMap[Symbol, String] = new mutable.HashMap[Symbol, String]()

  private def addTypeParameter(t: Symbol) {
    def checkName(name: String): Boolean = {
      currentTypeParameters.forall {
        case (_: Symbol, symbolName: String) => name != symbolName
      }
    }
    if (checkName(t.name)) {
      currentTypeParameters += ((t, t.name))
    } else {
      @tailrec
      def writeWithIndex(index: Int) {
        val nameWithIndex: String = s"${t.name}_$$_$index"
        if (checkName(nameWithIndex)) {
          currentTypeParameters += ((t, nameWithIndex))
        } else writeWithIndex(index + 1)
      }
      writeWithIndex(1)
    }
  }

  private def removeTypeParameter(t: Symbol) {
    currentTypeParameters.remove(t)
  }

  val CONSTRUCTOR_NAME = "<init>"

  val INIT_NAME = "$init$"

  case class TypeFlags(printRep: Boolean)
  implicit object _tf extends TypeFlags(false)

  def printSymbol(symbol: Symbol) {printSymbol(0, symbol)}

  def printSymbolAttributes(s: Symbol, onNewLine: Boolean, indent: => Unit): Unit = s match {
    case t: SymbolInfoSymbol =>
      for (a <- t.attributes) {
        indent; print(toString(a))
        if (onNewLine) print("\n") else print(" ")
      }
    case _ =>
  }

  def printSymbol(level: Int, symbol: Symbol) {
    if (symbol.isSynthetic) return
    val shouldPrint = {
      val accessibilityOk = verbosity match {
        case ShowAll => true
        case HideClassPrivate => !symbol.isPrivate || symbol.isInstanceOf[AliasSymbol] || level == 0
        case HideInstancePrivate => !symbol.isLocal || symbol.isInstanceOf[AliasSymbol]
      }
      val paramAccessor = symbol match {
        case m: MethodSymbol if m.isParamAccessor => true
        case _ => false
      }
      accessibilityOk && !symbol.isCaseAccessor && !paramAccessor
    }
    if (shouldPrint) {
      def indent() {for (_ <- 1 to level) print("  ")}

      printSymbolAttributes(symbol, onNewLine = true, indent())
      symbol match {
        case o: ObjectSymbol =>
          indent()
          if (o.name == "package" || o.name == "`package`") {
            // print package object
            printPackageObject(level, o)
          } else {
            printObject(level, o)
          }
        case c: ClassSymbol if !refinementClass(c) && !c.isModule =>
          indent()
          printClass(level, c)
        case m: MethodSymbol =>
          printMethod(level, m, indent _)
        case a: AliasSymbol =>
          indent()
          printAlias(level, a)
        case t: TypeSymbol if !t.isParam && !t.name.matches("_\\$\\d+") &&
          !t.name.matches("\\?(\\d)+") =>
          // todo: type 0? found in Suite class from scalatest package. So this is quickfix,
          // todo: we need to find why such strange type is here
          indent()
          printTypeSymbol(level, t)
        case _ =>
      }
    }
  }

  def isCaseClassObject(o: ObjectSymbol): Boolean = {
    val TypeRefType(_, Ref(classSymbol: ClassSymbol), _) = o.infoType
    o.isFinal && (classSymbol.children.find(x => x.isCase && x.isInstanceOf[MethodSymbol]) match {
      case Some(_) => true
      case None => false
    })
  }

  private def underObject(m: MethodSymbol) = m.parent match {
    case Some(c: ClassSymbol) => c.isModule
    case _ => false
  }

  private def underTrait(m: MethodSymbol) = m.parent match {
    case Some(c: ClassSymbol) => c.isTrait
    case _ => false
  }


  private def printChildren(level: Int, symbol: Symbol, filterFirstCons: Boolean = false) {
    var firstConsFiltered = !filterFirstCons
    for {
      child <- symbol.children
      if !(child.isParam && child.isType)
    } {
      if (!firstConsFiltered)
        child match {
          case m: MethodSymbol if m.name == CONSTRUCTOR_NAME => firstConsFiltered = true
          case _ => printSymbol(level + 1, child)
        }
      else printSymbol(level + 1, child)
    }
  }

  def printWithIndent(level: Int, s: String) {
    def indent() {for (i <- 1 to level) print("  ")}
    indent()
    print(s)
  }

  def printModifiers(symbol: Symbol) {
    lazy val privateWithin: Option[String] = {
      symbol match {
        case sym: SymbolInfoSymbol => sym.symbolInfo.privateWithin match {
          case Some(t: Ref[Symbol]) => Some("[" + processName(t.get.name) + "]")
          case _ => None
        }
        case _ => None
      }
    }

    symbol.parent match {
      case Some(cSymbol: ClassSymbol) if refinementClass(cSymbol) => return //no modifiers allowed inside refinements
      case _ =>
    }

    // print private access modifier
    if (symbol.isPrivate) {
      print("private")
      if (symbol.isLocal) print("[this] ")
      else print(" ")
    }
    else if (symbol.isProtected) {
      print("protected")
      if (symbol.isLocal) print("[this]")
      else privateWithin foreach print
      print(" ")
    }
    else privateWithin.foreach(s => print("private" + s + " "))

    if (symbol.isSealed) print("sealed ")
    if (symbol.isImplicit) print("implicit ")
    if (symbol.isFinal && !symbol.isInstanceOf[ObjectSymbol]) print("final ")
    if (symbol.isOverride) print("override ")
    if (symbol.isAbstract) symbol match {
      case c@(_: ClassSymbol | _: ObjectSymbol) if !c.isTrait => print("abstract ")
      case _ => ()
    }
    if (symbol.isCase && !symbol.isMethod) print("case ")
  }

  private def refinementClass(c: ClassSymbol) = c.name == "<refinement>"

  def printClass(level: Int, c: ClassSymbol) {
    if (c.name == "<local child>" /*scala.tools.nsc.symtab.StdNames.LOCALCHILD.toString()*/ ) {
      print("\n")
    } else if (c.name == "<refinement>") { //todo: make it better to avoin '\n' char
      print(" {\n")
      printChildren(level, c)
      printWithIndent(level, "}")
    } else {
      printModifiers(c)
      val defaultConstructor = if (!c.isTrait) getPrinterByConstructor(c) else ""
      if (c.isTrait) print("trait ") else print("class ")
      print(processName(c.name))
      val it = c.infoType
      val (classType, typeParams) = it match {
        case PolyType(typeRef, symbols) => (PolyTypeWithCons(typeRef, symbols, defaultConstructor), symbols)
        case ClassInfoType(a, b) if !c.isTrait => (ClassInfoTypeWithCons(a, b, defaultConstructor), Seq.empty)
        case _ => (it, Seq.empty)
      }
      for (param <- typeParams) addTypeParameter(param.get)
      printType(classType)
      try {
        print(" {")
        //Print class selftype
        c.thisTypeRef match {
          case Some(t) => print("\n"); print(" this : " + toString(t.get) + " =>")
          case None =>
        }
        print("\n")
        printChildren(level, c, !c.isTrait)
        printWithIndent(level, "}\n")
      }
      finally {
        for (param <- typeParams) removeTypeParameter(param.get)
      }
    }
  }

  def getClassString(level: Int, c: ClassSymbol): String = {
    val printer = new ScalaSigPrinter(new StringBuilder(), verbosity)
    printer.printClass(level, c)
    printer.result
  }

  def getPrinterByConstructor(c: ClassSymbol): String = {
    c.children.find {
      case m: MethodSymbol if m.name == CONSTRUCTOR_NAME => true
      case _ => false
    } match {
      case Some(m: MethodSymbol) =>
        val printer = new ScalaSigPrinter(new StringBuilder(), verbosity)
        printer.printPrimaryConstructor(m, c)
        val res = printer.result
        if (res.length() > 0 && res.charAt(0) != '(') " " + res
        else res
      case _ => ""
    }
  }

  def printPrimaryConstructor(m: MethodSymbol, c: ClassSymbol) {
    printModifiers(m)
    printMethodType(m.infoType, printResult = false, methodSymbolAsClassParam(_, c))(())
  }

  def printPackageObject(level: Int, o: ObjectSymbol) {
    printModifiers(o)
    print("package ")
    print("object ")
    val poName = o.symbolInfo.owner.get.name
    print(processName(poName))
    val TypeRefType(_, Ref(classSymbol: ClassSymbol), _) = o.infoType
    printType(classSymbol)
    print(" {\n")
    printChildren(level, classSymbol)
    printWithIndent(level, "}\n")

  }

  def printObject(level: Int, o: ObjectSymbol) {
    printModifiers(o)
    print("object ")
    print(processName(o.name))
    val TypeRefType(_, Ref(classSymbol: ClassSymbol), _) = o.infoType
    printType(classSymbol)
    print(" {\n")
    printChildren(level, classSymbol)
    printWithIndent(level, "}\n")
  }

  private def methodSymbolAsMethodParam(ms: MethodSymbol): String = {
    val nameAndType = processName(ms.name) + " : " + toString(ms.infoType)(TypeFlags(true))
    val default = if (ms.hasDefault) " = { /* compiled code */ }" else ""
    nameAndType + default
  }

  private def methodSymbolAsClassParam(msymb: MethodSymbol, c: ClassSymbol): String = {
    val printer = new ScalaSigPrinter(new StringBuilder(), verbosity)
    val paramAccessors = c.children.filter {
      case ms: MethodSymbol if ms.isParamAccessor && ms.name.startsWith(msymb.name) => true
      case _ => false
    }
    val isMutable = paramAccessors.exists(_.name == setterName(msymb))
    val toPrint = paramAccessors.find(m => !m.isPrivate || !m.isLocal)
    toPrint match {
      case Some(ms) =>
        printer.printSymbolAttributes(ms, onNewLine = false, ())
        printer.printModifiers(ms)
        if (isMutable) printer.print("var ")
        else printer.print("val ")
      case _ =>
    }

    val nameAndType = processName(msymb.name) + " : " + toString(msymb.infoType)(TypeFlags(true))
    val default = if (msymb.hasDefault) " = { /* compiled code */ }" else ""
    printer.print(nameAndType + default)
    printer.result
  }

  def printMethodType(t: Type, printResult: Boolean,
                      pe: MethodSymbol => String = methodSymbolAsMethodParam)(cont: => Unit) {

    def _pmt(mt: FunctionType) {

      val paramEntries = mt.paramSymbols.map({
        case ms: MethodSymbol => pe(ms)
        case _ => "^___^"
      })

      // Print parameter clauses
      print(paramEntries.mkString(
        "(" + (mt match {
          case _: ImplicitMethodType => "implicit "
          //for Scala 2.9
          case mt: MethodType if mt.paramSymbols.nonEmpty && mt.paramSymbols.head.isImplicit => "implicit "
          case _ => ""
        }), ", ", ")"))

      // Print result type
      mt.resultType.get match {
        case mt: MethodType => printMethodType(mt, printResult, pe)({})
        case imt: ImplicitMethodType => printMethodType(imt, printResult, pe)({})
        case x => if (printResult) {
          print(" : ")
          printType(x)
        }
      }
    }

    t match {
      case mt@MethodType(_, _) => _pmt(mt)
      case mt@ImplicitMethodType(_, _) => _pmt(mt)
      case pt: PolyType =>
        val typeParams = pt.paramSymbols
        for (param <- typeParams) addTypeParameter(param)
        print(typeParamString(typeParams))
        try {
          printMethodType(pt.typeRef.get, printResult)({})
        }
        finally {
          for (param <- typeParams) removeTypeParameter(param)
        }
      //todo consider another method types
      case x => print(" : "); printType(x)
    }

    // Print rest of the symbol output
    cont
  }

  def printMethod(level: Int, m: MethodSymbol, indent: () => Unit) {
    val n = m.name
    if (underObject(m) && n == CONSTRUCTOR_NAME) return
    if (underTrait(m) && n == INIT_NAME) return
    if (n.matches(".+\\$default\\$\\d+")) return // skip default function parameters
    if (n.startsWith("super$")) return // do not print auxiliary qualified super accessors
    if (m.isAccessor && n.endsWith("_$eq")) return
    if (m.isParamAccessor) return //do not print class parameters
    indent()
    printModifiers(m)
    if (m.isAccessor) {
      val indexOfSetter = m.parent.get.children.indexWhere {
        case ms: MethodSymbol => ms.name == setterName(m)
        case _ => false
      }
      val keywords = if (indexOfSetter > 0) "var " else if (m.isLazy) "lazy val " else "val "
      print(keywords)
    } else {
      print("def ")
    }
    n match {
      case CONSTRUCTOR_NAME =>
        print("this")
        printMethodType(m.infoType, printResult = false) {
          print(" = { /* compiled code */ }")
        }
      case name =>
        val nn = processName(name)
        print(nn)
        val printBody = !m.isDeferred && (m.parent match {
          case Some(c: ClassSymbol) if refinementClass(c) => false
          case _ => true
        })
        printMethodType(m.infoType, printResult = true)(
          {if (printBody) print(" = { /* compiled code */ }" /* Print body only for non-abstract methods */ )}
          )
    }
    print("\n")
  }

  def printAlias(level: Int, a: AliasSymbol) {
    printModifiers(a)
    print("type ")
    print(processName(a.name))
    val tp: Unit = a.infoType match {
      case PolyType(typeRef, symbols) => printType(PolyTypeWithCons(typeRef, symbols, " = "))
      case t => printType(t, " = ")
    }
    print("\n")
    printChildren(level, a)
  }

  def printTypeSymbol(level: Int, t: TypeSymbol) {
    print("type ")
    print(processName(t.name))
    t.infoType match {
      case PolyType(typeRef, symbols) => printType(PolyTypeWithCons(typeRef, symbols, ""))
      case _ => printType(t.infoType)
    }
    print("\n")
  }

  def toString(attrib: SymAnnot): String = {
    val prefix = toString(attrib.typeRef, "@")
    if (attrib.hasArgs) {
      val argTexts = attrib.args.map(annotArgText)
      val namedArgsText = attrib.namedArgs.map {
        case (name, value) => s"${processName(name)} = ${annotArgText(value)}"
      }
      (argTexts ++ namedArgsText).mkString(s"$prefix(", ", ", ")")
    }
    else prefix
  }

  // TODO char, float, etc.
  def annotArgText(arg: Any): String = {
    def quote(s: String) = if (s.contains("\n") || s.contains("\r")) {
      "\"\"\"" + s + "\"\"\""
    } else "\"" +  StringEscapeUtils.escapeJava(s) + "\""

    arg match {
      case s: String => quote(s)
      case Name(s: String) => quote(s)
      case Constant(v) => annotArgText(v)
      case Ref(v) => annotArgText(v)
      case AnnotArgArray(args) =>
        args.map(ref => annotArgText(ref.get)).mkString("Array(", ", ", ")")
      case t: Type => "classOf[%s]" format toString(t)
      case _ => arg.toString
    }
  }

  def printType(sym: SymbolInfoSymbol)(implicit flags: TypeFlags): Unit = printType(sym.infoType)(flags)

  def printType(t: Type)(implicit flags: TypeFlags): Unit = print(toString(t)(flags))

  def printType(t: Type, sep: String)(implicit flags: TypeFlags): Unit = print(toString(t, sep)(flags))

  def toString(t: Type)(implicit flags: TypeFlags): String = toString(t, "")(flags)

  def toString(t: Type, level: Int)(implicit flags: TypeFlags): String = toString(t, "", level)(flags)

  private val SingletonTypePattern = """(.*?)\.type""".r

  //TODO: this passing of 'level' look awful;
  def toString(t: Type, sep: String, level: Int = 0)(implicit flags: TypeFlags): String = {

    // print type itself
    t match {
      case ThisType(Ref(classSymbol: ClassSymbol)) if refinementClass(classSymbol) => sep + "this.type"
      case ThisType(Ref(symbol)) => sep + processName(symbol.name) + ".this.type"
      case SingleType(Ref(ThisType(Ref(thisSymbol: ClassSymbol))), symbol) =>
        val thisSymbolName: String =
          thisSymbol.name match {
            case "package" => thisSymbol.symbolInfo.owner match {
              case Ref(ex: ExternalSymbol) => processName(ex.name)
              case _ => "this"
            }
            case name if thisSymbol.isModule => processName(name)
            case name => processName(name) + ".this"
          }
        sep + thisSymbolName + "." + processName(symbol.name) + ".type"
      case SingleType(Ref(ThisType(Ref(exSymbol: ExternalSymbol))), symbol) if exSymbol.name == "<root>" =>
        sep + processName(symbol.name) + ".type"
      case SingleType(Ref(ThisType(Ref(exSymbol: ExternalSymbol))), Ref(symbol)) =>
        sep + StringUtil.cutSubstring(StringUtil.trimStart(processName(exSymbol.path), "<empty>."))(".`package`") + "." +
          processName(symbol.name) + ".type"
      case SingleType(Ref(NoPrefixType), Ref(symbol)) =>
        sep + processName(symbol.name) + ".type"
      case SingleType(typeRef, symbol) =>
        var typeRefString = toString(typeRef, level)
        if (typeRefString.endsWith(".type")) typeRefString = typeRefString.dropRight(5)
        typeRefString = StringUtil.cutSubstring(typeRefString)(".`package`")
        sep + typeRefString + "." + processName(symbol.name) + ".type"
      case ConstantType(Ref(Constant(constant))) => sep + (constant match {
        case null => "scala.Null"
        case _: Unit => "scala.Unit"
        case _: Boolean => "scala.Boolean"
        case _: Byte => "scala.Byte"
        case _: Char => "scala.Char"
        case _: Short => "scala.Short"
        case _: Int => "scala.Int"
        case _: Long => "scala.Long"
        case _: Float => "scala.Float"
        case _: Double => "scala.Double"
        case _: String => "java.lang.String"
        case Ref(Name(_)) => "java.lang.String"
        case c: Class[_] => "java.lang.Class[" + c.getComponentType.getCanonicalName.replace("$", ".") + "]"
        case Ref(ExternalSymbol(_, Some(Ref(parent)), _)) => parent.path //enum value
      })
      case TypeRefType(Ref(NoPrefixType), Ref(symbol: TypeSymbol), typeArgs) if currentTypeParameters.isDefinedAt(symbol) =>
        sep + processName(currentTypeParameters.getOrElse(symbol, symbol.name)) + typeArgString(typeArgs, level)
      case TypeRefType(prefix, symbol, typeArgs) => sep + (symbol.path match {
        case "scala.<repeated>" => flags match {
          case TypeFlags(true) => toString(typeArgs.head, level) + "*"
          case _ => "scala.Seq" + typeArgString(typeArgs, level)
        }
        case "scala.<byname>" => "=> " + toString(typeArgs.head, level)
        case _ =>
          def checkContainsSelf(self: Option[Type], parent: Symbol): Boolean = {
            self match {
              case Some(tp) =>
                tp match {
                  case ThisType(Ref(sym)) => sym == parent
                  case SingleType(_, Ref(sym)) => sym == parent
                  case _: ConstantType => false
                  case TypeRefType(_, Ref(sym), _) => sym == parent
                  case _: TypeBoundsType => false
                  case RefinedType(Ref(sym), refs) => sym == parent || !refs.forall(tp => !checkContainsSelf(Some(tp), parent))
                  case ClassInfoType(Ref(sym), refs) => sym == parent || !refs.forall(tp => !checkContainsSelf(Some(tp), parent))
                  case ClassInfoTypeWithCons(Ref(sym), refs, _) => sym == parent || !refs.forall(tp => !checkContainsSelf(Some(tp), parent))
                  case ImplicitMethodType(_, _) => false
                  case MethodType(_, _) => false
                  case NullaryMethodType(_) => false
                  case PolyType(typeRef, symbols) =>
                    checkContainsSelf(Some(typeRef), parent) || symbols.contains(parent)
                  case PolyTypeWithCons(typeRef, symbols, _) =>
                    checkContainsSelf(Some(typeRef), parent) || symbols.contains(parent)
                  case AnnotatedType(typeRef) => checkContainsSelf(Some(typeRef), parent)
                  case AnnotatedWithSelfType(typeRef, Ref(sym), _) =>
                    checkContainsSelf(Some(typeRef), parent) || sym == parent
                  case ExistentialType(typeRef, symbols) =>
                    checkContainsSelf(Some(typeRef), parent) || symbols.contains(parent)
                  case _ => false
                }
              case None => false
            }
          }
          val prefixStr = (prefix.get, symbol.get, toString(prefix.get, level)) match {
            case (NoPrefixType, _, _) => ""
            case (ThisType(Ref(objectSymbol)), _, _) if objectSymbol.isModule && !objectSymbol.isStable =>
              val name: String = objectSymbol.name
              objectSymbol match {
                case classSymbol: ClassSymbol if name == "package" =>
                  processName(classSymbol.symbolInfo.owner.path) + "."
                case _ => processName(name) + "."
              }
            case (ThisType(packSymbol), _, _) if !packSymbol.isType =>
              processName(packSymbol.path.replace("<root>", "_root_")) + "."
            case (ThisType(Ref(classSymbol: ClassSymbol)), _, _) if refinementClass(classSymbol) => ""
            case (ThisType(Ref(typeSymbol: ClassSymbol)), ExternalSymbol(_, Some(parent), _), _)
              if typeSymbol.path != parent.path && checkContainsSelf(typeSymbol.thisTypeRef, parent) =>
              processName(typeSymbol.name) + ".this."
            case (ThisType(typeSymbol), ExternalSymbol(_, Some(parent), _), _) if typeSymbol.path != parent.path =>
              processName(typeSymbol.name) + ".super[" + processName(parent.name) + "/*"+ parent.path +"*/]."
            case (_, _, SingletonTypePattern(a)) => a + "."
            case (_, _, a) => a + "#"
          }
          //remove package object reference
          val path = StringUtil.cutSubstring(prefixStr)(".`package`")
          val name = processName(symbol.name)
          val res = path + name
          val typeBounds = if (name == "_") {
            symbol.get match {
              case ts: TypeSymbol =>
                ts.infoType match {
                  case t: TypeBoundsType => toString(t, level)
                  case _ => ""
                }
              case _ => ""
            }
          } else ""
          val ress = StringUtil.trimStart(res, "<empty>.") + typeArgString(typeArgs, level) + typeBounds
          ress
      })
      case TypeBoundsType(lower, upper) =>
        val lb = toString(lower, level)
        val ub = toString(upper, level)
        val lbs = if (!lb.equals("scala.Nothing")) " >: " + lb else ""
        val ubs = if (!ub.equals("scala.Any")) " <: " + ub else ""
        lbs + ubs
      case RefinedType(Ref(classSym: ClassSymbol), typeRefs) =>
        val classStr = {
          val text = getClassString(level + 1, classSym)
          if (text.trim.stripPrefix("{").stripSuffix("}").trim.isEmpty) ""
          else text
        }
        sep + typeRefs.map(toString(_, level)).mkString("", " with ", "") + classStr
      case RefinedType(_, typeRefs) => sep + typeRefs.map(toString(_, level)).mkString("", " with ", "")
      case ClassInfoType(_, typeRefs) => sep + typeRefs.map(toString(_, level)).mkString(" extends ", " with ", "")
      case ClassInfoTypeWithCons(_, typeRefs, cons) => sep + typeRefs.map(toString(_, level)).
              mkString(cons + " extends ", " with ", "")

      case ImplicitMethodType(resultType, _) => toString(resultType, sep, level)
      case MethodType(resultType, _) => toString(resultType, sep, level)
      case NullaryMethodType(resultType) => toString(resultType, sep, level)

      case PolyType(typeRef, symbols) =>
        "({ type λ" + typeParamString(symbols) + " = " + toString(typeRef, sep, level) + " })#λ"
      case PolyTypeWithCons(typeRef, symbols, cons) =>
        typeParamString(symbols) + cons + toString(typeRef, sep, level)
      case AnnotatedType(typeRef) =>
        toString(typeRef, sep, level)
      case AnnotatedWithSelfType(typeRef, _, _) => toString(typeRef, sep, level)
      //case DeBruijnIndexType(typeLevel, typeIndex) =>
      case ExistentialType(typeRef, symbols) =>
        val refs = symbols.map(_.get).map(toString).filter(!_.startsWith("_")).map("type " + _)
        toString(typeRef, sep, level) + (if (refs.nonEmpty) refs.mkString(" forSome {", "; ", "}") else "")
      case _ => sep + t.toString
    }
  }

  def getVariance(t: TypeSymbol): String = if (t.isCovariant) "+" else if (t.isContravariant) "-" else ""

  def toString(symbol: Symbol): String = symbol match {
    case symbol: TypeSymbol =>
      val attrs = (for (a <- symbol.attributes) yield toString(a)).mkString(" ")
      val atrs = if (attrs.length > 0) attrs.trim + " " else ""
      val symbolType = symbol.infoType match {
        case PolyType(typeRef, symbols) => PolyTypeWithCons(typeRef, symbols, "")
        case tp => tp
      }
      val name: String = currentTypeParameters.getOrElse(symbol, symbol.name)
      atrs + getVariance(symbol) + processName(name) + toString(symbolType)
    case _ => symbol.toString
  }

  def typeArgString(typeArgs: Seq[Type], level: Int): String =
    if (typeArgs.isEmpty) ""
    else typeArgs.map(toString(_, level)).map(StringUtil.trimStart(_, "=> ")).mkString("[", ", ", "]")

  def typeParamString(params: Seq[Symbol]): String =
    if (params.isEmpty) ""
    else params.map(toString).mkString("[", ", ", "]")
}

object ScalaSigPrinter {
  val keywordList =
    Set("true", "false", "null", "abstract", "case", "catch", "class", "def",
      "do", "else", "extends", "final", "finally", "for", "forSome", "if",
      "implicit", "import", "lazy", "match", "new", "object", "override",
      "package", "private", "protected", "return", "sealed", "super",
      "this", "throw", "trait", "try", "type", "val", "var", "while", "with",
      "yield")

  def processName(name: String): String = {
    def processNameWithoutDot(name: String): String = {
      def isIdentifier(id: String): Boolean = {
        //following four methods is the same like in scala.tools.nsc.util.Chars class
        /** Can character start an alphanumeric Scala identifier? */
        def isIdentifierStart(c: Char): Boolean =
          (c == '_') || (c == '$') || Character.isUnicodeIdentifierStart(c)

        /** Can character form part of an alphanumeric Scala identifier? */
        def isIdentifierPart(c: Char) =
          (c == '$') || Character.isUnicodeIdentifierPart(c)

        /** Is character a math or other symbol in Unicode?  */
        def isSpecial(c: Char) = {
          val chtp = Character.getType(c)
          chtp == Character.MATH_SYMBOL.toInt || chtp == Character.OTHER_SYMBOL.toInt
        }

        /** Can character form part of a Scala operator name? */
        def isOperatorPart(c : Char) : Boolean = (c: @switch) match {
          case '~' | '!' | '@' | '#' | '%' |
               '^' | '*' | '+' | '-' | '<' |
               '>' | '?' | ':' | '=' | '&' |
               '|' | '/' | '\\' => true
          case _ => isSpecial(c)
        }

        def hasCommentStart(s: String) = s.contains("//") || s.contains("/*")

        if (id.isEmpty || hasCommentStart(id)) return false

        if (isIdentifierStart(id(0))) {
          if (id.indexWhere(c => !isIdentifierPart(c) && !isOperatorPart(c) && c != '_') >= 0) return false
          val index = id.indexWhere(isOperatorPart)
          if (index < 0) return true
          if (id(index - 1) != '_') return false
          id.drop(index).forall(isOperatorPart)
        } else if (isOperatorPart(id(0))) {
          id.forall(isOperatorPart)
        } else false
      }
      val result = NameTransformer.decode(name)
      if (!isIdentifier(result) || keywordList.contains(result) || result == "=") "`" + result + "`" else result
    }

    val stripped = stripPrivatePrefix(name)
    val m = pattern.matcher(stripped)
    var temp = stripped
    while (m.find) {
      val key = m.group
      val re = "\\" + key
      temp = temp.replaceAll(re, _syms(re))
    }

    val placeholderPattern = "_\\$(\\d)+"
    var result = temp.replaceAll(placeholderPattern, "_")

    //to avoid names like this one: ?0 (from existential type parameters)
    if (result.length() > 1 && result(0) == '?' && result(1).isDigit) result = "x" + result.substring(1)
    result.split('.').map(s => processNameWithoutDot(s)).mkString(".")
  }

  private def stripPrivatePrefix(name: String): String = {
    val i = name.lastIndexOf("$$")
    if (i > 0) name.substring(i + 2) else name
  }

  private def setterName(m: MethodSymbol) = m.name + "_$eq"

  val _syms = Map("\\$bar" -> "|", "\\$tilde" -> "~",
    "\\$bang" -> "!", "\\$up" -> "^", "\\$plus" -> "+",
    "\\$minus" -> "-", "\\$eq" -> "=", "\\$less" -> "<",
    "\\$times" -> "*", "\\$div" -> "/", "\\$bslash" -> "\\\\",
    "\\$greater" -> ">", "\\$qmark" -> "?", "\\$percent" -> "%",
    "\\$amp" -> "&", "\\$colon" -> ":", "\\$u2192" -> "→",
    "\\$hash" -> "#")

  val pattern: Pattern = Pattern.compile(_syms.keys.foldLeft("")((x, y) => if (x == "") y else x + "|" + y))
}