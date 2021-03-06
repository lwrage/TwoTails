package twotails

import scala.annotation.{switch, tailrec}
import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.{Plugin, PluginComponent}
import scala.tools.nsc.symtab.Flags._
import scala.tools.nsc.transform.{Transform, TypingTransformers}
import collection.mutable.{Map => MMap, Set => MSet}
import collection.breakOut

class TwoTailsPlugin(val global: Global) extends Plugin{
  val name = "twotails"
  val description = "Adds support for mutually recursive functions in Scala."
  val components = List[PluginComponent](new MutualRecComponent(this, global))
}

class MutualRecComponent(val plugin: Plugin, val global: Global) 
    extends PluginComponent with Transform with TypingTransformers {
  import global._

  val phaseName = "twotails"
  override val runsBefore = List("tailcalls", "patmat")
  val runsAfter = List("typer")

  def newTransformer(unit: CompilationUnit) = new MutualRecTransformer(unit)

  final class MutualRecTransformer(unit: CompilationUnit) extends TypingTransformer(unit){

    override def transform(tree: Tree): Tree = super.transform{
      tree match{
        case cd @ ClassDef(mods, name, tparams, body) =>
          val trans = transformBody(tree, body)
          if(trans != body) treeCopy.ClassDef(cd, mods, name, tparams, trans) else cd
        case md @ ModuleDef(mods, name, body) =>
          val trans = transformBody(tree, body)
          if(trans != body) treeCopy.ModuleDef(md, mods, name, trans) else md
        case bl @ Block(stats, expr) if expr != EmptyTree =>
          val trans = transformNested(tree, stats)
          if(stats != trans) treeCopy.Block(bl, trans, expr) else bl
        case _ => tree
      }
    }

    def transformBody(root: Tree, template: Template): Template ={
      val Template(parents, valDef, body) = template

      val cnt = body.count{
        case ddef: DefDef => hasMutualRec(ddef)
        case _ => false
      }

      (cnt: @switch) match{
        case 0 => template
        case 1 => convertTailRec(body); template
        case _ => treeCopy.Template(template, parents, valDef, convertMutualRec(root, body))
      }
    }

    def transformNested(root: Tree, stats: List[Tree]): List[Tree] ={
      val cnt = stats.count{
        case ddef: DefDef => hasMutualRec(ddef)
        case _ => false
      }

      (cnt: @switch) match{
        case 0 => stats
        case 1 => convertTailRec(stats); stats
        case _ => convertMutualRec(root, stats)
      }
    }

    private final val mtrec: Symbol = rootMirror.getRequiredClass("twotails.mutualrec")
    private final val trec = AnnotationInfo(definitions.TailrecClass.tpe, Nil, Nil)

    def hasMutualRec(tree: Tree) = 
      (tree.symbol != NoSymbol) &&
      tree.symbol.annotations.exists(_.tpe.typeSymbol == mtrec)

    //In case there's just one, convert to a @tailrec. No reason to add a whole new method.
    def convertTailRec(body: List[Tree]): Unit = body.foreach{
      case ddef: DefDef if hasMutualRec(ddef) => 
        ddef.symbol
          .removeAnnotation(mtrec)
          .setAnnotations(trec :: Nil)
      case _ => 
    }

    def convertMutualRec(root: Tree, body: List[Tree]): List[Tree] ={
      val (recs, everythingElse) = body.partition{
        case ddef: DefDef => hasMutualRec(ddef)
        case _ => false
      }
      val symbols: Map[Symbol, Tree] = recs.map{ t => (t.symbol, t) }(breakOut)
      val walker = new CallGraphWalker(symbols.keySet)
      val adjacencyList: Map[Symbol, List[Symbol]] = recs.map{ tree =>
        val calls = walker.walk(tree)
        (tree.symbol, calls)
      }(breakOut)
      val groups ={
        var grouped: List[List[Symbol]] = Nil
        for(t <- recs if !grouped.exists(_.contains(t.symbol))){
          grouped = component(t.symbol, adjacencyList) :: grouped
        }
        grouped.map(_.map(symbols))
      }
      val ungrouped = recs.filter{ t => !groups.exists(_.contains(t)) }

      var tndx = 0
      val optimized = groups flatMap {
        case Nil => Nil
        case head :: Nil => head.symbol
          .removeAnnotation(mtrec)
          .setAnnotations(trec :: Nil)
          head :: Nil
        case head :: tail => 
          val methSym = mkNewMethodSymbol(head.symbol, TermName("mutualrec_fn$" + tndx))
          val rhs = mkNewMethodRhs(methSym, head :: tail)
          val methTree = mkNewMethodTree(methSym, root, rhs)
          val forwardedTrees = forwardTrees(methSym, head :: tail)
          tndx += 1
          methTree :: forwardedTrees
      }

      if(ungrouped.isEmpty) everythingElse ::: optimized
      else everythingElse ::: ungrouped ::: optimized
    }

    def component(root: Symbol, adjacencyList: Map[Symbol, List[Symbol]]): List[Symbol] ={
      //checking that the path is one that should be followed
      def hasSame(that: Symbol): Boolean = that.info.matches(root.info)

      val out = MSet.empty[Symbol]
      val visited = MSet.empty[Symbol]
      def visit(s: Symbol, path: List[Symbol] = Nil): Unit = {
        visited += s
        adjacencyList(s) foreach{
          case `root` => out += s ++= path
          case c if out.contains(c) => out ++= path
          case c if visited.contains(c) => //do nothing
          case c if hasSame(c) => visit(c, s :: path)
          case c => visited += c
        }
      }
      visit(root)
      out.filter(adjacencyList(_).nonEmpty).toList
    }

    def mkNewMethodSymbol(symbol: Symbol, 
                          name: TermName, 
                          flags: FlagSet = METHOD | FINAL | PRIVATE | ARTIFACT): Symbol ={
      val methSym = symbol.cloneSymbol(symbol.owner, flags, name)
      val param = methSym.newSyntheticValueParam(definitions.IntTpe, TermName("indx"))
      
      methSym.modifyInfo {
        case GenPolyType(tparams, MethodType(params, res)) => GenPolyType(tparams, MethodType(param :: params, res))
      }
      methSym
        .removeAnnotation(mtrec)
        .setAnnotations(trec :: Nil)
      localTyper.namer.enterInScope(methSym)
    }

    def mkNewMethodRhs(methSym: Symbol, defdef: List[Tree]): Tree ={
      val defSymbols: Map[Symbol, () => Tree] = defdef.zipWithIndex.map{
        case (d, i) => (d.symbol, {() => localTyper.typed(Literal(Constant(i)))})
      }(breakOut)
      val callTransformer = new MutualCallTransformer(methSym, defSymbols)
      val defrhs = defdef.map{ tree =>
        val DefDef(_, _, _, vparams, _, rhs) = tree

        //shamelessly taken from @retronym's example #20 of the Scalac survival guide.
        val origTparams = tree.symbol.info.typeParams
        val (oldSkolems, deskolemized) = if(origTparams.isEmpty) (Nil, Nil) else{
          val skolemSubst = MMap.empty[Symbol, Symbol]
          rhs.foreach{
            _.tpe.foreach {
              case tp if tp.typeSymbolDirect.isSkolem =>
                val tparam = tp.typeSymbolDirect.deSkolemize
                if (!skolemSubst.contains(tparam) && origTparams.contains(tparam)) {
                  skolemSubst(tp.typeSymbolDirect) = methSym.typeParams(origTparams.indexOf(tparam))
                }
              case _ =>
            }
          }
          skolemSubst.toList.unzip
        }

        val old = oldSkolems ::: tree.symbol.typeParams ::: vparams.flatMap(_.map(_.symbol))
        val neww = deskolemized ::: methSym.typeParams ::: methSym.info.paramss.flatten.drop(1)
        
        callTransformer.transform(rhs)
          .changeOwner(tree.symbol -> methSym)
          .substituteSymbols(old, neww)
      }

      val cases = defrhs.zipWithIndex.map{
        case (body, i) => cq"$i => $body"
      }

      q"(indx: @scala.annotation.switch) match{ case ..$cases }"
    }

    def mkNewMethodTree(methSym: Symbol, tree: Tree, rhs: Tree): Tree ={
      @tailrec def find(t: Tree): Position = t match{
        case Block(Nil, r) => if(r.symbol != null) r.symbol.pos else NoPosition
        case Block(h :: _, _) => find(h)
        case _  => if (t.symbol != null) t.symbol.pos else NoPosition
      }
      localTyper.typedPos(find(tree)){
        DefDef(methSym, rhs)
      }
    }

    def forwardTrees(methSym: Symbol, defdef: List[Tree]): List[Tree] = defdef.zipWithIndex.map{
  	  case (tree, indx) =>
        val DefDef(_, _, _, vp :: vps, _, _) = tree
        val newVp = localTyper.typed(Literal(Constant(indx))) :: vp.map(p => gen.paramToArg(p.symbol))
        val refTree = gen.mkAttributedRef(tree.symbol.owner.thisType, methSym)
        val forwarderTree = (Apply(refTree, newVp) /: vps){
          (fn, params) => Apply(fn, params map (p => gen.paramToArg(p.symbol)))
        }
        val forwarded = deriveDefDef(tree)(_ => localTyper.typedPos(tree.symbol.pos)(forwarderTree))
        if(tree.symbol.isEffectivelyFinalOrNotOverridden) forwarded.symbol.removeAnnotation(mtrec)
        forwarded
    }
  }

  /** @notes The newly created method symbol can be reused without issue but if the symbol `Tree`
   *         of the index literals are reused, creates a `NullPointerException` during Erasure 
   *         phase.
   */
  final class MutualCallTransformer(methSym: Symbol, symbols: Map[Symbol, () => Tree]) extends Transformer{
    private val ref = gen.mkAttributedRef(methSym)
    private val bor = currentRun.runDefinitions.Boolean_or
    private val band = currentRun.runDefinitions.Boolean_and

    override def transform(tree: Tree): Tree = tree match{
      case Apply(fn, _) if symbols.contains(fn.symbol) => multiArgs(tree)
      case Apply(fn, args) if fn.symbol == band || fn.symbol == bor =>
        treeCopy.Apply(tree, fn, transformTrees(args))
      case Block(stats, expr) => treeCopy.Block(tree, stats, transform(expr))
      case If(pred, ftrue, ffalse) => treeCopy.If(tree, pred, transform(ftrue), transform(ffalse))
      case Match(selector, cases) => 
        treeCopy.Match(tree, selector, transformTrees(cases).asInstanceOf[List[CaseDef]])
      case Try(block, catches, EmptyTree) => 
        treeCopy.Try(tree, block, transformTrees(catches).asInstanceOf[List[CaseDef]], EmptyTree)
      case that: Apply => that
      case that: Try => that
      case that: Select => that
      case that: DefDef => that
      case that: ClassDef => that
      case that: ModuleDef => that
      case _ => super.transform(tree)
    }

    def multiArgs(tree: Tree): Tree = tree match{
      case Apply(fn, args) => 
        multiArgs(fn) match{
          case f @ Apply(_, _) => treeCopy.Apply(tree, f, transformTrees(args))
          case f => treeCopy.Apply(tree, f, symbols(fn.symbol)() :: transformTrees(args))
        }
      case TypeApply(fn, targs) => 
        val out = treeCopy.TypeApply(tree, ref, targs)
        out.setType(ref.tpe)
        out
      case _ => ref
    }
  }

  //Searches the Tree and returns all functions found in tailcall position from a search set.
  final class CallGraphWalker(search: Set[Symbol]) extends Traverser{
    private final val calls = MSet.empty[Symbol]
    private var isValid = true

    def walk(tree: Tree): List[Symbol] ={
      calls clear ()
      tree match { 
        case x:DefDef => traverse(x.rhs)
        case _ => ()
      }
      calls.toList
    }

    def step(tree: Tree, valid: Boolean): Unit ={
      val oldValid = isValid
      isValid &= valid
      traverse(tree)
      isValid = oldValid
    }

    def steps(trees: List[Tree], valid: Boolean): Unit ={
      val oldValid = isValid
      isValid &= valid
      traverseTrees(trees)
      isValid = oldValid
    }

    private val bor = currentRun.runDefinitions.Boolean_or
    private val band = currentRun.runDefinitions.Boolean_and

    //Attempting to use the same search criteria as found in TailRec.
    override def traverse(tree: Tree): Unit = tree match{
      case Apply(_, args) if search.contains(tree.symbol) => 
        if(isValid) calls += tree.symbol else calls -= tree.symbol
        steps(args, false)
      case Apply(fun, args) if fun.symbol == bor || fun.symbol == band =>
        step(fun, false)
        steps(args, isValid)
      case Apply(fun, args) => 
        step(fun, false)
        steps(args, false)
      case DefDef(_, _, _, vparams, _, rhs) =>
        vparams.foreach(steps(_, false))
        step(rhs, false)
      case Block(stats, expr) =>
        steps(stats, false)
        step(expr, isValid)
      case If(pred, ftrue, ffalse) =>
        step(pred, false)
        step(ftrue, isValid)
        step(ffalse, isValid)
      case Match(selector, cases) =>
        step(selector, false)
        steps(cases, isValid)
      case Try(block, catches, EmptyTree) =>
        step(block, false) //TODO: Does this mean things surrounded by try{ } aren't tail pos?!
        steps(catches, isValid)
      case Try(block, catches, finalizer) =>
        step(block, false)
        steps(catches, false)
        step(finalizer, false)
      case Select(qual, _) =>
        step(qual, false)
      case _ => super.traverse(tree)
    }
  }
}