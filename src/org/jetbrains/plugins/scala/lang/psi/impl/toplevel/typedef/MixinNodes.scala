/**
* @author ven
*/
package org.jetbrains.plugins.scala
package lang
package psi
package impl
package toplevel
package typedef

import collection.mutable.{LinkedHashSet, HashMap, HashSet, Set, ListBuffer}
import psi.types._
import synthetic.ScSyntheticClass
import caches.CachesUtil
import api.toplevel.typedef.{ScTrait, ScObject, ScTypeDefinition, ScTemplateDefinition}
import com.intellij.psi.{PsiClassType, PsiElement, PsiClass}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.util.PsiModificationTracker

abstract class MixinNodes {
  type T
  def equiv(t1: T, t2: T) : Boolean
  def computeHashCode(t: T) : Int
  def isAbstract(t: T) : Boolean
  class Node (val info: T, val substitutor: ScSubstitutor) {
    var supers: Seq[Node] = Seq.empty
    var primarySuper: Option[Node] = None
  }
  
  class Map extends HashMap[T, Node] {
    override def elemHashCode(t : T) = computeHashCode(t)
    override def elemEquals(t1 : T, t2 : T) = equiv(t1, t2)

    /**
     * Use this method if you are sure, that map contains key
     */
    def smartGet(key: T): Option[Node] = {
      val h = index(elemHashCode(key))
      var e = table(h).asInstanceOf[Entry]
      if (e != null && e.next == null) return Some(e.value)
      while (e != null && !elemEquals(e.key, key)) {
        e = e.next
        if (e.next == null) return Some(e.value)
      }
      None
    }
  }

  class MultiMap extends HashMap[T, Set[Node]] with collection.mutable.MultiMap[T, Node] {
    override def elemHashCode(t : T) = computeHashCode(t)
    override def elemEquals(t1 : T, t2 : T) = equiv(t1, t2)

    override def makeSet = new LinkedHashSet[Node]   
  }

  object MultiMap {def empty = new MultiMap}

  def mergeSupers (maps : List[Map]) : MultiMap = {
    val res = MultiMap.empty
    val mapsIterator = maps.iterator
    while (mapsIterator.hasNext) {
      val currentIterator = mapsIterator.next.iterator
      while (currentIterator.hasNext) {
        val (k, node) = currentIterator.next
        res.addBinding(k, node)
      }
    }
    return res
  }

  //Return primary selected from supersMerged
  def mergeWithSupers(thisMap : Map, supersMerged : MultiMap) = {
    val primarySupers = new Map
    for ((key, nodes) <- supersMerged) {
      val primarySuper = nodes.find {n => !isAbstract(n.info)} match {
        case None => nodes.toList(0)
        case Some(concrete) => concrete
      }
      primarySupers += ((key, primarySuper))
      thisMap.get(key) match {
        case Some(node) => {
          node.primarySuper = Some(primarySuper)
          node.supers = nodes.toSeq
        }
        case None => {
          nodes -= primarySuper
          primarySuper.supers = nodes.toSeq
          thisMap += ((key, primarySuper))
        }
      }
    }
    primarySupers
  }

  def build(clazz: PsiClass): (Map, Map) = build(ScType.designator(clazz))

  def build(tp : ScType): (Map, Map) = {
    var isPredef = false
    var place: Option[PsiElement] = None
    val map = new Map
    val superTypesBuff = new ListBuffer[Map]
    //val superTypesBuff = new ListBuffer[(Map, ScSubstitutor)]
    val (superTypes, subst, thisTypeSubst): (Seq[ScType], ScSubstitutor, ScSubstitutor) = tp match {
      case ScDesignatorType(template: ScTypeDefinition) => {
        if (template.getQualifiedName == "scala.Predef") isPredef = true
        place = Some(template.extendsBlock)
        processScala(template, ScSubstitutor.empty, map, place)
        val lin = MixinNodes.linearization(template, collection.immutable.HashSet.empty)
        var zSubst = new ScSubstitutor(Predef.Map.empty, Predef.Map.empty, Some(ScThisType(template)))
        var placer = template.getContext
        while (placer != null) {
          placer match {
            case t: ScTemplateDefinition => zSubst = zSubst.followed(
              new ScSubstitutor(Predef.Map.empty, Predef.Map.empty, Some(ScThisType(t)))
            )
            case _ =>
          }
          placer = placer.getContext
        }
        (if (!lin.isEmpty) lin.tail else lin, Bounds.putAliases(template, ScSubstitutor.empty), zSubst)
      }
      case ScDesignatorType(template : ScTemplateDefinition) => {
        place = Some(template.getLastChild)
        processScala(template, ScSubstitutor.empty, map, place)
        var zSubst = new ScSubstitutor(Predef.Map.empty, Predef.Map.empty, Some(ScThisType(template)))
        var placer = template.getContext
        while (placer != null) {
          placer match {
            case t: ScTemplateDefinition => zSubst = zSubst.followed(
              new ScSubstitutor(Predef.Map.empty, Predef.Map.empty, Some(ScThisType(t)))
            )
            case _ =>
          }
          placer = placer.getContext
        }
        (MixinNodes.linearization(template, collection.immutable.HashSet.empty),
          Bounds.putAliases(template, ScSubstitutor.empty), zSubst)
      }
      case ScDesignatorType(syn: ScSyntheticClass) => {
        processSyntheticScala(syn, ScSubstitutor.empty, map, place)
        (syn.getSuperTypes.map{psiType => ScType.create(psiType, syn.getProject)} : Seq[ScType],
          ScSubstitutor.empty, ScSubstitutor.empty)
      }
      case ScDesignatorType(clazz: PsiClass) => {
        place = Some(clazz.getLastChild)
        processJava(clazz, ScSubstitutor.empty, map, place)
        val lin = MixinNodes.linearization(clazz, collection.immutable.HashSet.empty)
        (if (!lin.isEmpty) lin.tail else lin,
          ScSubstitutor.empty, ScSubstitutor.empty)
      }
      case cp: ScCompoundType => {
        processRefinement(cp, map, place)
        (MixinNodes.linearization(cp), ScSubstitutor.empty,
          new ScSubstitutor(Predef.Map.empty, Predef.Map.empty, Some(tp)))
      }
      case _ => (Seq.empty, ScSubstitutor.empty, ScSubstitutor.empty)
    }

    val iter = superTypes.iterator
    while (iter.hasNext) {
      val superType = iter.next()
      ScType.extractClassType(superType, place.map(_.getProject)) match {
        case Some((superClass, s)) =>
          // Do not include scala.ScalaObject to Predef's base types to prevent SOE
          if (!(superClass.getQualifiedName == "scala.ScalaObject" && isPredef)) {
            val newSubst = combine(s, subst, superClass).followed(thisTypeSubst)
            val newMap = new Map
            superClass match {
              case template : ScTemplateDefinition => {
                processScala(template, newSubst, newMap, place)
              }
              case syn: ScSyntheticClass => { //todo: is it really unnessesary?
                processSyntheticScala(syn, newSubst, newMap, place)
              }
              case _ => {
                processJava(superClass, newSubst, newMap, place)
              }
            }
            superTypesBuff += newMap
          }
        case _ =>
      }
    }
    val superMap = mergeWithSupers(map, mergeSupers(superTypesBuff.toList))

    (superMap, map)
  }

  def combine(superSubst : ScSubstitutor, derived : ScSubstitutor, superClass : PsiClass) = {
    var res : ScSubstitutor = ScSubstitutor.empty
    for (tp <- superClass.getTypeParameters) {
      val tv = ScalaPsiManager.typeVariable(tp)
      res = res bindT ((tp.getName, ScalaPsiUtil.getPsiElementId(tp)), derived.subst(superSubst.subst(ScalaPsiManager.typeVariable(tp))))
    }
    superClass match {
      case td : ScTypeDefinition => {
        var aliasesMap = res.aliasesMap
        for (alias <- td.aliases) {
          derived.aliasesMap.get(alias.name) match {
            case Some(t) => aliasesMap = aliasesMap + ((alias.name, t))
            case None =>
          }
        }
        res = new ScSubstitutor(res.tvMap, aliasesMap, None)
      }
      case _ => ()
    }
    res
  }

  def processJava(clazz: PsiClass, subst: ScSubstitutor, map: Map, place: Option[PsiElement])
  def processScala(template: ScTemplateDefinition, subst: ScSubstitutor, map: Map, place: Option[PsiElement])
  @deprecated
  def processSyntheticScala(clazz: ScSyntheticClass, subst: ScSubstitutor, map: Map, place: Option[PsiElement])
  def processRefinement(cp: ScCompoundType, map: Map, place: Option[PsiElement])
}

object MixinNodes {
  def linearization(clazz: PsiClass, visited: collection.immutable.HashSet[PsiClass]): Seq[ScType] = {
    clazz match {
      case obj: ScObject if obj.isPackageObject && obj.getQualifiedName == "scala" => {
        return Seq(ScType.designator(obj))
      }
      case _ =>
    }

    if (visited.contains(clazz)) return Seq.empty
    CachesUtil.get(clazz, CachesUtil.LINEARIZATION_KEY,
      new CachesUtil.MyProvider(clazz, (clazz: PsiClass) => linearizationInner(clazz, visited + clazz))
      (PsiModificationTracker.JAVA_STRUCTURE_MODIFICATION_COUNT))
  }

  def linearization(compound: ScCompoundType): Seq[ScType] = {
    val comps = compound.components

    //todo: duplicate
    val buffer = new ListBuffer[ScType]
    val set: HashSet[String] = new HashSet //to add here qualified names of classes
    def add(tp: ScType) {
      ScType.extractClass(tp) match {
        case Some(clazz) if clazz.getQualifiedName != null && !set.contains(clazz.getQualifiedName) => {
          tp +=: buffer
          set += clazz.getQualifiedName
        }
        case Some(clazz) if clazz.getTypeParameters.length != 0 => {
          val i = buffer.findIndexOf(newTp => {
            ScType.extractClass(newTp) match {
              case Some(newClazz) if newClazz == clazz => true
              case _ => false
            }
          })
          if (i != -1) {
            val newTp = buffer.apply(i)
            if (tp.conforms(newTp)) buffer.update(i, tp)
          }
        }
        case _ =>
      }
    }

    val iterator = comps.reverseIterator
    while (iterator.hasNext) {
      val tp = iterator.next()
      ScType.extractClassType(tp) match {
        case Some((clazz, subst)) => {
          val lin = linearization(clazz, collection.immutable.HashSet.empty)
          val newIterator = lin.reverseIterator
          while (newIterator.hasNext) {
            val tp = newIterator.next()
            add(subst.subst(tp))
          }
        }
        case _ =>
      }
    }
    return buffer.toSeq
  }

  def linearizationInner(clazz: PsiClass, visited: collection.immutable.HashSet[PsiClass]): Seq[ScType] = {
    ProgressManager.checkCanceled()
    val tp = {
      if (clazz.getTypeParameters.length == 0) ScType.designator(clazz)
      else ScParameterizedType(ScType.designator(clazz), clazz.
              getTypeParameters.map(tp => ScalaPsiManager.instance(clazz.getProject).typeVariable(tp)))
    }
    val supers: Seq[ScType] = {
      clazz match {
        case td: ScTemplateDefinition => td.superTypes
        case clazz: PsiClass => clazz.getSuperTypes.map(tp => tp match {
          case ctp: PsiClassType =>
            val cl = ctp.resolve()
            if (cl != null && cl.getQualifiedName == "java.lang.Object") ScDesignatorType(cl)
            else ScType.create(tp, clazz.getProject)
          case _ => ScType.create(tp, clazz.getProject)
        }).toSeq
      }
    }
    val buffer = new ListBuffer[ScType]
    val set: HashSet[String] = new HashSet //to add here qualified names of classes
    def classString(clazz: PsiClass): String = {
      clazz match {
        case obj: ScObject => "Object: " + obj.getQualifiedName
        case tra: ScTrait => "Trait: " + tra.getQualifiedName
        case _ => "Class: " + clazz.getQualifiedName
      }
    }
    def add(tp: ScType) {
      ScType.extractClass(tp, Some(clazz.getProject)) match {
        case Some(clazz) if clazz.getQualifiedName != null && !set.contains(classString(clazz)) => {
          tp +=: buffer
          set += classString(clazz)
        }
        case Some(clazz) if clazz.getTypeParameters.length != 0 => {
          val i = buffer.findIndexOf(newTp => {
            ScType.extractClass(newTp, Some(clazz.getProject)) match {
              case Some(newClazz) if newClazz == clazz => true
              case _ => false
            }
          })
          if (i != -1) {
            val newTp = buffer.apply(i)
            if (tp.conforms(newTp)) buffer.update(i, tp)
          }
        }
        case _ =>
      }
    }

    val iterator = supers.iterator
    while (iterator.hasNext) {
      val tp = iterator.next()
      ScType.extractClassType(tp) match {
        case Some((clazz, subst)) => {
          val lin = linearization(clazz, visited)
          val newIterator = lin.reverseIterator
          while (newIterator.hasNext) {
            val tp = newIterator.next()
            add(subst.subst(tp))
          }
        }
        case _ =>
      }
    }
    add(tp)
    return buffer.toSeq
  }
}
