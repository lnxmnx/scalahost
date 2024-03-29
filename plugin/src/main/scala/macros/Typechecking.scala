package scala.meta
package internal.hosts.scalacompiler
package macros

import scala.reflect.internal.Flags._
import scala.meta.internal.hosts.scalacompiler.{Plugin => PalladiumPlugin}

trait Typechecking {
  self: PalladiumPlugin =>

  import global._
  import definitions._
  import treeInfo._
  import analyzer.{MacroPlugin => NscMacroPlugin, _}

  // TODO: would be nice if we had a way to know whether `tree` is a collapsed single-element block
  // then we could reliably discern vanilla macro syntax `def foo = macro bar` and palladium macro syntax `def foo = macro { ... }`
  // for now we have to approximate, and maybe later we could make this approximation more precise
  object PalladiumBody {
    def unapply(tree: Tree): Option[Tree] = {
      def isVanillaBody(tree: Tree): Boolean = tree match {
        case Ident(_) => true
        case Select(_, _) => true
        case TypeApply(fn, _) => isVanillaBody(tree)
        case _ => false
      }
      if (isVanillaBody(tree)) None else Some(tree)
    }
  }

  def palladiumTypedMacroBody(typer: Typer, ddef: DefDef): Option[Tree] = {
    val TermQuote = "denied" // TODO: find a better solution
    ddef match {
      case DefDef(mods, name, tparams, vparamss, _, PalladiumBody(body)) if mods.hasFlag(MACRO) =>
        def cleanupMods(mods: Modifiers) = mods &~ IMPLICIT
        val vparamss1 = mmap(vparamss){
          case p @ q"$mods val $pname: $_ = $_" =>
            val p1 = atPos(p.pos)(q"${cleanupMods(mods)} val $pname: _root_.scala.meta.Term")
            if (isRepeated(p.symbol)) copyValDef(p1)(tpt = tq"_root_.scala.<repeated>[${p1.tpt}]") else p1
        }
        val c = q"implicit val ${TermName("c$" + globalFreshNameCreator.newName(""))}: _root_.scala.meta.semantic.MacroHost"
        val implDdef = atPos(ddef.pos)(q"def $name[..$tparams](...$vparamss1)(implicit $c): _root_.scala.meta.Term = $body")
        val q"{ ${typedImplDdef: DefDef}; () }" = typer.typed(q"{ $implDdef; () }")
        if (typedImplDdef.exists(_.isErroneous)) {
          if (ddef.symbol != null) ddef.symbol setFlag IS_ERROR
          ddef setType ErrorType
        } else {
          var isExplicitlyWhitebox = false
          object dewhiteboxer extends Transformer {
            private val c_whitebox = typeOf[scala.meta.semantic.`package`.c.type].decl(TermName("whitebox")).asMethod
            override def transform(tree: Tree): Tree = tree match {
              case Apply(fn, List(arg)) if fn.symbol == c_whitebox => isExplicitlyWhitebox = true; transform(arg)
              case tree => super.transform(tree)
            }
          }
          val typedImplDdef1 = dewhiteboxer.transform(typedImplDdef).asInstanceOf[DefDef]
          // NOTE: order is actually very important here, because at the end of the day
          // we need the legacy annotation to come first so that it can be picked up by the 2.11.x macro engine
          // (otherwise half of the standard macro infrastructure will cease to function)
          ddef.symbol.addAnnotation(MacroImplAnnotation, PalladiumSignature(!isExplicitlyWhitebox, typedImplDdef1))
          ddef.symbol.addAnnotation(MacroImplAnnotation, LegacySignature())
        }
        Some(EmptyTree)
      case _ =>
        None
    }
  }

  object LegacySignature extends FixupSignature {
    def apply(): Tree = fixup(Apply(Ident(TermName("macro")), List(Assign(Literal(Constant("macroEngine")), Literal(Constant("Palladium experimental macro engine"))))))
  }

  object PalladiumSignature extends FixupSignature {
    def apply(isBlackbox: Boolean, implDdef: DefDef): Tree = {
      fixup(Apply(Ident(TermName("palladiumMacro")), List(
        Assign(Literal(Constant("isBlackbox")), Literal(Constant(isBlackbox))),
        Assign(Literal(Constant("implDdef")), implDdef))))
    }
    def unapply(tree: Tree): Option[(Boolean, DefDef)] = {
      tree match {
        case Apply(Ident(TermName("palladiumMacro")), List(
          Assign(Literal(Constant("isBlackbox")), Literal(Constant(isBlackbox: Boolean))),
          Assign(Literal(Constant("implDdef")), (implDdef: DefDef)))) => Some((isBlackbox, implDdef))
        case _ => None
      }
    }
  }

  // NOTE: this fixup is necessary for signatures to be accepted as annotations
  // if we don't set types in annotations, then pickler is going to crash
  // apart from constants, it doesn't really matter what types we assign, so we just go for NoType
  trait FixupSignature {
    protected def fixup(tree: Tree): Tree = {
      new Transformer {
        override def transform(tree: Tree) = {
          tree match {
            case Literal(const @ Constant(x)) if tree.tpe == null => tree setType ConstantType(const)
            case _ if tree.tpe == null => tree setType NoType
            case _ => ;
          }
          super.transform(tree)
        }
      }.transform(tree)
    }
  }

  def palladiumIsBlackbox(macroDef: Symbol): Option[Boolean] = {
    val macroSignatures = macroDef.annotations.filter(_.atp.typeSymbol == MacroImplAnnotation)
    macroSignatures match {
      case _ :: AnnotationInfo(_, List(PalladiumSignature(isBlackbox, _)), _) :: Nil => Some(isBlackbox)
      case _ => None
    }
  }
}
