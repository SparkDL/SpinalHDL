package spinal.core


import scala.collection.mutable
import scala.collection.mutable.{ListBuffer, ArrayBuffer}
import scala.util.Random


class ComponentEmiterVhdl(val c : Component,
                          vhdlBase: VhdlBase,
                          override val algoIdIncrementalBase : Int,
                          override val mergeAsyncProcess : Boolean,
                          anonymSignalPrefix : String,
                          emitedComponentRef : java.util.concurrent.ConcurrentHashMap[Component,Component]) extends ComponentEmiter{
  import vhdlBase._

  override def component = c

  val portMaps = ArrayBuffer[String]()
  val declarations = new StringBuilder()
  val logics = new StringBuilder()

  def getTrace() = new ComponentEmiterTrace(declarations :: logics :: Nil, portMaps)


  def result : String = {
    val ret = new StringBuilder()
    emitLibrary(ret)
    ret ++= s"\nentity ${c.definitionName} is\n"
    ret ++= s"  port("
    var first = true
    for(portMap <- portMaps){
      if(first){
        ret ++= s"\n    $portMap"
        first = false
      } else {
        ret ++= s";\n    $portMap"
      }
    }
    ret ++= "\n  );\n"

    ret ++= s"end ${c.definitionName};\n"
    ret ++= s"\n"

    ret ++= s"architecture arch of ${c.definitionName} is\n"
    ret ++= declarations
    ret ++= s"begin\n"
    ret ++= logics
    ret ++= s"end arch;\n"
    ret ++= s"\n"
    ret.toString()
  }




  def emitEntity(): Unit = {
    component.getOrdredNodeIo.foreach(baseType =>
      portMaps += s"${baseType.getName()} : ${emitDirection(baseType)} ${emitDataType(baseType)}${getBaseTypeSignalInitialisation(baseType)}"
    )
  }


  override def wrapSubInput(io: BaseType): Unit = {
    val name = component.localNamingScope.allocateName(anonymSignalPrefix)
    declarations ++= s"  signal $name : ${emitDataType(io)};\n"
    referencesOverrides(io) = name
  }

  def emitArchitecture(): Unit = {
    for(mem <- mems){
      mem.foreachStatements(s => {
        s.foreachDrivingExpression{
          case e : BaseType =>
          case e => expressionToWrap += e
        }

        s match {
          case s : MemReadSync => {
            val name = component.localNamingScope.allocateName(anonymSignalPrefix)
            declarations ++= s"  signal $name : ${emitType(s)};\n"
            wrappedExpressionToName(s) = name
          }
          case s : MemReadAsync => {
            val name = component.localNamingScope.allocateName(anonymSignalPrefix)
            declarations ++= s"  signal $name : ${emitType(s)};\n"
            wrappedExpressionToName(s) = name
          }
          case s : MemReadWrite => {
            val name = component.localNamingScope.allocateName(anonymSignalPrefix)
            declarations ++= s"  signal $name : ${emitType(s)};\n"
            wrappedExpressionToName(s) = name
          }
          case s : MemWrite =>
        }
      })
    }


    for(output <- outputsToBufferize){
      val name = component.localNamingScope.allocateName(anonymSignalPrefix)
      declarations ++= s"  signal $name : ${emitDataType(output)}${getBaseTypeSignalInitialisation(output)};\n"
      logics ++= s"  ${emitReference(output, false)} <= $name;\n"
      referencesOverrides(output) = name
    }

    component.children.foreach(sub => sub.getAllIo.foreach(io => if(io.isOutput) {
      val name = component.localNamingScope.allocateName(anonymSignalPrefix)
      declarations ++= s"  signal $name : ${emitDataType(io)};\n"
      referencesOverrides(io) = name
    }))

    //Wrap expression which need it
    for(e <- expressionToWrap if !e.isInstanceOf[DeclarationStatement]){
      val name = component.localNamingScope.allocateName(anonymSignalPrefix)
      declarations ++= s"  signal $name : ${emitType(e)};\n"
      wrappedExpressionToName(e) = name
    }

    for(e <- expressionToWrap  if !e.isInstanceOf[DeclarationStatement]){
      logics ++= s"  ${wrappedExpressionToName(e)} <= ${emitExpressionNoWrappeForFirstOne(e)};\n"
    }


    //Flush all that mess out ^^
    emitBlackBoxComponents()
    emitAttributesDef()
    emitSignals()
    emitMems(mems)
    emitSubComponents(openSubIo)
    processes.foreach(p => {
      if(p.leafStatements.nonEmpty ) {
        p.leafStatements.head match {
          case AssignementStatement(target: DeclarationStatement, _) if subComponentInputToNotBufferize.contains(target) =>
          case _ => emitAsyncronous(p)
        }
      } else {
        emitAsyncronous(p)
      }
    })
    syncGroups.valuesIterator.foreach(emitSyncronous(component, _))

    component.dslBody.walkStatements{
      case s : TreeStatement => s.algoIncrementale = algoIdIncrementalBase
      case s  =>
    }
  }




  def emitSubComponents( openSubIo : mutable.HashSet[BaseType]): Unit = {
    for (children <- component.children) {
      val isBB = children.isInstanceOf[BlackBox]
      //      val isBBUsingULogic = isBB && children.asInstanceOf[BlackBox].isUsingULogic
      val definitionString = if (isBB) children.definitionName else s"entity work.${getOrDefault(emitedComponentRef, children, children).definitionName}"
      logics ++= s"  ${
        children.getName()
      } : $definitionString\n"



      def addULogicCast(bt: BaseType, io: String, logic: String, dir: IODirection): String = {

        //        if (isBBUsingULogic)
        //          if (dir == in) {
        //            bt match {
        //              case _: Bool => return s"      $io => std_ulogic($logic),\n"
        ////              case _: Bits => return s"      $io => std_ulogic_vector($logic),\n"
        //              case _ => return s"      $io => $logic,\n"
        //            }
        //          } else if (dir == spinal.core.out) {
        //            bt match {
        //              case _: Bool => return s"      std_logic($io) => $logic,\n"
        ////              case _: Bits => return s"      std_logic_vector($io) => $logic,\n"
        //              case _ => return s"      $io => $logic,\n"
        //            }
        //          } else SpinalError("???")
        //
        //        else
        return s"      $io => $logic,\n"
      }

      if (children.isInstanceOf[BlackBox]) {
        val bb = children.asInstanceOf[BlackBox]
        val genericFlat = bb.getGeneric.flatten

        if (genericFlat.size != 0) {
          logics ++= s"    generic map( \n"


          for (e <- genericFlat) {
            e match {
              case bt : BaseType => logics ++= addULogicCast(bt, emitReference(bt,false), emitExpression(bt.head.source), in)
              case (name : String,s: String) => logics ++= s"      ${name} => ${"\""}${s}${"\""},\n"
              case (name : String,i : Int) => logics ++= s"      ${name} => $i,\n"
              case (name : String,d: Double) => logics ++= s"      ${name} => $d,\n"
              case (name : String,boolean: Boolean) => logics ++= s"      ${name} => $boolean,\n"
              case (name : String,t: TimeNumber) => {
                val d = t.decompose
                logics ++= s"      ${name} => ${d._1} ${d._2},\n"
              }
            }
          }
          logics.setCharAt(logics.size - 2, ' ')
          logics ++= s"    )\n"
        }
      }
      logics ++= s"    port map ( \n"
      for (data <- children.getOrdredNodeIo) {
        val logic = if(openSubIo.contains(data)) "open" else emitReference(data, false) //TODO IR && false
        logics ++= addULogicCast(data, emitReferenceNoOverrides(data),logic , data.dir)
      }
      logics.setCharAt(logics.size - 2, ' ')

      logics ++= s"    );"
      logics ++= s"\n"
    }
  }




  def emitClockedProcess(emitRegsLogic : (String, StringBuilder) => Unit,
                         emitRegsInitialValue : (String, StringBuilder) => Unit,
                         b : mutable.StringBuilder,
                         clockDomain: ClockDomain,
                         withReset : Boolean): Unit ={
    val clock = component.pulledDataCache.getOrElse(clockDomain.clock, throw new Exception("???")).asInstanceOf[Bool]
    val reset = if (null == clockDomain.reset || !withReset) null else component.pulledDataCache.getOrElse(clockDomain.reset, throw new Exception("???")).asInstanceOf[Bool]
    val softReset = if (null == clockDomain.softReset || !withReset) null else component.pulledDataCache.getOrElse(clockDomain.softReset, throw new Exception("???")).asInstanceOf[Bool]
    val clockEnable = if (null == clockDomain.clockEnable) null else component.pulledDataCache.getOrElse(clockDomain.clockEnable, throw new Exception("???")).asInstanceOf[Bool]

    val asyncReset = (null != reset) && clockDomain.config.resetKind == ASYNC
    val syncReset = (null != reset) && clockDomain.config.resetKind == SYNC
    var tabLevel = 1
    def tabStr = "  " * tabLevel
    def inc = {
      tabLevel = tabLevel + 1
    }
    def dec = {
      tabLevel = tabLevel - 1
    }



    val initialStatlementsGeneration =  new StringBuilder()
    referenceSetStart()
    if(withReset) emitRegsInitialValue("      ", initialStatlementsGeneration)
    referenceSetAdd(emitReference(clock,false))

    if (asyncReset) {
      referenceSetAdd(emitReference(reset,false))
    }

    b ++= s"${tabStr}process(${referehceSetSorted.mkString(", ")})\n"
    b ++= s"${tabStr}begin\n"
    inc
    if (asyncReset) {
      b ++= s"${tabStr}if ${emitReference(reset, false)} = \'${if (clockDomain.config.resetActiveLevel == HIGH) 1 else 0}\' then\n";
      inc
      b ++= initialStatlementsGeneration
      dec
      b ++= s"${tabStr}elsif ${emitClockEdge(emitReference(clock,false), clockDomain.config.clockEdge)}"
      inc
    } else {
      b ++= s"${tabStr}if ${emitClockEdge(emitReference(clock,false), clockDomain.config.clockEdge)}"
      inc
    }
    if (clockEnable != null) {
      b ++= s"${tabStr}if ${emitReference(clockEnable,false)} = \'${if (clockDomain.config.clockEnableActiveLevel == HIGH) 1 else 0}\' then\n"
      inc
    }

    if (syncReset || softReset != null) {
      var condList = ArrayBuffer[String]()
      if(syncReset) condList += s"${emitReference(reset,false)} = \'${if (clockDomain.config.resetActiveLevel == HIGH) 1 else 0}\'"
      if(softReset != null) condList += s"${emitReference(softReset,false)} = \'${if (clockDomain.config.softResetActiveLevel == HIGH) 1 else 0}\'"

      b ++= s"${tabStr}if ${condList.reduce(_ + " or " + _)} then\n"
      inc
      b ++= initialStatlementsGeneration
      dec
      b ++= s"${tabStr}else\n"
      inc
      emitRegsLogic(tabStr,b)
      dec
      b ++= s"${tabStr}end if;\n"
      dec
    } else {
      emitRegsLogic(tabStr,b)
      dec
    }

    while (tabLevel != 1) {
      b ++= s"${tabStr}end if;\n"
      dec
    }
    b ++= s"${tabStr}end process;\n"
    dec
    b ++= s"${tabStr}\n"






  }





  def emitSyncronous(component : Component, group: SyncGroup): Unit = {
    import group._
    def withReset = hasInit


    def emitRegsInitialValue(tab: String, b : StringBuilder): Unit = {
      emitLeafStatements(group.initStatements, 0, group.scope, "<=", b , tab)
    }
    def emitRegsLogic(tab: String, b : StringBuilder): Unit = {
      emitLeafStatements(group.dataStatements, 0, group.scope, "<=", b , tab)
    }


    emitClockedProcess(
      emitRegsLogic = emitRegsLogic,
      emitRegsInitialValue = emitRegsInitialValue,
      b = logics,
      clockDomain = group.clockDomain,
      withReset = withReset
    )
  }


  def emitAsyncronous(process: AsyncProcess): Unit = {
    process match {
      case _ if process.leafStatements.size == 1 && process.leafStatements.head.parentScope == process.nameableTargets.head.rootScopeStatement => process.leafStatements.head match {
        case s : AssignementStatement =>
          logics ++= emitAssignement(s, "  ", "<=")
      }
      case _ => {
        val tmp = new StringBuilder
        referenceSetStart()
        emitLeafStatements(process.leafStatements, 0, process.scope, "<=", tmp, "    ")

        if (referehceSetSorted.nonEmpty) {
          logics ++= s"  process(${referehceSetSorted.mkString(",")})\n";
          logics ++= "  begin\n"
          logics ++= tmp.toString()
          logics ++= "  end process;\n\n"
        } else {
          //assert(process.nameableTargets.size == 1)
          for(node <- process.nameableTargets) node match {
            case node : BaseType => {
              val funcName = "zz_" + emitReference(node, false)
              declarations ++= s"  function $funcName return ${emitDataType(node, false)} is\n"
              declarations ++= s"    variable ${emitReference(node, false)} : ${emitDataType(node, true)};\n"
              declarations ++= s"  begin\n"
              val statements = ArrayBuffer[LeafStatement]()
              node.foreachStatements(s => statements += s.asInstanceOf[LeafStatement])
              emitLeafStatements(statements, 0, process.scope, ":=", declarations, "    ")
              declarations ++= s"    return ${emitReference(node, false)};\n"
              declarations ++= s"  end function;\n"
              logics ++= s"  ${emitReference(node, false)} <= ${funcName};\n"
            }
          }
        }
      }
    }
  }


  def emitLeafStatements(statements : ArrayBuffer[LeafStatement], statementIndexInit : Int, scope : ScopeStatement, assignementKind : String, b : StringBuilder, tab : String): Int ={
    var statementIndex = statementIndexInit
    var lastWhen : WhenStatement = null
    def closeSubs() : Unit = {
      if(lastWhen != null) {
        b ++= s"${tab}end if;\n"
        lastWhen = null
      }
    }
    while(statementIndex < statements.length){
      val leaf = statements(statementIndex)
      val statement = leaf
      val targetScope = statement.parentScope
      if(targetScope == scope){
        closeSubs()
        statement match {
          case assignement : AssignementStatement => b ++= emitAssignement(assignement,tab, assignementKind)
          case assertStatement : AssertStatement => {
            val cond = emitExpression(assertStatement.cond)
            require(assertStatement.message.size == 0 || (assertStatement.message.size == 1 && assertStatement.message(0).isInstanceOf[String]))
            val message = if(assertStatement.message.size == 1) s"""report "${assertStatement.message(0)}" """ else ""
            val severity = "severity " +  (assertStatement.severity match{
              case `NOTE`     => "NOTE"
              case `WARNING`  => "WARNING"
              case `ERROR`    => "ERROR"
              case `FAILURE`  => "FAILURE"
            })
            b ++= s"${tab}assert $cond = '1' $message $severity;\n"
          }
        }

        statementIndex += 1
      } else {
        var scopePtr = targetScope
        while(scopePtr.parentStatement != null && scopePtr.parentStatement.parentScope != scope){
          scopePtr = scopePtr.parentStatement.parentScope
        }
        if(scopePtr.parentStatement == null) {
          closeSubs()
          return statementIndex
        }
        val treeStatement = scopePtr.parentStatement
        if(treeStatement != lastWhen)
          closeSubs()
        treeStatement match {
          case treeStatement : WhenStatement => {
            if(scopePtr == treeStatement.whenTrue){
              b ++= s"${tab}if ${emitExpression(treeStatement.cond)} = '1' then\n"
            } else if(lastWhen == treeStatement){
              //              if(scopePtr.sizeIsOne && scopePtr.head.isInstanceOf[WhenStatement]){
              //                b ++= s"${tab}if ${emitExpression(treeStatement.cond)} = '1' then\n"
              //              } else {
              b ++= s"${tab}else\n"
              //              }
            } else {
              b ++= s"${tab}if ${emitExpression(treeStatement.cond)} = '0' then\n"
            }
            lastWhen = treeStatement
            statementIndex = emitLeafStatements(statements,statementIndex, scopePtr, assignementKind,b, tab + "  ")
          }
          case switchStatement : SwitchStatement => {
            val isPure = switchStatement.elements.foldLeft(true)((carry, element) => carry && !(element.keys.exists(!_.isInstanceOf[Literal])))
            //Generate the code
            def findSwitchScopeRec(scope: ScopeStatement): ScopeStatement = scope.parentStatement match {
              case null => null
              case s if s == switchStatement => scope
              case s => findSwitchScopeRec(s.parentScope)
            }
            def findSwitchScope() : ScopeStatement = {
              if(statementIndex < statements.length)
                findSwitchScopeRec(statements(statementIndex).parentScope)
              else
                null
            }
            var nextScope = findSwitchScope()

            //Generate the code
            if(isPure) {
              def emitIsCond(that : Expression): String = that match {
                case lit : BitVectorLiteral => '"' + lit.getBitsStringOnNoPoison(lit.getWidth) + '"'
                case lit : BoolLiteral => if(lit.value) "'1'" else "'0'"
                case lit : EnumLiteral[_] => emitEnumLiteral(lit.enum, lit.encoding)
              }

              b ++= s"${tab}case ${emitExpression(switchStatement.value)} is\n"
              switchStatement.elements.foreach(element =>  {
                b ++= s"${tab}  when ${element.keys.map(e => emitIsCond(e)).mkString(" | ")} =>\n"
                if(nextScope == element.scopeStatement) {
                  statementIndex = emitLeafStatements(statements, statementIndex, element.scopeStatement, assignementKind, b, tab + "    ")
                  nextScope = findSwitchScope()
                }
              })

              b ++= s"${tab}  when others =>\n"
              if (switchStatement.defaultScope != null) {
                if(nextScope == switchStatement.defaultScope) {
                  statementIndex = emitLeafStatements(statements, statementIndex, switchStatement.defaultScope, assignementKind, b, tab + "    ")
                  nextScope = findSwitchScope()
                }
              }
              b ++= s"${tab}end case;\n"
            } else {
              def emitIsCond(that : Expression): String = that match {
                case that : SwitchStatementKeyBool => s"(${emitExpression(that.cond)} = '1')"
                case that => s"(${emitExpression(switchStatement.value)} = ${emitExpression(that)})"
              }
              var index = 0
              switchStatement.elements.foreach(element =>  {
                b ++= s"${tab}${if(index == 0) "if" else "elsif"} ${element.keys.map(e => emitIsCond(e)).mkString(" or ")} then\n"
                if(nextScope == element.scopeStatement) {
                  statementIndex = emitLeafStatements(statements, statementIndex, element.scopeStatement, assignementKind, b, tab + "    ")
                  nextScope = findSwitchScope()
                }
                index += 1
              })
              if(switchStatement.defaultScope  != null){
                b ++= s"${tab}else\n"
                if(nextScope == switchStatement.defaultScope) {
                  statementIndex = emitLeafStatements(statements, statementIndex, switchStatement.defaultScope, assignementKind, b, tab + "    ")
                  nextScope = findSwitchScope()
                }
              }
              b ++= s"${tab}end if;\n"
            }
          }
        }
      }
    }
    closeSubs()
    return statementIndex
  }


  def emitAssignement(assignement : AssignementStatement, tab: String, assignementKind: String): String = {
    assignement match {
      case _ => {
        s"$tab${emitAssignedExpression(assignement.target)} ${assignementKind} ${emitExpression(assignement.source)};\n"
      }
    }
  }

  def referenceSetStart(): Unit ={
    _referenceSetEnabled = true
    _referenceSet.clear()
  }

  def referenceSetStop(): Unit ={
    _referenceSetEnabled = false
    _referenceSet.clear()
  }

  def referenceSetAdd(str : String): Unit ={
    if(_referenceSetEnabled) {
      _referenceSet.add(str)
    }
  }

  def referehceSetSorted() = _referenceSet

  var _referenceSetEnabled = false
  val _referenceSet = mutable.LinkedHashSet[String]()

  def emitReference(that : DeclarationStatement, sensitive : Boolean): String ={
    val name = referencesOverrides.getOrElse(that, that.getNameElseThrow) match {
      case x : String => x
      case x : DeclarationStatement => emitReference(x,false)
    }
    if(sensitive) referenceSetAdd(name)
    name
  }

  def emitReferenceNoOverrides(that : DeclarationStatement): String ={
    that.getNameElseThrow
  }

  def emitAssignedExpression(that : Expression): String = that match{
    case that : BaseType => emitReference(that, false)
    case that : BitAssignmentFixed => s"${emitReference(that.out, false)}(${that.bitId})"
    case that : BitAssignmentFloating => s"${emitReference(that.out, false)}(to_integer(${emitExpression(that.bitId)}))"
    case that : RangedAssignmentFixed => s"${emitReference(that.out, false)}(${that.hi} downto ${that.lo})"
    case that : RangedAssignmentFloating => s"${emitReference(that.out, false)}(${that.bitCount - 1} + to_integer(${emitExpression(that.offset)}) downto to_integer(${emitExpression(that.offset)}))"
  }

  def emitExpression(that : Expression) : String = {
    wrappedExpressionToName.get(that) match {
      case Some(name) => {
        referenceSetAdd(name)
        name
      }
      case None => dispatchExpression(that)
    }
  }

  def emitExpressionNoWrappeForFirstOne(that : Expression) : String = {
    dispatchExpression(that)
  }

  def emitAttributesDef(): Unit = {
    val map = mutable.Map[String, Attribute]()

    component.dslBody.walkStatements{
      case s : SpinalTagReady =>
        for (attribute <- s.instanceAttributes(Language.VHDL)) {
          val mAttribute = map.getOrElseUpdate(attribute.getName, attribute)
          if (!mAttribute.sameType(attribute)) SpinalError(s"There is some attributes with different nature (${attribute} and ${mAttribute} at\n${component}})")
        }
      case s =>
    }

    for (attribute <- map.values) {
      val typeString = attribute match {
        case _: AttributeString => "string"
        case _: AttributeFlag => "boolean"
      }
      declarations ++= s"  attribute ${attribute.getName} : $typeString;\n"
    }

    declarations ++= "\n"
  }


  def getBaseTypeSignalInitialisation(signal : BaseType) : String = {
    if(signal.isReg){
      if(signal.clockDomain.config.resetKind == BOOT && signal.hasInit) {
        var initStatement : AssignementStatement = null
        var needFunc = false
        signal.foreachStatements {
          case s : InitAssignementStatement if s.source.isInstanceOf[Literal] =>
            if(initStatement != null)
              needFunc = true
            initStatement = s
          case s =>
        }

        if(needFunc)
          ???
        else {
          assert(initStatement.parentScope == signal.parentScope)
          return " := " + emitExpressionNoWrappeForFirstOne(initStatement.source)
        }
      }else if (signal.hasTag(randomBoot)) {
        return signal match {
          case b: Bool => " := " + {
            if (Random.nextBoolean()) "'1'" else "'0'"
          }
          case bv: BitVector => {
            val rand = BigInt(bv.getWidth, Random).toString(2)
            " := \"" + "0" * (bv.getWidth - rand.length) + rand + "\""
          }
          case e: SpinalEnumCraft[_] => {
            val vec = e.spinalEnum.elements.toVector
            val rand = vec(Random.nextInt(vec.size))
            " := " + emitEnumLiteral(rand, e.getEncoding)
          }
        }
      }
    }
    ""
  }



  var memBitsMaskKind : MemBitsMaskKind = MULTIPLE_RAM


  def emitSignals(): Unit = {
    component.dslBody.walkDeclarations(node => {
      node match {
        case signal: BaseType => {
          if (!signal.isIo) {
            declarations ++= s"  signal ${emitReference(signal, false)} : ${emitDataType(signal)}${getBaseTypeSignalInitialisation(signal)};\n"
          }


          emitAttributes(signal,signal.instanceAttributes(Language.VHDL), "signal", declarations)
        }
        case mem: Mem[_] =>
      }
    })
  }

  def emitMems(mems: ArrayBuffer[Mem[_]]) : Unit = {
    for(mem <- mems) emitMem(mem)
  }

  def emitMem(mem: Mem[_]): Unit ={
    def emitDataType(mem: Mem[_], constrained: Boolean = true) =  s"${emitReference(mem, constrained)}_type"

    //ret ++= emitSignal(mem, mem);
    val symbolWidth = mem.getMemSymbolWidth()
    val symbolCount = mem.getMemSymbolCount

    val initAssignementBuilder = for(i <- 0 until symbolCount) yield {
      val builder = new StringBuilder()
      val mask = (BigInt(1) << symbolWidth)-1
      if (mem.initialContent != null) {
        builder ++= " := ("

        var first = true
        for ((value, index) <- mem.initialContent.zipWithIndex) {
          if (!first)
            builder ++= ","
          else
            first = false

          if ((index & 15) == 0) {
            builder ++= "\n     "
          }

          val unfilledValue = ((value>>(i*symbolWidth)) & mask).toString(2)
          val filledValue = "0" * (symbolWidth-unfilledValue.length) + unfilledValue
          builder ++= "\"" + filledValue + "\""
        }

        builder ++= ")"
      }else if(mem.hasTag(randomBoot)){
        builder ++= " := (others => (others => '1'))"
      }
      builder
    }


    if(memBitsMaskKind == MULTIPLE_RAM && symbolCount != 1) {
      //if(mem.initialContent != null) SpinalError("Memory with multiple symbol per line + initial contant are not suported currently")

      declarations ++= s"  type ${emitReference(mem,false)}_type is array (0 to ${mem.wordCount - 1}) of std_logic_vector(${symbolWidth - 1} downto 0);\n"
      for(i <- 0 until symbolCount) {
        val postfix = "_symbol" + i
        declarations ++= s"  signal ${emitReference(mem,false)}$postfix : ${emitDataType(mem)}${initAssignementBuilder(i).toString()};\n"
        emitAttributes(mem,mem.instanceAttributes(Language.VHDL), "signal", declarations,postfix = postfix)
      }
    }else{
      declarations ++= s"  type ${emitReference(mem,false)}_type is array (0 to ${mem.wordCount - 1}) of std_logic_vector(${mem.getWidth - 1} downto 0);\n"
      declarations ++= s"  signal ${emitReference(mem,false)} : ${emitDataType(mem)}${initAssignementBuilder.head.toString()};\n"
      emitAttributes(mem, mem.instanceAttributes(Language.VHDL), "signal", declarations)
    }


    def emitWrite(b : StringBuilder, mem : Mem[_], writeEnable : String, address : Expression, data : Expression, mask : Expression with WidthProvider, symbolCount : Int, bitPerSymbole : Int, tab: String) : Unit = {
      if(memBitsMaskKind == SINGLE_RAM || symbolCount == 1) {
        val ramAssign = s"$tab${emitReference(mem, false)}(to_integer(${emitExpression(address)})) <= ${emitExpression(data)};\n"
        if (writeEnable != null) {
          b ++= s"${tab}if ${writeEnable} then\n  "
          b ++= ramAssign
          b ++= s"${tab}end if;\n"
        } else {
          b ++= ramAssign
        }
      } else {
        def maskCount = mask.getWidth
        for(i <- 0 until symbolCount) {
          var conds = if(writeEnable != null) List(writeEnable) else Nil
          val range = s"(${(i + 1) * bitPerSymbole - 1} downto ${i * bitPerSymbole})"
          if(mask != null)
            conds =  s"${emitExpression(mask)}($i) = '1'" :: conds
          if(conds.nonEmpty)
            b ++= s"${tab}if ${conds.mkString(" and ")} then\n"

          if (memBitsMaskKind == SINGLE_RAM || symbolCount == 1)
            b ++= s"$tab  ${emitReference(mem, false)}(to_integer(${emitExpression(address)}))$range <= ${emitExpression(data)}$range;\n"
          else
            b ++= s"$tab  ${emitReference(mem, false)}_symbol${i}(to_integer(${emitExpression(address)})) <= ${emitExpression(data)}$range;\n"
          if(conds.nonEmpty)
            b ++= s"${tab}end if;\n"
        }
      }
    }

    def emitRead(b : StringBuilder, mem : Mem[_], address : Expression, target : Expression, tab: String) = {
      val ramRead = {
        val symbolCount = mem.getMemSymbolCount
        if(memBitsMaskKind == SINGLE_RAM || symbolCount == 1)
          b ++= s"$tab${emitExpression(target)} <= ${emitReference(mem, false)}(to_integer(${emitExpression(address)}));\n"
        else{
          val symboleReadDataNames = for(i <- 0 until symbolCount) yield {
            val symboleReadDataName = component.localNamingScope.allocateName(anonymSignalPrefix)
            declarations ++= s"  signal $symboleReadDataName : std_logic_vector(${mem.getMemSymbolWidth()-1} downto 0);\n"
            b ++= s"$tab$symboleReadDataName <= ${emitReference(mem,false)}_symbol$i(to_integer(${emitExpression(address)}));\n"
            symboleReadDataName
          }


          logics ++= s"  process (${symboleReadDataNames.mkString(", " )})\n"
          logics ++= s"  begin\n"
          logics ++= s"    ${emitExpression(target)} <= ${symboleReadDataNames.reverse.mkString(" & " )};\n"
          logics ++= s"  end process;\n"
        }
//          (0 until symbolCount).reverse.map(i => (s"${emitReference(mem, false)}_symbol$i(to_integer(${emitExpression(address)}))")).reduce(_ + " & " + _)
      }


    }

    def emitPort(port : MemPortStatement,tab : String, b : mutable.StringBuilder) : Unit = port match {
      case memWrite: MemWrite => {
        if(memWrite.aspectRatio != 1) SpinalError(s"VHDL backend can't emit ${memWrite.mem} because of its mixed width ports")
        emitWrite(b, memWrite.mem,  if (memWrite.writeEnable != null) emitExpression(memWrite.writeEnable) + " = '1'" else null.asInstanceOf[String], memWrite.address, memWrite.data, memWrite.mask, memWrite.mem.getMemSymbolCount, memWrite.mem.getMemSymbolWidth(), tab)
      }
      case memReadSync: MemReadSync => {
        if(memReadSync.aspectRatio != 1) SpinalError(s"VHDL backend can't emit ${memReadSync.mem} because of its mixed width ports")
        if(memReadSync.readUnderWrite == writeFirst) SpinalError(s"Can't translate a memReadSync with writeFirst into VHDL $memReadSync")
        if(memReadSync.readUnderWrite == dontCare) SpinalWarning(s"memReadSync with dontCare is as readFirst into VHDL $memReadSync")
        if(memReadSync.readEnable != null) {
          b ++= s"${tab}if ${emitExpression(memReadSync.readEnable)} = '1' then\n"
          emitRead(b, memReadSync.mem, memReadSync.address, memReadSync, tab + "  ")
          b ++= s"${tab}end if;\n"
        } else {
          emitRead(b, memReadSync.mem, memReadSync.address, memReadSync, tab)
        }
      }

      case memReadWrite : MemReadWrite => {
        if(memReadWrite.aspectRatio != 1) SpinalError(s"VHDL backend can't emit ${memReadWrite.mem} because of its mixed width ports")
        //                    if (memReadWrite.readUnderWrite == writeFirst) SpinalError(s"Can't translate a MemWriteOrRead with writeFirst into VHDL $memReadWrite")
        //                    if (memReadWrite.readUnderWrite == dontCare) SpinalWarning(s"MemWriteOrRead with dontCare is as readFirst into VHDL $memReadWrite")

        val symbolCount = memReadWrite.mem.getMemSymbolCount
        emitWrite(b, memReadWrite.mem,s"${emitExpression(memReadWrite.chipSelect)} = '1' and ${emitExpression(memReadWrite.writeEnable)} = '1'", memReadWrite.address, memReadWrite.data, memReadWrite.mask, memReadWrite.mem.getMemSymbolCount, memReadWrite.mem.getMemSymbolWidth(),tab)
        b ++= s"${tab}if ${emitExpression(memReadWrite.chipSelect)} = '1' then\n"
        emitRead(b, memReadWrite.mem, memReadWrite.address, memReadWrite, tab + "  ")
        b ++= s"${tab}end if;\n"
      }
    }


    val cdTasks = mutable.LinkedHashMap[ClockDomain, ArrayBuffer[MemPortStatement]]()
    mem.foreachStatements{
      case port : MemWrite =>
        cdTasks.getOrElseUpdate(port.clockDomain, ArrayBuffer[MemPortStatement]()) += port
      case port : MemReadSync =>
        if(port.readUnderWrite == readFirst)
          cdTasks.getOrElseUpdate(port.clockDomain, ArrayBuffer[MemPortStatement]()) += port
      case port : MemReadWrite =>
        cdTasks.getOrElseUpdate(port.clockDomain, ArrayBuffer[MemPortStatement]()) += port
      case port : MemReadAsync =>
    }


    val tmpBuilder = new StringBuilder()
    for((cd, ports) <- cdTasks){
      def syncLogic(tab : String, b : StringBuilder): Unit ={
        ports.foreach{
          case port : MemWrite => emitPort(port, tab, b)
          case port : MemReadSync => emitPort(port, tab, b)
          case port : MemReadWrite => emitPort(port, tab, b)
        }
      }

      emitClockedProcess(syncLogic,null,tmpBuilder, cd, false)
    }

    mem.foreachStatements{
      case port : MemWrite =>
      case port : MemReadWrite =>
      case port : MemReadSync =>
        if(port.readUnderWrite == dontCare)
          emitClockedProcess(emitPort(port,_,_),null,tmpBuilder, port.clockDomain, false)
      case port : MemReadAsync =>
        if(port.aspectRatio != 1) SpinalError(s"VHDL backend can't emit ${port.mem} because of its mixed width ports")
        if (port.readUnderWrite == dontCare) SpinalWarning(s"memReadAsync with dontCare is as writeFirst into VHDL")
        val symbolCount = port.mem.getMemSymbolCount
        if(memBitsMaskKind == SINGLE_RAM || symbolCount == 1)
          tmpBuilder ++= s"  ${emitExpression(port)} <= ${emitReference(port.mem, false)}(to_integer(${emitExpression(port.address)}));\n"
        else
          (0 until symbolCount).foreach(i => tmpBuilder  ++= (s"  ${emitExpression(port)}(${(i + 1)*symbolWidth - 1} downto ${i*symbolWidth}) <= ${emitReference(port.mem, false)}_symbol$i(to_integer(${emitExpression(port.address)}));\n"))

    }


    logics ++= tmpBuilder

  }


  def emitAttributes(node : DeclarationStatement,attributes: Iterable[Attribute], vhdlType: String, ret: StringBuilder,postfix : String = ""): Unit = {
    for (attribute <- attributes){
      val value = attribute match {
        case attribute: AttributeString => "\"" + attribute.value + "\""
        case attribute: AttributeFlag => "true"
      }

      ret ++= s"  attribute ${attribute.getName} of ${emitReference(node, false)}: signal is $value;\n"
    }
  }

  def emitBlackBoxComponents(): Unit = {
    val emited = mutable.Set[String]()
    for (c <- component.children) c match {
      case blackBox: BlackBox => {
        if (!emited.contains(blackBox.definitionName)) {
          emited += blackBox.definitionName
          emitBlackBoxComponent(blackBox)
        }
      }
      case _ =>
    }
  }

  def blackBoxRemplaceULogic(b: BlackBox, str: String): String = {
    if (b.isUsingULogic)
      str.replace("std_logic", "std_ulogic")
    else
      str
  }

  def emitBlackBoxComponent(component: BlackBox): Unit = {
    declarations ++= s"\n  component ${component.definitionName} is\n"
    val genericFlat = component.getGeneric.flatten
    if (genericFlat.size != 0) {
      declarations ++= s"    generic( \n"
      for (e <- genericFlat) {
        e match {
          case baseType: BaseType => declarations ++= s"      ${emitReference(baseType, false)} : ${blackBoxRemplaceULogic(component, emitDataType(baseType, true))};\n"
          case (name : String,s: String) => declarations ++= s"      $name : string;\n"
          case (name : String,i : Int) => declarations ++= s"      $name : integer;\n"
          case (name : String,d: Double) => declarations ++= s"      $name : real;\n"
          case (name : String,boolean: Boolean) => declarations ++= s"      $name : boolean;\n"
          case (name : String,t: TimeNumber) => declarations ++= s"      $name : time;\n"
        }
      }

      declarations.setCharAt(declarations.size - 2, ' ')
      declarations ++= s"    );\n"
    }
    declarations ++= s"    port( \n"
    component.getOrdredNodeIo.foreach(_ match {
      case baseType: BaseType => {
        if (baseType.isIo) {
          declarations ++= s"      ${baseType.getName()} : ${emitDirection(baseType)} ${blackBoxRemplaceULogic(component, emitDataType(baseType, true))};\n"
        }
      }
      case _ =>
    })
    declarations.setCharAt(declarations.size - 2, ' ')
    declarations ++= s"    );\n"
    declarations ++= s"  end component;\n"
    declarations ++= s"  \n"
  }




  def refImpl(e: BaseType): String = emitReference(e, true)

  def operatorImplAsBinaryOperator(vhd: String)(e: BinaryOperator): String = {
    s"(${emitExpression(e.left)} $vhd ${emitExpression(e.right)})"
  }


  def operatorImplAsBinaryOperatorStdCast(vhd: String)(e: BinaryOperator): String = {
    s"pkg_toStdLogic(${emitExpression(e.left)} $vhd ${emitExpression(e.right)})"
  }

  def boolLiteralImpl(e: BoolLiteral) : String = s"pkg_toStdLogic(${e.value})"

  def moduloImpl(e: Operator.BitVector.Mod): String = {
    s"resize(${emitExpression(e.left)} mod ${emitExpression(e.right)},${e.getWidth})"
  }

  def operatorImplAsUnaryOperator(vhd: String)(e: UnaryOperator): String = {
    s"($vhd ${emitExpression(e.source)})"
  }

  def opImplAsCast(vhd: String)(e: Cast): String = {
    s"$vhd(${emitExpression(e.input)})"
  }

  def binaryOperatorImplAsFunction(vhd: String)(e: BinaryOperator): String = {
    s"$vhd(${emitExpression(e.left)},${emitExpression(e.right)})"
  }


  def muxImplAsFunction(vhd: String)(e: Multiplexer): String = {
    s"$vhd(${emitExpression(e.cond)},${emitExpression(e.whenTrue)},${emitExpression(e.whenFalse)})"
  }


  def shiftRightByIntImpl(e: Operator.BitVector.ShiftRightByInt): String = {
    s"pkg_shiftRight(${emitExpression(e.source)},${e.shift})"
  }

  def shiftLeftByIntImpl(e: Operator.BitVector.ShiftLeftByInt): String = {
    s"pkg_shiftLeft(${emitExpression(e.source)},${e.shift})"
  }

  def shiftRightByIntFixedWidthImpl(e: Operator.BitVector.ShiftRightByIntFixedWidth): String = {
    s"shift_right(${emitExpression(e.source)},${e.shift})"
  }

  def shiftLeftByIntFixedWidthImpl(e: Operator.BitVector.ShiftLeftByIntFixedWidth): String = {
    s"shift_left(${emitExpression(e.source)},${e.shift})"
  }

  def shiftRightBitsByIntFixedWidthImpl(e: Operator.BitVector.ShiftRightByIntFixedWidth): String = {
    s"std_logic_vector(shift_right(unsigned(${emitExpression(e.source)}),${e.shift}))"
  }

  def shiftLeftBitsByIntFixedWidthImpl(e: Operator.BitVector.ShiftLeftByIntFixedWidth): String = {
    s"std_logic_vector(shift_left(unsigned(${emitExpression(e.source)}),${e.shift}))"
  }


  def shiftLeftByUIntFixedWidthImpl(e: Operator.BitVector.ShiftLeftByUIntFixedWidth): String = {
    s"shift_left(${emitExpression(e.left)},to_integer(${emitExpression(e.right)}))"
  }

  def shiftLeftBitsByUIntFixedWidthImpl(e: Operator.BitVector.ShiftLeftByUIntFixedWidth): String = {
    s"std_logic_vector(shift_left(unsigned(${emitExpression(e.left)}),to_integer(${emitExpression(e.right)})))"
  }


  def shiftSIntLeftByUInt(e: Operator.SInt.ShiftLeftByUInt): String = {
    s"pkg_shiftLeft(${emitExpression(e.left)}, ${emitExpression(e.right)}, ${e.getWidth})"
  }


  def resizeFunction(vhdlFunc : String)(e: Resize): String = {
    s"pkg_resize(${emitExpression(e.input)},${e.size})"
  }


  def emitBitsLiteral(e : BitsLiteral) : String = {
    s"pkg_stdLogicVector(${'\"'}${e.getBitsStringOn(e.getWidth, 'X')}${'\"'})"
  }

  def emitUIntLiteral(e : UIntLiteral) : String = {
    s"pkg_unsigned(${'\"'}${e.getBitsStringOn(e.getWidth, 'X')}${'\"'})"
  }

  def emitSIntLiteral(e : SIntLiteral) : String = {
    s"pkg_signed(${'\"'}${e.getBitsStringOn(e.getWidth, 'X')}${'\"'})"
  }

  def emitEnumLiteralWrap(e : EnumLiteral[_  <: SpinalEnum]) : String = {
    emitEnumLiteral(e.enum, e.encoding)
  }

  def enumEgualsImpl(eguals: Boolean)(e: BinaryOperator with EnumEncoded): String = {
    val enumDef = e.getDefinition
    val encoding = e.getEncoding
    encoding match {
      //  case `binaryOneHot` => s"pkg_toStdLogic((${emitExpression(binOp.left)} and ${emitExpression(binOp.right)}) ${if (eguals) "/=" else "="} ${'"' + "0" * encoding.getWidth(enumDef) + '"'})"
      case _ => s"pkg_toStdLogic(${emitExpression(e.left)} ${if (eguals) "=" else "/="} ${emitExpression(e.right)})"
    }
  }


  def operatorImplAsBitsToEnum(e: CastBitsToEnum): String = {
    val enumDef = e.getDefinition
    val encoding = e.encoding

    if (!encoding.isNative) {
      emitExpression(e.input)
    } else {
      s"pkg_to${enumDef.getName()}_${encoding.getName()}(${emitExpression(e.input)})"
    }
  }

  def operatorImplAsEnumToBits(e: CastEnumToBits): String = {
    val enumDef = e.input.getDefinition
    val encoding = e.input.getEncoding

    if (!encoding.isNative) {
      emitExpression(e.input)
    } else {
      s"pkg_toStdLogicVector_${encoding.getName()}(${emitExpression(e.input)})"
    }
  }

  def operatorImplAsEnumToEnum(e: CastEnumToEnum): String = {
    val enumDefSrc = e.input.getDefinition
    val encodingSrc = e.input.getEncoding
    val enumDefDst = e.getDefinition
    val encodingDst = e.getEncoding

    if (encodingDst.isNative && encodingSrc.isNative)
      emitExpression(e.input)
    else {
      s"${getReEncodingFuntion(enumDefDst, encodingSrc,encodingDst)}(${emitExpression(e.input)})"
    }
  }


  def emitEnumPoison(e : Expression) : String = {
    val dc = e.asInstanceOf[EnumPoison]
    if(dc.encoding.isNative)
      dc.enum.elements.head.getName()
    else
      s"(${'"'}${"X" * dc.encoding.getWidth(dc.enum)}${'"'})"
  }




  def accessBoolFixed(e: BitVectorBitAccessFixed): String = {
    s"pkg_extract(${emitExpression(e.source)},${e.bitId})"
  }

  def accessBoolFloating(e: BitVectorBitAccessFloating): String = {
    s"pkg_extract(${emitExpression(e.source)},to_integer(${emitExpression(e.bitId)}))"
  }

  def accessBitVectorFixed(e: BitVectorRangedAccessFixed): String = {
    s"pkg_extract(${emitExpression(e.source)},${e.hi},${e.lo})"
  }

  def accessBitVectorFloating(e: BitVectorRangedAccessFloating): String = {
    s"pkg_extract(${emitExpression(e.source)},${emitExpression(e.offset)},${e.size})"
  }


  def dispatchExpression(e : Expression) :  String = e match {
    case  e : BaseType => refImpl(e)

    case  e : BoolLiteral => boolLiteralImpl(e)
    case  e : BitsLiteral => emitBitsLiteral(e)
    case  e : UIntLiteral => emitUIntLiteral(e)
    case  e : SIntLiteral => emitSIntLiteral(e)
    case  e : EnumLiteral[_] => emitEnumLiteralWrap(e)


    case  e : BoolPoison => "'X'"
    case  e : EnumPoison => emitEnumPoison(e)

    //unsigned
    case  e : Operator.UInt.Add => operatorImplAsBinaryOperator("+")(e)
    case  e : Operator.UInt.Sub => operatorImplAsBinaryOperator("-")(e)
    case  e : Operator.UInt.Mul => operatorImplAsBinaryOperator("*")(e)
    case  e : Operator.UInt.Div => operatorImplAsBinaryOperator("/")(e)
    case  e : Operator.UInt.Mod => moduloImpl(e)

    case  e : Operator.UInt.Or => operatorImplAsBinaryOperator("or")(e)
    case  e : Operator.UInt.And => operatorImplAsBinaryOperator("and")(e)
    case  e : Operator.UInt.Xor => operatorImplAsBinaryOperator("xor")(e)
    case  e : Operator.UInt.Not =>  operatorImplAsUnaryOperator("not")(e)

    case  e : Operator.UInt.Equal => operatorImplAsBinaryOperatorStdCast("=")(e)
    case  e : Operator.UInt.NotEqual => operatorImplAsBinaryOperatorStdCast("/=")(e)
    case  e : Operator.UInt.Smaller =>  operatorImplAsBinaryOperatorStdCast("<")(e)
    case  e : Operator.UInt.SmallerOrEqual => operatorImplAsBinaryOperatorStdCast("<=")(e)


    case  e : Operator.UInt.ShiftRightByInt => shiftRightByIntImpl(e)
    case  e : Operator.UInt.ShiftLeftByInt => shiftLeftByIntImpl(e)
    case  e : Operator.UInt.ShiftRightByUInt => binaryOperatorImplAsFunction("pkg_shiftRight")(e)
    case  e : Operator.UInt.ShiftLeftByUInt => binaryOperatorImplAsFunction("pkg_shiftLeft")(e)
    case  e : Operator.UInt.ShiftRightByIntFixedWidth =>  shiftRightByIntFixedWidthImpl(e)
    case  e : Operator.UInt.ShiftLeftByIntFixedWidth =>  shiftLeftByIntFixedWidthImpl(e)
    case  e : Operator.UInt.ShiftLeftByUIntFixedWidth =>  shiftLeftByUIntFixedWidthImpl(e)


    //signed
    case  e : Operator.SInt.Add => operatorImplAsBinaryOperator("+")(e)
    case  e : Operator.SInt.Sub => operatorImplAsBinaryOperator("-")(e)
    case  e : Operator.SInt.Mul => operatorImplAsBinaryOperator("*")(e)
    case  e : Operator.SInt.Div => operatorImplAsBinaryOperator("/")(e)
    case  e : Operator.SInt.Mod => moduloImpl(e)

    case  e : Operator.SInt.Or => operatorImplAsBinaryOperator("or")(e)
    case  e : Operator.SInt.And => operatorImplAsBinaryOperator("and")(e)
    case  e : Operator.SInt.Xor => operatorImplAsBinaryOperator("xor")(e)
    case  e : Operator.SInt.Not =>  operatorImplAsUnaryOperator("not")(e)
    case  e : Operator.SInt.Minus => operatorImplAsUnaryOperator("-")(e)

    case  e : Operator.SInt.Equal => operatorImplAsBinaryOperatorStdCast("=")(e)
    case  e : Operator.SInt.NotEqual => operatorImplAsBinaryOperatorStdCast("/=")(e)
    case  e : Operator.SInt.Smaller =>  operatorImplAsBinaryOperatorStdCast("<")(e)
    case  e : Operator.SInt.SmallerOrEqual => operatorImplAsBinaryOperatorStdCast("<=")(e)


    case  e : Operator.SInt.ShiftRightByInt => shiftRightByIntImpl(e)
    case  e : Operator.SInt.ShiftLeftByInt => shiftLeftByIntImpl(e)
    case  e : Operator.SInt.ShiftRightByUInt => binaryOperatorImplAsFunction("pkg_shiftRight")(e)
    case  e : Operator.SInt.ShiftLeftByUInt => shiftSIntLeftByUInt(e)
    case  e : Operator.SInt.ShiftRightByIntFixedWidth =>  shiftRightByIntFixedWidthImpl(e)
    case  e : Operator.SInt.ShiftLeftByIntFixedWidth =>  shiftLeftByIntFixedWidthImpl(e)
    case  e : Operator.SInt.ShiftLeftByUIntFixedWidth =>  shiftLeftByUIntFixedWidthImpl(e)


    //bits
    case  e : Operator.Bits.Cat => binaryOperatorImplAsFunction("pkg_cat")(e)

    case  e : Operator.Bits.Or => operatorImplAsBinaryOperator("or")(e)
    case  e : Operator.Bits.And => operatorImplAsBinaryOperator("and")(e)
    case  e : Operator.Bits.Xor => operatorImplAsBinaryOperator("xor")(e)
    case  e : Operator.Bits.Not =>  operatorImplAsUnaryOperator("not")(e)

    case  e : Operator.Bits.Equal => operatorImplAsBinaryOperatorStdCast("=")(e)
    case  e : Operator.Bits.NotEqual => operatorImplAsBinaryOperatorStdCast("/=")(e)

    case  e : Operator.Bits.ShiftRightByInt => shiftRightByIntImpl(e)
    case  e : Operator.Bits.ShiftLeftByInt => shiftLeftByIntImpl(e)
    case  e : Operator.Bits.ShiftRightByUInt => binaryOperatorImplAsFunction("pkg_shiftRight")(e)
    case  e : Operator.Bits.ShiftLeftByUInt => binaryOperatorImplAsFunction("pkg_shiftLeft")(e)
    case  e : Operator.Bits.ShiftRightByIntFixedWidth =>  shiftRightBitsByIntFixedWidthImpl(e)
    case  e : Operator.Bits.ShiftLeftByIntFixedWidth =>  shiftLeftBitsByIntFixedWidthImpl(e)
    case  e : Operator.Bits.ShiftLeftByUIntFixedWidth =>  shiftLeftBitsByUIntFixedWidthImpl(e)


    //bool

    case  e : Operator.Bool.Equal => operatorImplAsBinaryOperatorStdCast("=")(e)
    case  e : Operator.Bool.NotEqual => operatorImplAsBinaryOperatorStdCast("/=")(e)


    case  e : Operator.Bool.Not => operatorImplAsUnaryOperator("not")(e)
    case  e : Operator.Bool.And => operatorImplAsBinaryOperator("and")(e)
    case  e : Operator.Bool.Or => operatorImplAsBinaryOperator("or")(e)
    case  e : Operator.Bool.Xor => operatorImplAsBinaryOperator("xor")(e)


    //enum
    case  e : Operator.Enum.Equal => enumEgualsImpl(true)(e)
    case  e : Operator.Enum.NotEqual => enumEgualsImpl(false)(e)

    //cast
    case  e : CastSIntToBits => opImplAsCast("std_logic_vector")(e)
    case  e : CastUIntToBits => opImplAsCast("std_logic_vector")(e)
    case  e : CastBoolToBits => opImplAsCast("pkg_toStdLogicVector")(e)
    case  e : CastEnumToBits => operatorImplAsEnumToBits(e)

    case  e : CastBitsToSInt => opImplAsCast("signed")(e)
    case  e : CastUIntToSInt => opImplAsCast("signed")(e)

    case  e : CastBitsToUInt => opImplAsCast("unsigned")(e)
    case  e : CastSIntToUInt => opImplAsCast("unsigned")(e)

    case  e : CastBitsToEnum => operatorImplAsBitsToEnum(e)
    case  e : CastEnumToEnum => operatorImplAsEnumToEnum(e)


    //misc
    case  e : ResizeSInt => resizeFunction("pkg_signed")(e)
    case  e : ResizeUInt => resizeFunction("pkg_unsigned")(e)
    case  e : ResizeBits => resizeFunction("pkg_stdLogicVector")(e)

    case  e : Multiplexer => muxImplAsFunction("pkg_mux")(e)

    case  e : BitVectorBitAccessFixed => accessBoolFixed(e)
    case  e : BitVectorBitAccessFloating => accessBoolFloating(e)
    case  e : BitVectorRangedAccessFixed => accessBitVectorFixed(e)
    case  e : BitVectorRangedAccessFloating => accessBitVectorFloating(e)
  }


  elaborate()
  emitEntity()
  emitArchitecture()
}