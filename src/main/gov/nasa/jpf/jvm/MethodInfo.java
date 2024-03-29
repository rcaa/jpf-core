//
// Copyright (C) 2006 United States Government as represented by the
// Administrator of the National Aeronautics and Space Administration
// (NASA).  All Rights Reserved.
// 
// This software is distributed under the NASA Open Source Agreement
// (NOSA), version 1.3.  The NOSA has been approved by the Open Source
// Initiative.  See the file NOSA-1.3-JPF at the top of the distribution
// directory tree for the complete NOSA document.
// 
// THE SUBJECT SOFTWARE IS PROVIDED "AS IS" WITHOUT ANY WARRANTY OF ANY
// KIND, EITHER EXPRESSED, IMPLIED, OR STATUTORY, INCLUDING, BUT NOT
// LIMITED TO, ANY WARRANTY THAT THE SUBJECT SOFTWARE WILL CONFORM TO
// SPECIFICATIONS, ANY IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR
// A PARTICULAR PURPOSE, OR FREEDOM FROM INFRINGEMENT, ANY WARRANTY THAT
// THE SUBJECT SOFTWARE WILL BE ERROR FREE, OR ANY WARRANTY THAT
// DOCUMENTATION, IF PROVIDED, WILL CONFORM TO THE SUBJECT SOFTWARE.
//
package gov.nasa.jpf.jvm;

import gov.nasa.jpf.Config;
import gov.nasa.jpf.JPF;
import gov.nasa.jpf.JPFException;
import gov.nasa.jpf.classfile.ClassFile;
import gov.nasa.jpf.jvm.bytecode.Instruction;
import gov.nasa.jpf.util.JPFLogger;
import gov.nasa.jpf.util.LocationSpec;
import gov.nasa.jpf.util.ObjectList;

import java.lang.reflect.Modifier;
import java.util.ArrayList;


/**
 * information associated with a method. Each method in JPF
 * is represented by a MethodInfo object
 */
public class MethodInfo extends InfoObject implements Cloneable, GenericSignatureHolder  {

  static JPFLogger logger = JPF.getLogger("gov.nasa.jpf.jvm.MethodInfo");

  static final int INIT_MTH_SIZE = 4096;
  protected static final ArrayList<MethodInfo> mthTable = new ArrayList<MethodInfo>(INIT_MTH_SIZE);
  
  // special globalIds
  static final int DIRECT_CALL = -1;
  static final int REFLECTION_CALL = -2;

  static final LocalVarInfo[] EMPTY = new LocalVarInfo[0];
  
  static final int[] EMPTY_INT = new int[0];
  
  /**
   * Used to warn about local variable information.
   */
  protected static boolean warnedLocalInfo = false;
  
  //--- various JPF method attributes
  static final int  EXEC_ATOMIC = 0x10000; // method executed atomically
  static final int  EXEC_HIDDEN = 0x20000; // method hidden from path
  static final int  FIREWALL    = 0x40000; // firewall any unhandled exceptionHandlers
                                           // (turn into UnhandledException throws)
  static final int  IS_CLINIT   = 0x80000;
  static final int  IS_INIT     = 0x100000;
  

  /** a unique int assigned to this method */
  protected int globalId = -1;

  /**
   * this is a lazy evaluated mangled name consisting of the name and
   * arg type signature
   */
  protected String uniqueName;

  /** Name of the method */
  protected String name;

  /** Signature of the method */
  protected String signature;

  /** Generic signature of the method */
  protected String genericSignature;

  /** Class the method belongs to */
  protected ClassInfo ci;

  /** Instructions associated with the method */
  protected Instruction[] code;

  /** JPFConfigException handlers */
  protected ExceptionHandler[] exceptionHandlers;

  /** classnames of checked exception thrown by the method */
  protected String[] thrownExceptionClassNames;

  /** Table used for line numbers 
   * this assigns a line number to every instruction index, instead of 
   * using an array of ranges. Assuming we have 2-3 insns per line on average,
   * this should still require less memory than a reference array with associated
   * range objects, and allows faster access of instruction line numbers, which
   * we might need for location specs
   */
  protected int[] lineNumbers;
  
  /** Local variable information */
  protected LocalVarInfo localVars[] = null;

  /** Maximum number of local variables */
  protected int maxLocals;

  /** Maximum number of elements on the stack */
  protected int maxStack;

  /** null if we don't have any */
  AnnotationInfo[][] parameterAnnotations;

  //--- a batch of attributes
  
  /** the standard Java modifier attributes */
  protected int modifiers;
   
  /** a batch of execution related JPF attributes */
  protected int attributes;
      

  //--- all the stuff we need for native methods
  // <2do> pcm - turn this into a derived class

  /**  the number of stack slots for the arguments (incl. 'this'), lazy eval */
  protected int argSize = -1;

  /** number of arguments (excl. 'this'), lazy eval */
  protected int nArgs = -1;

  /** what return type do we have (again, lazy evaluated) */
  protected byte returnType = -1;

  /** number of stack slots for return value */
  protected int retSize = -1;

  /** used for native method parameter conversion (lazy evaluated) */
  protected byte[] argTypes = null;

  
  /** user defined attributes */
  protected Object attr;
  
  static InstructionFactory insnFactory;
  
  static boolean init (Config config) {
    insnFactory = config.getEssentialInstance("vm.insn_factory.class", InstructionFactory.class);
    
    mthTable.clear();
    
    return true;
  }



  // for explicit construction only (direct calls)
  protected MethodInfo (int id) {
    globalId = id;
    // we don't want direct call methods in the mthTable (would be a memory leak)
  }
  
  public MethodInfo (ClassInfo ci, String name, String signature, int maxLocals, int maxStack, int modifiers){
    this.ci = ci;
    this.name = name;
    this.signature = signature;
    this.uniqueName = getUniqueName(name, signature);
    this.genericSignature = "";
    this.maxLocals = maxLocals;
    this.maxStack = maxStack;
    this.modifiers = modifiers;

    this.lineNumbers = null;
    this.exceptionHandlers = null;
    this.thrownExceptionClassNames = null;

    // set attributes we can deduce from the name and the ClassInfo
    if (ci != null){
      if (name.equals("<init>")) {
        attributes |= IS_INIT;
      } else if (name.equals("<clinit>")) {
        this.modifiers |= Modifier.SYNCHRONIZED;
        attributes |= IS_CLINIT | FIREWALL;
      }
      if (ci.isInterface()) { // all interface methods are public
        this.modifiers |= Modifier.PUBLIC;
      }
    }

    this.globalId = mthTable.size();
    mthTable.add(this);
  }

  
  public static MethodInfo getMethodInfo (int globalId){
    if (globalId >=0 && globalId <mthTable.size()){
      return mthTable.get(globalId);
    } else {
      return null;
    }
  }
  
  public static InstructionFactory getInstructionFactory() {
    // we clone so that instruction factories could have state
    return (InstructionFactory) insnFactory.clone();
  }
  
  public boolean hasParameterAnnotations() {
    return (parameterAnnotations != null);
  }

  
  // since some listeners might call this on every method invocation, we should do a little optimization
  static AnnotationInfo[][] NO_PARAMETER_ANNOTATIONS_0 = new AnnotationInfo[0][];
  static AnnotationInfo[][] NO_PARAMETER_ANNOTATIONS_1 = { new AnnotationInfo[0] };
  static AnnotationInfo[][] NO_PARAMETER_ANNOTATIONS_2 = { new AnnotationInfo[0], new AnnotationInfo[0] };
  static AnnotationInfo[][] NO_PARAMETER_ANNOTATIONS_3 = { new AnnotationInfo[0], new AnnotationInfo[0], new AnnotationInfo[0] };  
  
  public AnnotationInfo[][] getParameterAnnotations() {
    if (parameterAnnotations == null){ // keep this similar to getAnnotations()
      int n = getNumberOfArguments();
      switch (n){
      case 0: return NO_PARAMETER_ANNOTATIONS_0;
      case 1: return NO_PARAMETER_ANNOTATIONS_1;
      case 2: return NO_PARAMETER_ANNOTATIONS_2;
      case 3: return NO_PARAMETER_ANNOTATIONS_3;
      default:
        AnnotationInfo[][] pai = new AnnotationInfo[n][];
        for (int i=0; i<n; i++){
          pai[i] = new AnnotationInfo[0];
        }
        return pai;
      }
      
    } else {
      return parameterAnnotations;
    }
  }

  /**
   * return annotations for parameterIndex
   */
  public AnnotationInfo[] getParameterAnnotations(int parameterIndex){
    if (parameterAnnotations == null){
      return null;
    } else {
      if (parameterIndex >= getNumberOfArguments()){
        return null;
      } else {
        return parameterAnnotations[parameterIndex];
      }
    }
  }

  public void setMaxLocals(int maxLocals){
    this.maxLocals = maxLocals;
  }

  public void setMaxStack(int maxStack){
    this.maxStack = maxStack;
  }

  public void setClassInfo (ClassInfo ci){
    this.ci = ci;
  }
  
  public void setCode (Instruction[] code){
    for (int i=0; i<code.length; i++){
      code[i].setMethodInfo(this);
    }
    this.code = code;
  }
  
  public static int getNumberOfLoadedMethods () {
    return mthTable.size();
  }

  void setAtomic (boolean isAtomic) {
    if (isAtomic) {
      attributes |= EXEC_ATOMIC;
    } else {
      attributes &= ~EXEC_ATOMIC;
    }
  }
  public boolean isAtomic () {
    return ((attributes & EXEC_ATOMIC) != 0);
  }
  
  void setHidden (boolean isHidden) {
    if (isHidden) {
      attributes |= EXEC_HIDDEN;
    } else {
      attributes &= ~EXEC_HIDDEN;
    }
  }
  public boolean isHidden () {
    return ((attributes & EXEC_HIDDEN) != 0);    
  }
  
  /**
   * turn unhandled exceptionHandlers at the JPF execution level
   * into UnhandledException throws at the host VM level
   * this is useful to implement firewalls for direct calls
   * which should not let exceptionHandlers permeate into bytecode/
   * application code
   */
  public void setFirewall (boolean isFirewalled) {
    if (isFirewalled) {
      attributes |= FIREWALL;
    } else {
      attributes &= ~FIREWALL;
    }
  }
  public boolean isFirewall () {
    return ((attributes & FIREWALL) != 0);    
  }
  
  
  
  public Object clone() {
    try {
      return super.clone();
    } catch (CloneNotSupportedException cnx) {
      return null;
    }
  }
  
  public int getGlobalId() {
    return globalId;
  }
  
  protected MethodInfo createCallStub (String originator, int id){
    MethodInfo mi = new MethodInfo(id);
    String cname = ci.getName();

    mi.name = originator; // + name; // + cname; // could maybe also include the called method, but keep it fast
    mi.signature = "()V";
    mi.genericSignature = "";
    mi.maxLocals = isStatic() ? 0 : 1;
    mi.maxStack = getNumberOfCallerStackSlots();  // <2do> cache for optimization
    mi.localVars = EMPTY;
    mi.lineNumbers = null;
    mi.exceptionHandlers = null;
    mi.thrownExceptionClassNames = null;
    mi.uniqueName = mi.name;

    CodeBuilder cb = mi.createCodeBuilder();

    if (isStatic()){
      mi.modifiers |= Modifier.STATIC;

      if (isClinit()) {
        cb.invokeclinit(ci);
      } else {
        cb.invokestatic(cname, name, signature);
      }
    } else if (name.equals("<init>") || isPrivate()){
      cb.invokespecial(cname, name, signature);
    } else {
      cb.invokevirtual(cname, name, signature);
    }

    cb.directcallreturn();
    cb.installCode();

    return mi;
  }

  public CodeBuilder createCodeBuilder(){
    InstructionFactory ifact = getInstructionFactory();
    return new CodeBuilder(ifact, null, this);
  }

  public CodeBuilder createCodeBuilder(ClassFile cf){
    InstructionFactory ifact = getInstructionFactory();
    return new CodeBuilder(ifact, cf, this);
  }

  /**
   * NOTE - this only works in conjunction with a special StackFrame,
   * the caller has to make sure the right operands are pushed for the call arguments!
   */
  public MethodInfo createDirectCallStub (String originator) {
    return createCallStub(originator, DIRECT_CALL);
  }
  public boolean isDirectCallStub() {
    return (globalId == DIRECT_CALL);
  }

  public MethodInfo createReflectionCallStub() {
    return createCallStub("[reflection]", REFLECTION_CALL);
  }
  
  public boolean isReflectionCallStub() {
    return (globalId == REFLECTION_CALL);
  }

  public boolean isSyncRelevant () {
    return (name.charAt(0) != '<');
  }
  
  public boolean isClinit () {
    return ((attributes & IS_CLINIT) != 0);
  }

  public boolean isClinit (ClassInfo ci) {
    return (((attributes & IS_CLINIT) != 0) && (this.ci == ci));
  }

  public boolean isInit() {
    return ((attributes & IS_INIT) != 0);
  }
  
  /**
   * yet another name - this time with a non-mangled, but abbreviated signature
   * and without return type (e.g. like "main(String[])"
   */
  public String getLongName () {
    StringBuilder sb = new StringBuilder();
    sb.append(name);
    
    sb.append('(');
    String[] argTypeNames = getArgumentTypeNames();
    for (int i=0; i<argTypeNames.length; i++) {
      String a = argTypeNames[i];
      int idx = a.lastIndexOf('.');
      if (idx > 0) {
        a = a.substring(idx+1);
      }
      if (i>0) {
        sb.append(',');
      }
      sb.append(a);
    }
    sb.append(')');
    
    return sb.toString();
  }
  
  /**
   * return the minimal name that has to be unique for overloading
   * used as a lookup key
   * NOTE: with the silent introduction of covariant return types
   * in Java 5.0, we have to use the full signature to be unique
   */
  public static String getUniqueName (String mname, String signature) {
    return (mname + signature);
  }

  public String getStackTraceSource() {
    return getSourceFileName();
  }

  public byte[] getArgumentTypes () {
    if (argTypes == null) {
      argTypes = Types.getArgumentTypes(signature);
      nArgs = argTypes.length;
    }

    return argTypes;
  }

  public String[] getArgumentTypeNames () {
    return Types.getArgumentTypeNames(signature);
  }
  
  public int getArgumentsSize () {
    // it's actually faster to do this on demand just for the invoked methods
    // than to pull this out of each BCEL Method during init

    if (argSize < 0) {
      argSize = Types.getArgumentsSize(signature);

      if (!isStatic()) {
        argSize++;
      }
    }

    return argSize;
  }
  
  /**
   * return only the LocalVarInfos for arguments, in order of definition
   * or null if there are no localVarInfos.
   * throw a JPFException if there are more immediately in scope vars than args
   * 
   * NOTE - it is perfectly legal for a method to have arguments but no LocalVarInfos,
   * which are code attributes, clients have to check for a non-null return value
   * even if the method has arguments.
   * Note also that abstract / interface methods don't have code and hence no
   * LocalVarInfos
   */
  public LocalVarInfo[] getArgumentLocalVars(){
    if (localVars == null){ // shortcut in case we don't have args or localVars;
      return null;
    }
    
    int nArgs = getNumberOfStackArguments(); // we want 'this'
    if (nArgs == 0){
      return new LocalVarInfo[0]; // rare enough so that we don't use a static
    }

    LocalVarInfo[] argLvis = new LocalVarInfo[nArgs];
    int n = 0; // how many args we've got so far
    
    for (LocalVarInfo lvi : localVars){
      // arguments are the only ones that are immediately in scope
      if (lvi.getStartPC() == 0){
        if (n == nArgs){ // ARGH - more in-scope vars than args
          throw new JPFException("inconsistent localVar table for method " + getFullName());
        }
        
        // order with respect to slot index - since this might get called
        // frequently, we don't use java.util.Arrays.sort() but sort in
        // on-the-fly. Note that we can have several localVar entries for the
        // same name, but only one can be immediately in scope
        int slotIdx = lvi.getSlotIndex();

        int i;
        for (i = 0; i < n; i++) {
          if (slotIdx < argLvis[i].getSlotIndex()) {
            for (int j=n; j>i; j--){
              argLvis[j] = argLvis[j-1];
            }
            argLvis[i] = lvi;
            n++;
            break;
          }
        }
        if (i == n) { // append
          argLvis[n++] = lvi;
        }
      }
    }
    
    return argLvis;
  }
  
  public String getReturnType () {
    return Types.getReturnTypeSignature(signature);
  }

  public String getReturnTypeName () {
    return Types.getReturnTypeName(signature);
  }
  
  public String getSourceFileName () {
    if (ci != null) {
      return ci.getSourceFileName();
    } else {
      return "[VM]";
    }
  }

  public String getClassName () {
    if (ci != null) {
      return ci.getName();
    } else {
      return "[VM]";
    }
  }
  
  /**
   * Returns the class the method belongs to.
   */
  public ClassInfo getClassInfo () {
    return ci;
  }

  /**
   * @deprecated - use getFullName
   */
  public String getCompleteName () {
    return getFullName();
  }

  /**
   * return classname.name (but w/o signature)
   */
  public String getBaseName() {
    return getClassName() + '.' + name;
  }
  
  public boolean isExecutable (ThreadInfo ti) {
    // <2do> well, that doesn't take into account if executability depends on values
    // but 'isExecutable' is going away anyways
    return canEnter(ti);
  }

    
  public boolean isCtor () {
    return (name.equals("<init>"));
  }
  
  public boolean isInternalMethod () {
    // <2do> pcm - should turn this into an attribute for efficiency reasons
    return (name.equals("<clinit>") || uniqueName.equals("finalize()V"));
  }
  
  public boolean isThreadEntry (ThreadInfo ti) {
    return (uniqueName.equals("run()V") && (ti.countStackFrames() == 1));
  }
  
  /**
   * Returns the full classname (if any) + name + signature.
   */
  public String getFullName () {
    if (ci != null) {
      return ci.getName() + '.' + getUniqueName();
    } else {
      return getUniqueName();
    }
  }

  /**
   * returns stack trace name: classname (if any) + name
   */
  public String getStackTraceName(){
    if (ci != null) {
      return ci.getName() + '.' + name;
    } else {
      return name;
    }
  }
  
  /**
   * return number of instructions
   */
  public int getNumberOfInstructions() {
    if (code == null){
      return 0;
    }
    
    return code.length;
  }
  
  /**
   * Returns a specific instruction.
   */
  public Instruction getInstruction (int i) {
    if (code == null) {
      return null;
    }

    if ((i < 0) || (i >= code.length)) {
      return null;
    }

    return code[i];
  }

  /**
   * Returns the instruction at a certain position.
   */
  public Instruction getInstructionAt (int position) {
    if (code == null) {
      return null;
    }

    for (int i = 0, l = code.length; i < l; i++) {
      if ((code[i] != null) && (code[i].getPosition() == position)) {
        return code[i];
      }
    }

    throw new JPFException("instruction not found");
  }

  /**
   * Returns the instructions of the method.
   */
  public Instruction[] getInstructions () {
    return code;
  }
  
  public boolean includesLine (int line){
    int len = code.length;
    return (code[0].getLineNumber() <= line) && (code[len].getLineNumber() >= line);
  }

  public Instruction[] getInstructionsForLine (int line){
    return getInstructionsForLineInterval(line,line);
  }

  public Instruction[] getInstructionsForLineInterval (int l1, int l2){
    int len = code.length;

    if ((code[0].getLineNumber() > l2) || (code[len-1].getLineNumber() < l1)){
      // no overlap
      return null;
    }

    int i1 = -1;
    int i2 = -1;
    for (int i = 0; i < len; i++) {
      int l = code[i].getLineNumber();
      if ((l >= l1) && (l <= l2)) {
        if (i1 < 0) {
          i1 = i;
        }
        i2 = i;
      } else if (l > l2) {
        break;
      }
    }

    if (i1 >= 0){
      Instruction[] a = new Instruction[i2 - i1 + 1];
      int j = 0;
      for (int i = i1; i <= i2; i++) {
        a[j++] = code[i];
      }
      return a;

    } else {
      return null; // no insn found for this line interval
    }
  }

  public Instruction[] getMatchingInstructions (LocationSpec lspec){
    return getInstructionsForLineInterval(lspec.getFromLine(), lspec.getToLine());
  }


  /**
   * Returns the line number for a given position.
   */
  public int getLineNumber (Instruction pc) {
    if (lineNumbers == null) {
      if (pc == null)
        return -1;
      else
        return pc.getPosition();
    }

    int idx = pc.getInstructionIndex();
    if (idx < 0) idx = 0;
    return lineNumbers[idx];
  }

  /**
   * Returns a table to translate positions into line numbers.
   */
  public int[] getLineNumbers () {
    return lineNumbers;
  }

  public boolean containsLineNumber (int n){
    if (lineNumbers != null){
      return (lineNumbers[0] <= n) && (lineNumbers[lineNumbers.length-1] <= n);
    }
    
    return false;
  }
  
  public boolean intersectsLineNumbers( int first, int last){
    if (lineNumbers != null){
      if ((last < lineNumbers[0]) || (first > lineNumbers[lineNumbers.length-1])){
        return false;
      }
      return true;
    }
    
    return false;
  }
  
  public boolean isMJI () {
    return false;
  }

  public int getMaxLocals () {
    return maxLocals;
  }

  public int getMaxStack () {
    return maxStack;
  }

  public ExceptionHandler[] getExceptions () {
    return exceptionHandlers;
  }

  public String[] getThrownExceptionClassNames () {
    return thrownExceptionClassNames;
  }


  public LocalVarInfo getLocalVar(String name, int pc){
    LocalVarInfo[] vars = localVars;
    if (vars != null){
      for (int i = 0; i < vars.length; i++) {
        LocalVarInfo lv = vars[i];
        if (lv.matches(name, pc)) {
          return lv;
        }
      }
    }

    return null;

  }

  public LocalVarInfo getLocalVar (int slotIdx, int pc){
    LocalVarInfo[] vars = localVars;

    if (vars != null){
      for (int i = 0; i < vars.length; i++) {
        LocalVarInfo lv = vars[i];
        if (lv.matches(slotIdx, pc)) {
          return lv;
        }
      }
    }

    return null;
  }

  public LocalVarInfo[] getLocalVars() {
    return localVars; 
  }


  /**
   * note that this might contain duplicates for variables with multiple
   * scope entries
   */
  public String[] getLocalVariableNames() {
    String[] names = new String[localVars.length];

    for (int i=0; i<localVars.length; i++){
      names[i] = localVars[i].getName();
    }

    return names;
  }


  public MethodInfo getOverriddenMethodInfo(){
    MethodInfo smi = null;
    
    if (ci != null) {
      ClassInfo sci = ci.getSuperClass();
      if (sci != null){
        smi = sci.getMethod(getUniqueName(), true);
      }
    }
    
    return smi;
  }
  
  /**
   * Returns the name of the method.
   */
  public String getName () {
    return name;
  }

  public String getJNIName () {
    return Types.getJNIMangledMethodName(null, name, signature);
  }
  
  public int getModifiers () {
    return modifiers;
  }
  
  /**
   * Returns true if the method is native
   */
  public boolean isNative () {
    return ((modifiers & Modifier.NATIVE) != 0);
  }

  public boolean isAbstract () {
    return ((modifiers & Modifier.ABSTRACT) != 0);
  }
  
  // overridden by NativeMethodInfo
  public boolean isUnresolvedNativeMethod(){
    return ((modifiers & Modifier.NATIVE) != 0);
  }

  public int getNumberOfArguments () {
    if (nArgs < 0) {
      nArgs = Types.getNumberOfArguments(signature);
    }

    return nArgs;
  }

  /**
   * Returns the size of the arguments.
   * This returns the number of parameters passed on the stack, incl. 'this'
   */
  public int getNumberOfStackArguments () {
    int n = getNumberOfArguments();

    return isStatic() ? n : n + 1;
  }

  public int getNumberOfCallerStackSlots () {
    return Types.getNumberOfStackSlots(signature, isStatic()); // includes return type
  }

  public Instruction getLastInsn() {
    if (code == null){
      return null;
    }
    
    return code[code.length-1];
  }

  /**
   * do we return Object references?
   */
  public boolean isReferenceReturnType () {
    int r = getReturnTypeCode();

    return ((r == Types.T_REFERENCE) || (r == Types.T_ARRAY));
  }

  public byte getReturnTypeCode () {
    if (returnType < 0) {
      returnType = Types.getReturnBuiltinType(signature);
    }

    return returnType;
  }

  /**
   * what is the slot size of the return value
   */
  public int getReturnSize() {
    if (retSize == -1){
      switch (getReturnTypeCode()) {
        case Types.T_VOID:
          retSize = 0;
          break;

        case Types.T_LONG:
        case Types.T_DOUBLE:
          retSize = 2;
          break;

        default:
          retSize = 1;
          break;
      }
    }

    return retSize;
  }

  public Class<? extends ChoiceGenerator<?>> getReturnChoiceGeneratorType (){
    switch (getReturnTypeCode()){
      case Types.T_BOOLEAN:
        return BooleanChoiceGenerator.class;

      case Types.T_BYTE:
      case Types.T_CHAR:
      case Types.T_SHORT:
      case Types.T_INT:
        return IntChoiceGenerator.class;

      case Types.T_LONG:
        return LongChoiceGenerator.class;

      case Types.T_FLOAT:
        return FloatChoiceGenerator.class;

      case Types.T_DOUBLE:
        return DoubleChoiceGenerator.class;

      case Types.T_ARRAY:
      case Types.T_REFERENCE:
      case Types.T_VOID:
        return ReferenceChoiceGenerator.class;
    }

    return null;
  }

  /**
   * Returns the signature of the method.
   */
  public String getSignature () {
    return signature;
  }

  public String getGenericSignature() {
    return genericSignature;
  }

  public void setGenericSignature(String sig){
    genericSignature = sig;
  }

  /**
   * Returns true if the method is static.
   */
  public boolean isStatic () {
    return ((modifiers & Modifier.STATIC) != 0);
  }

  /**
   * is this a public method
   */
  public boolean isPublic() {
    return ((modifiers & Modifier.PUBLIC) != 0);
  }
  
  public boolean isPrivate() {
    return ((modifiers & Modifier.PRIVATE) != 0);
  }
  
  public boolean isProtected() {
    return ((modifiers & Modifier.PROTECTED) != 0);
  }

  /**
   * Returns true if the method is synchronized.
   */
  public boolean isSynchronized () {
    return ((modifiers & Modifier.SYNCHRONIZED) != 0);
  }

  // <2do> these modifiers are still java.lang.reflect internal and not
  // supported by public Modifier methods, but since we want to keep this 
  // similar to the Method reflection and we get the modifiers from the
  // classfile we implement this with explicit values
  
  public boolean isSynthetic(){
    return ((modifiers & 0x00001000) != 0);    
  } 
  public boolean isVarargs(){
    return ((modifiers & 0x00000080) != 0);        
  }
  
  /*
   * is this from a classfile or was it created by JPF (and hence should not
   * be visible in stacktraces etc)
   */
  public boolean isJPFInternal(){
    // note this has a different meaning than Method.isSynthetic(), which
    // is defined in VM spec 4.7.8. What we mean here is that this MethodInfo
    // is not associated with any class (such as direct call MethodInfos), but
    // there might be more in the future
    return (ci == null);
  }
  
  public String getUniqueName () {
    return uniqueName;
  }

  public boolean canEnter (ThreadInfo th) {
    if (isSynchronized()) {
      ElementInfo ei = getBlockedObject(th, true);

      // <?> pcm - the other way round would be intuitive
      return ei.canLock(th);
    }

    return true;
  }

  public ElementInfo getBlockedObject (ThreadInfo th, boolean isBeforeCall) {
    int         objref;
    ElementInfo ei = null;

    if (isSynchronized()) {
      if (isStatic()) {
        objref = ci.getClassObjectRef();
      } else {
        // NOTE 'inMethod' doesn't work for natives, because th.getThis()
        // pulls 'this' from the stack frame, which we don't have (and don't need)
        // for natives
        objref = isBeforeCall ? th.getCalleeThis(this) : th.getThis();
      }

      ei = th.getElementInfo(objref);

      assert (ei != null) : ("inconsistent stack, no object or class ref: " +
                               getFullName() + " (" + objref +")");
    }

    return ei;
  }

  // override this if there is a need for a special StackFrame
  protected StackFrame createStackFrame (ThreadInfo ti){
    return new StackFrame(this, ti.getTopFrame());
  }

  /**
   * locking, stackframe push and enter notification
   */
  public void enter (ThreadInfo ti) {
    if (isSynchronized()) {
      ElementInfo ei = getBlockedObject(ti, true);
      ei.lock(ti);

      if (isStatic() && isClinit()) {
        ci.setInitializing(ti);
      }
    }

    // we need to do this after locking
    ti.pushFrame( createStackFrame(ti));

    ti.getVM().notifyMethodEntered(ti, this);
  }

  /**
   * unlocking and exit notification
   */
  public void leave (ThreadInfo ti) {
    
    // <2do> - that's not really enough, we might have suspicious bytecode that fails
    // to release locks acquired by monitor_enter (e.g. by not having a handler that
    // monitor_exits & re-throws). That's probably shifted into the bytecode verifier
    // in the future (i.e. outside JPF), but maybe we should add an explicit test here
    // and report an error if the code does asymmetric locking (according to the specs,
    // VMs are allowed to silently fix this, so it might run on some and fail on others)
    
    if (isSynchronized()) {
      ElementInfo ei = getBlockedObject(ti, false);
      if (ei.isLocked()){
        ei.unlock(ti);
      }
      
      if (isStatic() && isClinit()) {
        // we just released the lock on the class object, returning from a clinit
        // now we can consider this class to be initialized.
        // NOTE this is still part of the RETURN insn of clinit, so ClassInfo.isInitialized
        // is protected
        ci.setInitialized();
      }
    }

    ti.getVM().notifyMethodExited(ti, this);
  }
  
  /**
   * execute this method invocation
   */
  public Instruction execute (ThreadInfo ti) {
    enter(ti);
    return ti.getPC();
  }

  public boolean hasCode(){
    return (code != null);
  }
  
  public boolean hasEmptyBody (){
    // only instruction is a return
    return (code.length == 1 && (code[0] instanceof ReturnInstruction));
  }


  //--- parameter annotations
  protected void startParameterAnnotations(int annotationCount){
    parameterAnnotations = new AnnotationInfo[annotationCount][];
  }

  protected void setParameterAnnotations(int index, AnnotationInfo[] ai){
    parameterAnnotations[index] = ai;
  }

  protected void finishParameterAnnotations(){
    // nothing
  }

  //--- thrown exceptions
  protected void startTrownExceptions (int exceptionCount){
    thrownExceptionClassNames = new String[exceptionCount];
  }

  protected void setException (int index, String exceptionType){
    thrownExceptionClassNames[index] = Types.getClassNameFromTypeName(exceptionType);
  }

  protected void finishThrownExceptions(){
    // nothing
  }


  //--- exception handler table initialization
  protected void startExceptionHandlerTable (int handlerCount){
    exceptionHandlers = new ExceptionHandler[handlerCount];
  }

  protected void setExceptionHandler (int index, int startPc, int endPc, int handlerPc, String catchType){
    exceptionHandlers[index] = new ExceptionHandler(catchType, startPc, endPc, handlerPc);
  }

  protected void finishExceptionHandlerTable(){
    // nothing
  }

  //--- local var table initialization
  protected void startLocalVarTable (int localVarCount){
    localVars = new LocalVarInfo[localVarCount];
  }

  protected void setLocalVar(int index, String varName, String descriptor, int scopeStartPc, int scopeEndPc, int slotIndex){
    localVars[index] = new LocalVarInfo(varName, descriptor, "", scopeStartPc, scopeEndPc, slotIndex);
  }

  protected void finishLocalVarTable(){
    // nothing to do
  }

  //--- line number table initialization

  protected void startLineNumberTable(int lineNumberCount){
    int len = code.length;
    int[] ln = new int[len];

    lineNumbers = ln;
  }

  protected void setLineNumber(int index, int lineNumber, int startPc){
    int len = code.length;
    int[] ln = lineNumbers;

    for (int i=0; i<len; i++){
      Instruction insn = code[i];
      int pc = insn.getPosition();

      if (pc == startPc){ // this is the first insn with this line number
        ln[i] = lineNumber;
        return;
      }
    }
  }

  protected void finishLineNumberTable (){
    int len = code.length;
    int[] ln = lineNumbers;
    int lastLine = ln[0];

    for (int i=1; i<len; i++){
      if (ln[i] == 0){
        ln[i] = lastLine;
      } else {
        lastLine = ln[i];
      }
    }
  }

  public String toString() {
    return "MethodInfo[" + getFullName() + ']';
  }


  //--- the generic attribute API

  public boolean hasAttr () {
    return (attr != null);
  }

  public boolean hasAttr (Class<?> attrType){
    return ObjectList.containsType(attr, attrType);
  }

  /**
   * this returns all of them - use either if you know there will be only
   * one attribute at a time, or check/process result with ObjectList
   */
  public Object getAttr(){
    return attr;
  }

  /**
   * this replaces all of them - use only if you know 
   *  - there will be only one attribute at a time
   *  - you obtained the value you set by a previous getXAttr()
   *  - you constructed a multi value list with ObjectList.createList()
   */
  public void setAttr (Object a){
    attr = a;    
  }

  public void addAttr (Object a){
    attr = ObjectList.add(attr, a);
  }

  public void removeAttr (Object a){
    attr = ObjectList.remove(attr, a);
  }

  public void replaceAttr (Object oldAttr, Object newAttr){
    attr = ObjectList.replace(attr, oldAttr, newAttr);
  }

  /**
   * this only returns the first attr of this type, there can be more
   * if you don't use client private types or the provided type is too general
   */
  public <T> T getAttr (Class<T> attrType) {
    return ObjectList.getFirst(attr, attrType);
  }

  public <T> T getNextAttr (Class<T> attrType, Object prev) {
    return ObjectList.getNext(attr, attrType, prev);
  }

  public ObjectList.Iterator attrIterator(){
    return ObjectList.iterator(attr);
  }
  
  public <T> ObjectList.TypedIterator<T> attrIterator(Class<T> attrType){
    return ObjectList.typedIterator(attr, attrType);
  }

  // -- end attrs --

  
  // for debugging purposes
  public void dump(){
    System.out.println("--- " + this);
    for (int i = 0; i < code.length; i++) {
      System.out.printf("%2d [%d]: %s\n", i, code[i].getPosition(), code[i].toString());
    }
  }

//  //--------------------------- by elton -------------------------------
//
//	 private boolean postDominatorsUpdates = false;
//
//	 public void updatePostDominators() {
//
//		 if (postDominatorsUpdates) {
//			 return;
//		 }
//
//		 postDominatorsUpdates = true;
//
//		 List<BasicBlock> bblocks = BasicBlock.genBasicBlocks(code);
//
////System.out.println(bblocks);
////for (BasicBlock bb : bblocks) {
////	System.out.println(bb.printInOutEdges());
////}
//
//		 /*******************************************
//		  * chaotic iteration to find solution to the
//		  * set of all post-dominators
//		  *******************************************/
//		 Map<BasicBlock, Set<BasicBlock>> state = new HashMap<BasicBlock, Set<BasicBlock>>();		 
//		 List<BasicBlock> wlist = new ArrayList<BasicBlock>();
//		 wlist.addAll(bblocks);
//
//		 // initialize state
//		 int size = bblocks.size();
//		 for (int i = 0; i < size; i++) {
//			 BasicBlock bb = bblocks.get(i);
//			 Set<BasicBlock> fresh = new HashSet<BasicBlock>();
//			 fresh.addAll(bblocks);
//			 state.put(bb, fresh);
//		 }
//
//		 // fix-point computation
//		 while (wlist.size() > 0) {
//			 BasicBlock bb = wlist.remove(0);
//			 Set<BasicBlock> posDoms = state.get(bb);
//			 /** recalculate **/
//			 Set<BasicBlock> combined = combine(bb, state);
//			 if (!combined.equals(posDoms)) {				 
////				 System.out.println("*************");
////				 System.out.println("updating" + bb);
////				 System.out.println("before: " + posDoms);
////				 System.out.println("after: " + combined);
////				 System.out.println("*************");
//				 if (combined.size() > posDoms.size()) {
//					 System.err.println("unexpected!");
//					 continue;
//				 }
//				 addDistinct(bblocks, wlist, bb);
//				 state.put(bb, combined);
//			 }
//		 }
//
////for (Map.Entry<BasicBlock, Set<BasicBlock>> entry : state.entrySet()) {
////	System.out.printf("%s => %s\n", entry.getKey(), entry.getValue());
////}
//
//		 /**********************************************
//		  * find immediate post-dominator of bb: we 
//		  * implemented this as the first basic block 
//		  * (meaning the one with smaller value of 
//		  * "lo" field) different than bb, if one exists.
//		  * otherwise, it is bb.
//		  *********************************************/
//		 
//		 for (Map.Entry<BasicBlock, Set<BasicBlock>> entry : state.entrySet()) {
//			 
//			 BasicBlock key = entry.getKey();
//			 
//			 /**
//			  * find immediate post-dom basic block
//			  */
//			 BasicBlock ipDomBB = null;
//			 for (BasicBlock bb : entry.getValue()) {
//				 if (bb == key) continue;
//				 int val = bb.getLo();
//				 int minPos = ipDomBB == null ? Integer.MAX_VALUE : ipDomBB.getLo(); 
//				 if (val < minPos && val > key.getLo()) {
//					 ipDomBB = bb;
//				 }
//			 }
//			 
//			 
//			 /** set immediate post-dom */
//			 setFirstPosDom(bblocks, key, ipDomBB);
//			 
//		 }
//
//		 
//	 }
//
//	private void setFirstPosDom(
//			List<BasicBlock> bblocks, 
//			BasicBlock key,
//			BasicBlock ipDomBB) {
//		
//		Instruction ipdom = (ipDomBB == null) ? EOM : code[ipDomBB.getLo()];
//		/**
//		 * necessary because of switch statements
//		 */
//		Instruction insn = (Instruction) code[key.getHi()];
//		
//		boolean isSwitch = insn instanceof SwitchInstruction;
//		
//		boolean isBranch = 
//			(insn instanceof IfInstruction) || 
//			(insn instanceof GOTO);
//		
//		if (isBranch) {
//
//			insn.setFirstPosDominator(ipdom);
//
//		} else if (isSwitch) {
//
//			if (ipdom != EOM) {
//				int position = code[ipDomBB.getLo()].getPosition();
//				
//				SwitchInstruction si = (SwitchInstruction) insn;
//				int[] positions = si.getTargets();
//				for (int j = 0; j < positions.length + 1; j++) {
//					if (j == positions.length) {
//						//TODO: handle default better
//						//	if (si.getTarget() == offset) {
//						//	  insn.setFirstPosDominator(EOM);
//						//	  break;
//						//	}
//					} else if (positions[j] == position) {
////						insn.setFirstPosDominator(EOM);
//						insn.setFirstPosDominator(code[ipDomBB.getHi()]);
//						break;
//					}
//				}
//				
//			}
//
//			if (insn.getFirstPosDominator() == null) {
//				insn.setFirstPosDominator(ipdom);
//			}
//
//		} 
//		
//	}
//
//	 static Instruction EOM /* end of method */ = new Instruction() {
//		 @Override
//		 public int getByteCode() {
//			 throw new UnsupportedOperationException();
//		 }		
//		 @Override
//		 public Instruction execute(SystemState ss, KernelState ks, ThreadInfo ti) {
//			 throw new UnsupportedOperationException();
//		 }
//		 @Override
//		 public int getOffset() {
//			 return Integer.MAX_VALUE;
//		 }
//
//		 @Override
//		 public int getPosition() {
//			 return Integer.MAX_VALUE;
//		 }
//		 @Override
//		 public String toString(){
//			 return "EOM";
//		 }
//	 };
//
//	 private void addDistinct(List<BasicBlock> bblocks, List<BasicBlock> wlist,
//			 BasicBlock bb) {
//		 List<BasicBlock> targeters = bb.getTargeters();
//		 for (BasicBlock targeter : targeters) {
//			 if (!wlist.contains(targeter)) {
//				 wlist.add(targeter);
//			 }
//		 }
//
//	 }
//
//	 /**
//	  * takes the intersection of sets associated 
//	  * with successor nodes and then the union 
//	  * with itself
//	  * 
//	  * @param bb
//	  * @param state
//	  * @return
//	  */
//	 private Set<BasicBlock> combine(BasicBlock bb, Map<BasicBlock, Set<BasicBlock>> state) {
//		 List<BasicBlock> targets = bb.getTargets();
//		 Set<BasicBlock> result = new HashSet<BasicBlock>();
//		 boolean firstTime = true;
//		 /** intersection of all target sets **/
//		 for (BasicBlock target : targets) {
//			 Set<BasicBlock> posDom = state.get(target);
//			 if (posDom != null) {
//				 if (firstTime) {
//					 firstTime = false;
//					 result.addAll(posDom);
//				 } else {
//					 result.retainAll(posDom);
//				 }
//			 }
//		 }
//		 /** then add itself **/
//		 result.add(bb);
//		 return result;
//	 }
//
//
//	public Instruction getSliceInstructionTarget(IfInstruction ins) {
//		 Instruction target = null;
//		 Instruction tmp;
//		 int index = ins.getOffset();
//		 if (index != -1) {
//			 target = ins.getTarget();
//			 tmp = code[target.getOffset()-1];
//			 if ( tmp instanceof GOTO ) {
//				 GOTO goto_ = (GOTO)tmp;
//				 if(goto_.getType() == JumpType.AUX && goto_.getTarget().getPosition() > goto_.getPosition()){
//					 target = goto_.getTarget();  
//				 }
//			 }
//		 }
//		 return target;
//	 }
//
//	 public Instruction getInstructionBefore(Instruction insn){
//		  return code[insn.getOffset()-1];
//	 }
//
//
//	 /**
//	  * Search for the instruction that mark the end of loops as "do-while" and "for"
//	  * @param the target instruction of continue goto.
//	  * @return
//	  */
//	 public Instruction endLoops(Instruction continueTarget){
//		 int idx = continueTarget.getOffset();
//		 for (int i = idx; i < code.length; i++) {
//			 if(code[i] instanceof IfInstruction){ //in case of "do-while"
//				 IfInstruction if_ = (IfInstruction)code[i];
//				 if(if_.getTarget().getPosition() < if_.getPosition()){
//					 return code[i+1];
//				 }
//			 }else if(code[i] instanceof GOTO){ //in  case of "for"
//				 GOTO goto_ = (GOTO)code[i];
//				 if(goto_.getTarget().getPosition() < goto_.getPosition()){ //*REMOVE check later to improve performance
//					 return code[i+1];
//				 }else{
//					 throw new RuntimeException("Check endLoop for do-whiles and fors");//*
//				 }
//			 }
//		 }
//		 return null;
//	 }
//	 //----------------------------- - - --  -------------------------------


  
}
