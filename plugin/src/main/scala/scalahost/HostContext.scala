package scala.reflect.internal.hosts
package scalacompiler
package scalahost

import scala.{Seq => _}
import scala.collection.immutable.Seq
import scala.reflect.{classTag, ClassTag}
import scala.reflect.runtime.universe.{Type => Pt, typeOf}
import scala.reflect.{core => p}
import scala.reflect.core.{TermQuote => _, _}
import scala.reflect.semantic._
import scala.tools.nsc.{Global => ScalaGlobal}
import scala.reflect.semantic.{HostContext => PalladiumHostContext}
import org.scalareflect.convert._
import org.scalareflect.invariants._
import org.scalareflect.unreachable

class HostContext[G <: ScalaGlobal](val g: G) extends PalladiumHostContext {
  import g.Quasiquote
  implicit val palladiumContext: PalladiumHostContext = this

  def syntaxProfile: SyntaxProfile = ???
  def semanticProfile: SemanticProfile = ???

  def owner(tree: Tree): Scope = ???
  // NOTE: def stats(scope: Scope): Seq[Tree] is implicit in signatures of Template and Pkg
  def defns(ref: Ref): Seq[Member] = ???
  def members(scope: Scope): Seq[Member] = ???
  def members(scope: Scope, name: Name): Seq[Member] = ???
  def ctors(scope: Scope): Seq[Ctor] = ???

  def defn(term: Term.Ref): Seq[Member.Term] = ???
  def defn(tpe: Type.Ref): Member = ???
  def overrides(member: Member.Term): Seq[Member.Term] = ???
  def overrides(member: Member.Type): Seq[Member.Type] = ???

  def <:<(tpe1: Type, tpe2: Type): Boolean = ???
  def weak_<:<(tpe1: Type, tpe2: Type): Boolean = ???
  def supertypes(tpe: Type): Seq[Type] = ???
  def linearization(tpes: Seq[Type]): Seq[Type] = ???
  def subclasses(tpe: Type): Seq[Member.Template] = ???
  def self(tpe: Type): Aux.Self = ???
  def lub(tpes: Seq[Type]): Type = ???
  def glb(tpes: Seq[Type]): Type = ???
  def widen(tpe: Type): Type = ???
  def dealias(tpe: Type): Type = ???
  def erasure(tpe: Type): Type = ???

  def attrs(tree: Tree): Seq[Attribute] = ???

  // TODO:
  // 4) ensure that `in` is attributed before doing any pattern match
  // 13) make sure that a cvt that unwraps and calls another cvt collects both original trees => don't use withScratchpad!
  // 14) some scratchpads might contain an attributed tree, some - a tree with just a symbol, some - a type
  // 15) structured scratchpads, not just tree/symbol/type, but actually meaningful case classes
  // 16) don't emit instances for unreachable nodes

  // NOTE: we only handle trees and types
  // NOTE: can't use MemberDef.mods, because they get their annotations erased and moved to Symbol.annotations during typechecking
  // NOTE: can't convert symbols, because that's quite unsafe: a lot of symbols don't make sense without prefixes
  // NOTE: careful use of NameTree.name, because it can lie (renaming imports) and it doesn't have enough semantic information (unlike the underlying symbol)
  // TODO: remember positions. actually, in scalac they are quite accurate, so it would be a shame to discard them
  @converter def toPalladium(in: Any, pt: Pt): Any = {
    object Helpers extends g.ReificationSupportImpl { self =>
      object SyntacticTemplate {
        def unapply(templ: g.Template): Option[(List[g.Tree], List[g.Tree], g.ValDef, List[g.Tree])] = {
          self.UnMkTemplate.unapply(templ).map { case (parents, self, ctorMods, pvparamss, earlydefns, stats) => (earlydefns, parents, self, stats) }
        }
      }
      implicit class RichType(tpe: g.Type) {
        def depoly: g.Type = tpe match {
          case g.PolyType(_, tpe) => tpe.depoly
          case _ => tpe
        }
      }
      private def alias(in: g.Tree): String = in match {
        case in: g.NameTree => in.name.toString
        case g.This(name) => name.toString
      }
      private def isBackquoted(in: g.Tree): Boolean = in match {
        // TODO: infer isBackquoted
        // TODO: iirc according to Denys, even BackquotedIdentifierAttachment sometimes lies
        case in: g.Ident => in.isBackquoted
        case _ => false
      }
      implicit class RichSymbol(gsym: g.Symbol) {
        def precvt(pre: g.Type, in: g.Tree): p.Name = {
          gsym.rawcvt(in).withScratchpad(pre)
        }
        def rawcvt(in: g.Tree): p.Name = {
          if (gsym.isTerm) p.Term.Name(alias(in))(isBackquoted = isBackquoted(in)).withScratchpad(gsym)
          else if (gsym.isType) p.Type.Name(alias(in))(isBackquoted = isBackquoted(in)).withScratchpad(gsym)
          else unreachable
        }
        def eithercvt(in: g.Tree): p.Name.Either = {
          require(gsym != g.NoSymbol)
          val gsyms = {
            if (gsym.isModuleClass) List(gsym.sourceModule.asModule, g.NoSymbol)
            else List(g.NoSymbol, gsym.asClass)
          }
          p.Name.Either(alias(in))(isBackquoted(in)).withScratchpad(gsyms)
        }
      }
      implicit class RichSymbols(gsyms: List[g.Symbol]) {
        def bothcvt(in: g.Tree): p.Name.Both = {
          val List(gterm, gtype) = gsyms
          require(gterm != g.NoSymbol || gtype != g.NoSymbol)
          require(gterm != g.NoSymbol ==> gterm.isTerm)
          require(gtype != g.NoSymbol ==> gtype.isType)
          p.Name.Both(alias(in))(isBackquoted(in)).withScratchpad(gsyms)
        }
      }
      implicit class RichTermSymbol(gsym: g.TermSymbol) {
        def precvt(pre: g.Type, in: g.Tree): p.Term.Name = (gsym: g.Symbol).precvt(pre, in).asInstanceOf[p.Term.Name]
        def rawcvt(in: g.Tree, allowNoSymbol: Boolean = false): p.Term.Name = (gsym: g.Symbol).rawcvt(in).asInstanceOf[p.Term.Name]
      }
      implicit class RichTypeSymbol(gsym: g.TypeSymbol) {
        def precvt(pre: g.Type, in: g.Tree): p.Type.Name = (gsym: g.Symbol).precvt(pre, in).asInstanceOf[p.Type.Name]
        def rawcvt(in: g.Tree): p.Type.Name = (gsym: g.Symbol).rawcvt(in).asInstanceOf[p.Type.Name]
      }
      object ValSymbol { def unapply(gsym: g.Symbol): Option[g.TermSymbol] = if (gsym.isTerm && !gsym.isMethod && !gsym.isModule && !gsym.isMutable) Some(gsym.asTerm) else None }
      object VarSymbol { def unapply(gsym: g.Symbol): Option[g.TermSymbol] = if (gsym.isTerm && !gsym.isMethod && !gsym.isModule && gsym.isMutable) Some(gsym.asTerm) else None }
      object DefSymbol { def unapply(gsym: g.Symbol): Option[g.TermSymbol] = if (gsym.isMethod) Some(gsym.asTerm) else None }
      object AbstractTypeSymbol { def unapply(gsym: g.Symbol): Option[g.TypeSymbol] = if (gsym.isType && gsym.isAbstractType) Some(gsym.asType) else None }
      object AliasTypeSymbol { def unapply(gsym: g.Symbol): Option[g.TypeSymbol] = if (gsym.isType && gsym.isAliasType) Some(gsym.asType) else None }
      private def paccessqual(gsym: g.Symbol): Option[p.Mod.AccessQualifier] = {
        if (gsym.isPrivateThis || gsym.isProtectedThis) Some(g.This(g.tpnme.EMPTY).setSymbol(gsym.privateWithin).cvt)
        else if (gsym.privateWithin == g.NoSymbol || gsym.privateWithin == null) None
        else Some(gsym.privateWithin.eithercvt(g.Ident(gsym.privateWithin))) // TODO: this loses information is gsym.privateWithin was brought into scope with a renaming import
      }
      def pmods(gsym: g.Symbol): Seq[p.Mod] = {
        val pmods = scala.collection.mutable.ListBuffer[p.Mod]()
        pmods ++= panns(gsym.annotations)
        if (gsym.isPrivate) pmods += p.Mod.Private(paccessqual(gsym))
        if (gsym.isProtected) pmods += p.Mod.Protected(paccessqual(gsym))
        if (gsym.isImplicit) pmods += p.Mod.Implicit()
        if (gsym.isFinal) pmods += p.Mod.Final()
        if (gsym.isSealed) pmods += p.Mod.Sealed()
        if (gsym.isOverride) pmods += p.Mod.Override()
        if (gsym.isCase) pmods += p.Mod.Case()
        if (gsym.isAbstract) pmods += p.Mod.Abstract()
        if (gsym.isAbstractOverride) { pmods += p.Mod.Abstract(); pmods += p.Mod.Override() }
        if (gsym.isCovariant) pmods += p.Mod.Covariant()
        if (gsym.isContravariant) pmods += p.Mod.Contravariant()
        if (gsym.isLazy) pmods += p.Mod.Lazy()
        if (gsym.isMacro) pmods += p.Mod.Macro()
        // TODO: implement this
        // if (gsym.???) pmods += p.Mod.ValParam()
        // if (gsym.???) pmods += p.Mod.VarParam()
        if (gsym.isPackageObject) pmods += p.Mod.Package()
        pmods.toList
      }
      private def pann(ann: g.AnnotationInfo): p.Mod.Annot = {
        // TODO: recover names and defaults (https://github.com/scala/scala/pull/3753/files#diff-269d2d5528eed96b476aded2ea039444R617)
        // TODO: recover multiple argument lists (?!)
        // TODO: infer the difference between @foo and @foo()
        // TODO: support classfile annotation args
        val g.AnnotationInfo(atp, args, assocs) = ann
        p.Mod.Annot(atp.cvt, List(args.cvt))
      }
      def panns(anns: List[g.AnnotationInfo]): Seq[p.Mod.Annot] = anns.map(pann)
      private def ptparam(gsym: g.Symbol): p.Aux.TypeParam = {
        // TODO: undo desugarings of context and view bounds
        require(gsym.isType)
        val isAnonymous = gsym.name == g.tpnme.WILDCARD
        if (isAnonymous) p.Aux.TypeParam.Anonymous(pmods(gsym), ptparams(gsym.typeParams), Nil, Nil, gsym.info.depoly.cvt)
        else p.Aux.TypeParam.Named(pmods(gsym), gsym.asType.cvt, ptparams(gsym.typeParams), Nil, Nil, gsym.info.depoly.cvt)
      }
      def ptparams(gsyms: List[g.Symbol]): Seq[p.Aux.TypeParam] = gsyms.map(ptparam)
      // TODO: somehow figure out the default argument from a parameter symbol if it is specified
      private def pvparam(gsym: g.Symbol): p.Aux.Param.Named = {
        require(gsym.isTerm)
        p.Aux.Param.Named(pmods(gsym), gsym.asTerm.cvt, gsym.info.depoly.cvt, None)
      }
      private def pvparams(gsyms: List[g.Symbol]): Seq[p.Aux.Param.Named] = gsyms.map(pvparam)
      private def pvparamss(gsymss: List[List[g.Symbol]]): Seq[Seq[p.Aux.Param.Named]] = gsymss.map(pvparams)
      def pexplicitss(gsym: g.Symbol): Seq[Seq[p.Aux.Param.Named]] = if (pimplicits(gsym).nonEmpty) pvparamss(gsym.info.paramss).dropRight(1) else pvparamss(gsym.info.paramss)
      def pimplicits(gsym: g.Symbol): Seq[p.Aux.Param.Named] = pvparams(gsym.info.paramss.flatten.filter(_.isImplicit))
      private def pclassof(gtype: g.Type): p.Term.ApplyType = {
        val mothershipCore = g.gen.mkAttributedRef(g.currentRun.runDefinitions.Predef_classOf)
        val orig = g.TypeApply(mothershipCore, List(g.TypeTree(gtype))).setType(g.appliedType(mothershipCore.tpe, gtype))
        orig.cvt.asInstanceOf[p.Term.ApplyType]
      }
      def pconst(gconst: g.Constant): p.Term = gconst.value match {
        case null => p.Lit.Null()
        case () => p.Lit.Unit()
        case v: Boolean => p.Lit.Bool(v)
        case v: Byte => ???
        case v: Short => ???
        case v: Int => p.Lit.Int(v)
        case v: Long => p.Lit.Long(v)
        case v: Float => p.Lit.Float(v)
        case v: Double => p.Lit.Double(v)
        case v: String => p.Lit.String(v)
        case v: Char => p.Lit.Char(v)
        case v: g.Type => pclassof(v)
        case v: g.Symbol => ??? // TODO: this is a super-crazy corner case that only appears in arguments of java annotations that refer to java enums
      }
    }
    import Helpers._
    in match {
      case g.EmptyTree =>
        unreachable
      case g.UnmappableTree =>
        unreachable
      case g.PackageDef(pid, stats) =>
        p.Pkg(pid.cvt, stats.cvt)(hasBraces = true) // TODO: infer hasBraces
      case in @ g.ClassDef(_, _, tparams, templ) =>
        require(in.symbol.isClass)
        in match {
          case q"$_ class $_[..$_] $_(...$explicits)(implicit ..$implicits) extends { ..$_ } with ..$_ { $_ => ..$_ }" =>
            val ctor = p.Ctor.Primary(pmods(in.symbol.primaryConstructor), explicits.cvt, implicits.cvt).withScratchpad(in.symbol.primaryConstructor)
            p.Defn.Class(pmods(in.symbol), in.symbol.asClass.rawcvt(in), tparams.cvt, ctor, templ.cvt)
          case q"$_ trait $_[..$_] extends { ..$_ } with ..$_ { $_ => ..$_ }" =>
            p.Defn.Trait(pmods(in.symbol), in.symbol.asClass.rawcvt(in), tparams.cvt, templ.cvt)
        }
      case in @ g.ModuleDef(_, _, templ) =>
        require(in.symbol.isModule && !in.symbol.isModuleClass)
        p.Defn.Object(pmods(in.symbol), in.symbol.asModule.rawcvt(in), templ.cvt)
      case in @ g.ValDef(_, _, tpt @ g.TypeTree(), rhs) if pt <:< typeOf[p.Aux.Param] =>
        require(in.symbol.isTerm)
        val isAnonymous = in.symbol.name.toString.startsWith("x$")
        val ptpe = {
          val ptpe = if (!tpt.wasEmpty) Some[p.Type](tpt.cvt) else None
          if (g.definitions.isRepeatedParamType(tpt.tpe)) ptpe.map(ptpe => p.Aux.ParamType.Repeated(ptpe))
          else if (g.definitions.isByNameParamType(tpt.tpe)) ptpe.map(ptpe => p.Aux.ParamType.ByName(ptpe))
          else ptpe
        }
        val pdefault = if (rhs.nonEmpty) Some(rhs.cvt) else None
        require(isAnonymous ==> pdefault.isEmpty)
        if (isAnonymous) p.Aux.Param.Anonymous(pmods(in.symbol), ptpe)
        else p.Aux.Param.Named(pmods(in.symbol), in.symbol.asTerm.rawcvt(in), ptpe, pdefault)
      case in @ g.ValDef(_, _, tpt @ g.TypeTree(), rhs) if pt <:< typeOf[p.Aux.Self] =>
        require(in.symbol.isTerm)
        val isAnonymous = in == g.noSelfType || in.symbol.name.toString == "x$1"
        val pname = if (!isAnonymous) Some(in.symbol.asTerm.rawcvt(in)) else None
        val ptpe = if (!tpt.wasEmpty) Some(tpt.cvt) else None
        require(rhs.isEmpty)
        p.Aux.Self(pname, ptpe)(hasThis = false) // TODO: figure out hasThis
      case in @ g.ValDef(_, _, tpt @ g.TypeTree(), rhs) if pt <:< typeOf[p.Member] =>
        // TODO: collapse desugared representations of pattern-based vals and vars
        require(in.symbol.isTerm)
        require(in.symbol.isDeferred ==> rhs.isEmpty)
        (in.symbol.isDeferred, in.symbol.isMutable) match {
          case (true, false) => p.Decl.Val(pmods(in.symbol), List(in.symbol.asTerm.rawcvt(in)), tpt.cvt)
          case (true, true) => p.Decl.Var(pmods(in.symbol), List(in.symbol.asTerm.rawcvt(in)), tpt.cvt)
          case (false, false) => p.Defn.Val(pmods(in.symbol), List(in.symbol.asTerm.rawcvt(in)), if (!tpt.wasEmpty) Some(tpt.cvt) else None, rhs.cvt)
          case (false, true) => p.Defn.Var(pmods(in.symbol), List(in.symbol.asTerm.rawcvt(in)), if (!tpt.wasEmpty) Some(tpt.cvt) else None, rhs.cvt)
        }
      case in @ g.DefDef(_, _, _, _, _, _) =>
        // TODO: figure out procedures
        require(in.symbol.isMethod)
        val q"$_ def $_[..$tparams](..$explicitss)(implicit ..$implicits): $tpt = $body" = in
        require(in.symbol.isDeferred ==> body.isEmpty)
        if (in.symbol.isConstructor) {
          require(!in.symbol.isPrimaryConstructor)
          val q"{ $_(...$argss); ..$stats; () }" = body
          p.Ctor.Secondary(pmods(in.symbol), explicitss.cvt, implicits.cvt, argss.cvt, stats.cvt)
        } else if (in.symbol.isDeferred) p.Decl.Def(pmods(in.symbol), in.symbol.asMethod.rawcvt(in), tparams.cvt, explicitss.cvt, implicits.cvt, tpt.cvt) // TODO: infer procedures
        else p.Defn.Def(pmods(in.symbol), in.symbol.asMethod.rawcvt(in), tparams.cvt, explicitss.cvt, implicits.cvt, tpt.cvt, body.cvt)
      case in @ g.TypeDef(_, _, tparams, tpt @ g.TypeTree()) if pt <:< typeOf[p.Aux.TypeParam] =>
        // TODO: undo desugarings of context and view bounds
        require(in.symbol.isType)
        val isAnonymous = in.symbol.name == g.tpnme.WILDCARD
        if (isAnonymous) p.Aux.TypeParam.Anonymous(pmods(in.symbol), tparams.cvt, Nil, Nil, tpt.cvt)
        else p.Aux.TypeParam.Named(pmods(in.symbol), in.symbol.asType.rawcvt(in), tparams.cvt, Nil, Nil, tpt.cvt)
      case in @ g.TypeDef(_, _, tparams, tpt @ g.TypeTree()) if pt <:< typeOf[p.Member] =>
        require(in.symbol.isType)
        if (in.symbol.isDeferred) p.Decl.Type(pmods(in.symbol), in.symbol.asType.rawcvt(in), tparams.cvt, tpt.cvt)
        else p.Defn.Type(pmods(in.symbol), in.symbol.asType.rawcvt(in), tparams.cvt, tpt.cvt)
      case g.LabelDef(_, _, _) =>
        // TODO: preprocess the input so that we don't have LabelDefs
        ???
      case g.Import(expr, selectors) =>
        // TODO: collapse desugared chains of imports
        // TODO: distinguish `import foo.x` from `import foo.{x => x}`
        p.Import(List(p.Import.Clause(expr.cvt, selectors.map(selector => {
          def resolveImport(source: String, alias: String): p.Name.Both = {
            val imported = g.TermName(source).bothNames.map(source => expr.tpe.nonLocalMember(source))
            imported.bothcvt(g.Ident(g.TermName(alias)))
          }
          selector match {
            case g.ImportSelector(g.nme.WILDCARD, _, null, _) =>
              p.Import.Selector.Wildcard()
            case g.ImportSelector(name1, _, name2, _) if name1 == name2 =>
              p.Import.Selector.Name(resolveImport(name1.toString, name1.toString))
            case g.ImportSelector(name1, _, name2, _) if name1 != name2 =>
              p.Import.Selector.Rename(resolveImport(name1.toString, name1.toString), resolveImport(name1.toString, name2.toString))
            case g.ImportSelector(name, _, g.nme.WILDCARD, _) =>
              p.Import.Selector.Unimport(resolveImport(name.toString, name.toString))
          }
        }))))
      case in @ g.Template(_, _, rawstats) =>
        // NOTE: SyntacticTemplate (based on UnMkTemplate, the basis of SyntacticClassDef and friends)
        // returns incorrect parents if input is typechecked, so we have to work around
        val SyntacticTemplate(earlydefns, _, self, stats) = in
        val parents = {
          // TODO: discern `... extends C()` and `... extends C`
          val incompleteParents = in.parents.map(tpe => g.Apply(tpe, Nil))
          g.treeInfo.firstConstructor(rawstats) match {
            case g.DefDef(_, _, _, _, _, rawinit) =>
              val rawsupercall = rawinit.collect { case app @ g.treeInfo.Applied(g.Select(g.Super(_, _), _), _, _) => app }.head
              object prettifier extends g.Transformer {
                override def transform(tree: g.Tree): g.Tree = tree match {
                  case g.TypeApply(g.Select(g.Super(_, _), _), _) | g.Select(g.Super(_, _), _) =>
                    g.TypeTree(tree.tpe.finalResultType).setSymbol(tree.symbol)
                  case _ =>
                    super.transform(tree)
                }
              }
              prettifier.transform(rawsupercall) +: incompleteParents.drop(1)
            case g.EmptyTree =>
              incompleteParents
          }
        }
        p.Aux.Template(earlydefns.cvt, parents.cvt, self.cvt, stats.cvt)(hasBraces = true) // TODO: infer hasBraces
      case g.Block(stats, expr) =>
        p.Term.Block((stats :+ expr).cvt)
      case g.CaseDef(pat, guard, body) =>
        p.Aux.Case(pat.cvt, if (guard.nonEmpty) Some(guard.cvt) else None, body.cvt)
      case g.Alternative(fst :: snd :: Nil) =>
        p.Pat.Alternative(fst.cvt, snd.cvt)
      case in @ g.Alternative(hd :: rest) =>
        p.Pat.Alternative(hd.cvt, g.Alternative(rest).setType(in.tpe).cvt)
      case g.Ident(g.nme.WILDCARD) =>
        p.Pat.Wildcard()
      case g.Star(g.Ident(g.nme.WILDCARD)) =>
        p.Pat.SeqWildcard()
      case in @ g.Bind(_, g.nme.WILDCARD) =>
        // TODO: discern `case x => ...` and `case x @ _ => ...`
        require(in.symbol.isTerm)
        in.symbol.asTerm.rawcvt(in)
      case in @ g.Bind(_, g.EmptyTree) =>
        require(in.symbol.isType)
        in.symbol.asType.rawcvt(in)
      case in @ g.Bind(_, g.Typed(g.nme.WILDCARD, tpt @ g.TypeTree())) =>
        require(in.symbol.isTerm)
        p.Pat.Typed(in.symbol.asTerm.rawcvt(in), tpt.cvt)
      case in @ g.Bind(name, tree) =>
        require(in.symbol.isTerm)
        require(name == in.symbol.name)
        p.Pat.Bind(in.symbol.asTerm.rawcvt(in), tree.cvt)
      case in @ g.UnApply(q"$ref.$unapply[..$targs](`<unapply-selector>`)", args) =>
        // TODO: infer Extract vs ExtractInfix
        // TODO: infer whether it was an application or a Tuple
        // TODO: also figure out Interpolate
        require(unapply == g.TermName("unapply") || unapply == g.TermName("unapplySeq"))
        p.Pat.Extract(ref.cvt, targs.cvt, args.cvt).withScratchpad(in.symbol)
      case g.Function(params, body) =>
        // TODO: recover eta-expansions that typer desugars to lambdas
        // TODO: recover shorthand function syntax
        p.Term.Function(params.cvt, body.cvt)
      case g.Assign(lhs, rhs) =>
        p.Term.Assign(lhs.cvt, rhs.cvt)
      case g.AssignOrNamedArg(lhs, rhs) =>
        unreachable
      case g.If(cond, thenp, elsep) =>
        // TODO: infer the difference between If.Then and If.ThenElse
        p.Term.If.ThenElse(cond.cvt, thenp.cvt, elsep.cvt): p.Term.If
      case g.Match(selector, cases) =>
        // TODO: it's cute that Term.Cases is a Term, but what tpe shall we return for it? :)
        p.Term.Match(selector.cvt, p.Term.Cases(cases.cvt))
      case g.Return(expr) =>
        p.Term.Return(expr.cvt)
      case g.Try(block, catches, finalizer) =>
        // TODO: undo desugarings of `try foo catch bar`
        val catchp = if (catches.nonEmpty) Some(p.Term.Cases(catches.cvt)) else None
        val finallyp = if (finalizer.nonEmpty) Some(finalizer.cvt) else None
        p.Term.Try(block.cvt, catchp, finallyp)
      case g.Throw(expr) =>
        p.Term.Throw(expr.cvt)
      case g.New(_) =>
        unreachable
      case in @ g.Apply(g.Select(g.New(_), g.nme.CONSTRUCTOR), _) =>
        // TODO: infer the difference between `new X` vs `new X()`
        // TODO: undo desugarings of stuff like `new X {}`
        // TODO: strip off inferred type and value arguments (but be careful to not remove explicitly provided arguments!)
        val q"new $tpt(...$argss)" = in
        val supertpt = tpt.duplicate.setSymbol(in.symbol)
        val supercall = p.Aux.Parent(supertpt.cvt, argss.cvt).withScratchpad(in)
        val self = p.Aux.Self(None, None)(hasThis = false).withScratchpad(tpt)
        val templ = p.Aux.Template(Nil, List(supercall), self, stats = Nil)(hasBraces = false).withScratchpad(in)
        p.Term.New(templ)
      case g.Typed(expr, tpt @ g.TypeTree()) if pt <:< typeOf[p.Term] =>
        // TODO: infer the difference between Ascribe and Annotate
        p.Term.Ascribe(expr.cvt, tpt.cvt)
      case g.Typed(expr, tpt @ g.TypeTree()) if pt <:< typeOf[p.Pat] =>
        p.Pat.Typed(expr.cvt, tpt.cvt)
      case _: g.Apply | _: g.TypeApply =>
        // TODO: infer the difference between Apply, ApplyInfix, ApplyUnary and Update
        // TODO: infer whether it was an application or a Tuple
        // TODO: recover names and defaults (https://github.com/scala/scala/pull/3753/files#diff-269d2d5528eed96b476aded2ea039444R617)
        // TODO: strip off inferred type arguments in loopParent
        // TODO: infer whether implicit arguments were provided explicitly and don't remove them if so
        // TODO: undo the for desugaring
        // TODO: undo the Lit.Symbol desugaring
        // TODO: undo the interpolate desugaring
        def loopParent(in: g.Tree, argss: List[List[p.Term]]): p.Tree = in match {
          case g.Apply(fun, args) if g.isImplicitMethodType(fun.tpe) => loopParent(fun, argss)
          case g.Apply(fun, args) => loopParent(fun, argss :+ args.cvt)
          case g.TypeTree() => p.Aux.Parent(in.cvt, argss)
        }
        def loopVanilla(in: g.Tree): p.Term = in match {
          case g.Apply(fun, args) if g.isImplicitMethodType(fun.tpe) => loopVanilla(fun)
          case g.Apply(fun, args) => p.Term.Apply(loopVanilla(fun), args.cvt).withScratchpad(in)
          case g.TypeApply(fun, targs) if targs exists { case tt: g.TypeTree => tt.wasEmpty } => loopVanilla(fun)
          case g.TypeApply(fun, targs) => p.Term.ApplyType(loopVanilla(fun), targs.cvt).withScratchpad(in)
          case _ => in.cvt
        }
        val in1 = in.asInstanceOf[g.Tree]
        val core = g.treeInfo.dissectApplied(in1).core
        if (core.isTerm) loopVanilla(in1) else loopParent(in1, Nil)
      case in @ g.ApplyDynamic(_, _) =>
        unreachable
      case in @ g.Super(qual, mix) =>
        require(in.symbol.isClass)
        val pthis = if (qual != g.tpnme.EMPTY) Some(qual.cvt) else None
        val psuper = if (mix != g.tpnme.EMPTY) Some(in.symbol.asClass.cvt) else None
        p.Aux.Super(pthis, psuper)
      case in @ g.This(qual) =>
        p.Term.This(if (qual != g.tpnme.EMPTY) Some(in.symbol.eithercvt(in)) else None)
      case in: g.PostfixSelect =>
        unreachable
      case in @ g.Select(qual, name) =>
        require(in.symbol.isTerm)
        p.Term.Select(qual.cvt, in.symbol.asTerm.precvt(qual.tpe, in))(isPostfix = false) // TODO: figure out isPostfix
      case in @ g.Ident(name) =>
        in.symbol.rawcvt(in)
      case g.ReferenceToBoxed(_) =>
        ???
      case g.Literal(const) =>
        pconst(const)
      case g.Parens(_) =>
        unreachable
      case g.DocDef(_, _) =>
        unreachable
      case g.Annotated(_, _) =>
        unreachable
      case g.ArrayValue(_, _) =>
        unreachable
      case g.InjectDerivedValue(_) =>
        unreachable
      case g.SelectFromArray(_, _, _) =>
        unreachable
      case in @ g.TypeTree() =>
        // TODO: iirc there were two problems with originals that we faced when developing reify:
        // 1) some originals are attributed only partially
        // 2) some originals are incomplete (e.g. for compound types iirc)
        // therefore I think we shouldn't use originals at the moment, so I'm marking g.TypTree as unreachable
        in.tpe.cvt
      case g.TypeTreeWithDeferredRefCheck() =>
        ???
      case _: g.TypTree =>
        unreachable
      case g.NoPrefix =>
        unreachable
      case g.NoType =>
        unreachable
      case g.ThisType(sym) =>
        // TODO: infer whether thistpe originally corresponded to Some or None
        p.Type.Singleton(g.This(sym).cvt)
      case g.SuperType(thistpe, supertpe) =>
        // TODO: infer whether supertpe originally corresponded to Some or None
        val p.Type.Singleton(p.Term.This(pthis)) = thistpe.cvt
        require(supertpe.typeSymbol.isType)
        val supersym = supertpe.typeSymbol.asType
        p.Type.Singleton(p.Aux.Super(pthis, Some(supersym.rawcvt(g.Ident(supersym)).withScratchpad(in))))
      case g.SingleType(pre, sym) =>
        // TODO: this loses information if sym was brought into scope with a renaming import
        require(sym.isTerm)
        val ref = (pre match {
          case g.NoPrefix =>
            sym.asTerm.rawcvt(g.Ident(sym))
          case _: g.SingletonType =>
            val p.Type.Singleton(preref) = pre.cvt
            p.Term.Select(preref, sym.asTerm.precvt(pre, g.Ident(sym)))(isPostfix = false) // TODO: figure out isPostfix
          case _ =>
            unreachable
        }).withScratchpad(in)
        p.Type.Singleton(ref)
      case g.ConstantType(const) =>
        pconst(const) match {
          case lit: p.Lit => lit
          // TODO: can Literal(Constant(_: Type)) or Literal(Constant(_: Symbol)) ever end up in patterns?
          case _ => unreachable
        }
      case g.TypeRef(pre, sym, args) =>
        // TODO: this loses information if sym was brought into scope with a renaming import
        require(sym.isType)
        val ref = (pre match {
          case g.NoPrefix =>
            sym.asType.rawcvt(g.Ident(sym))
          case _: g.SingletonType =>
            val p.Type.Singleton(preref) = pre.cvt
            p.Type.Select(preref, sym.asType.precvt(pre, g.Ident(sym)))
          case _ =>
            p.Type.Project(pre.cvt, sym.asType.precvt(pre, g.Ident(sym)))
        }).withScratchpad(in)
        // TODO: infer whether that was Apply, Function or Tuple
        // TODO: discern Apply and ApplyInfix
        if (args.isEmpty) ref
        else p.Type.Apply(ref, args.cvt)
      case g.RefinedType(parents, decls) =>
        val pstmts: Seq[p.Stmt.Refine] = decls.sorted.toList.map({
          case ValSymbol(sym) => p.Decl.Val(pmods(sym), List(sym.rawcvt(g.ValDef(sym))), sym.info.depoly.cvt)
          case VarSymbol(sym) if !sym.isMethod && !sym.isModule && sym.isMutable => p.Decl.Var(pmods(sym), List(sym.rawcvt(g.ValDef(sym))), sym.info.depoly.cvt)
          // TODO: infer the difference between Defs and Procedures
          case DefSymbol(sym) => p.Decl.Def(pmods(sym), sym.rawcvt(g.DefDef(sym, g.EmptyTree)), ptparams(sym.typeParams), pexplicitss(sym), pimplicits(sym), sym.info.finalResultType.cvt)
          case AbstractTypeSymbol(sym) => p.Decl.Type(pmods(sym), sym.rawcvt(g.TypeDef(sym)), ptparams(sym.typeParams), sym.info.depoly.cvt)
          case AliasTypeSymbol(sym) => p.Defn.Type(pmods(sym), sym.rawcvt(g.TypeDef(sym, g.TypeTree(sym.info))), ptparams(sym.typeParams), sym.info.depoly.cvt)
        })
        p.Type.Compound(parents.cvt, pstmts)(hasBraces = true) // TODO: infer hasBraces
      case g.ExistentialType(quantified, underlying) =>
        // TODO: infer type placeholders where they were specified explicitly
        val pstmts: Seq[p.Stmt.Existential] = quantified.map({
          case ValSymbol(sym) => p.Decl.Val(pmods(sym), List(sym.rawcvt(g.ValDef(sym))), sym.info.depoly.cvt)
          case AbstractTypeSymbol(sym) => p.Decl.Type(pmods(sym), sym.rawcvt(g.TypeDef(sym)), ptparams(sym.typeParams), sym.info.depoly.cvt)
        })
        p.Type.Existential(underlying.cvt, pstmts)
      case g.AnnotatedType(anns, underlying) =>
        p.Type.Annotate(underlying.cvt, panns(anns))
      case g.TypeBounds(lo, hi) =>
        // TODO: infer which of the bounds were specified explicitly by the user
        p.Aux.TypeBounds(Some(lo.cvt), Some(hi.cvt))
      // NOTE: these types have no equivalent in Palladium
      // e.g. q"List.apply".tpe is not some mysterious MethodType, but is an error, because List.apply is actually not a well-formed term
      // if one wants to analyze q"List.apply" in e.g. q"List.apply(2)", then it's possible to do Term.defn
      case g.ClassInfoType(_, _, _) =>
        unreachable
      case g.MethodType(_, _) =>
        unreachable
      case g.NullaryMethodType(_) =>
        unreachable
      case g.PolyType(_, _) =>
        unreachable
      case g.WildcardType =>
        unreachable
      case g.BoundedWildcardType(_) =>
        unreachable
      case in: g.UniqueErasedValueType =>
        unreachable
      case in: g.RepeatedType =>
        unreachable
      case in: g.NamedType =>
        unreachable
      case in: g.AppliedTypeVar =>
        unreachable
      case in: g.HKTypeVar =>
        unreachable
      case in: g.AntiPolyType =>
        unreachable
      case in: g.ImportType =>
        unreachable
      case in: g.OverloadedType =>
        unreachable
      case g.ErrorType =>
        unreachable
    }
  }
}