//
// Copyright (C) 2006 United States Government as represented by the
// Administrator of the National Aeronautics and Space Administration
// (NASA).  All Rights Reserved.
// 
// This software is distributed under the NASA Open Source Agreement
// (NOSA), version 1.3.  The NOSA has been approved by the Open Source
// Initiative.  See the fileName NOSA-1.3-JPF at the top of the distribution
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
package gov.nasa.jpf.jvm.bytecode;

import gov.nasa.jpf.jvm.ClassInfo;
import gov.nasa.jpf.jvm.KernelState;
import gov.nasa.jpf.jvm.MethodInfo;
import gov.nasa.jpf.jvm.SystemState;
import gov.nasa.jpf.jvm.ThreadInfo;
import gov.nasa.jpf.util.ObjectList;
import gov.nasa.jpf.util.Source;

import java.util.ArrayList;
import java.util.List;

/**
 * common root of all JPF bytecode instruction classes
 * 
 * <2do> this class should be in gov.nasa.jpf.jvm
 */
public abstract class Instruction implements InstructionVisitorAcceptor {

	protected static final List<String> unimplemented = new ArrayList<String>();

	protected int insnIndex; // code[] index of instruction
	protected int position; // accumulated bytecode position (prev pos + prev
							// bc-length)
	protected MethodInfo mi; // the method this insn belongs to

	// property/mode specific attributes
	protected Object attr;

	abstract public int getByteCode();

	// to allow a classname and methodname context for each instruction
	public void setContext(String className, String methodName, int lineNumber,
			int offset) {
	}

	/**
	 * is this the first instruction in a method
	 */
	public boolean isFirstInstruction() {
		return (insnIndex == 0);
	}

	/**
	 * answer if this is a potential loop closing jump
	 */
	public boolean isBackJump() {
		return false;
	}

	/**
	 * is this one of our own, artificial insns?
	 */
	public boolean isExtendedInstruction() {
		return false;
	}

	public MethodInfo getMethodInfo() {
		return mi;
	}

	/**
	 * that's used for explicit construction of MethodInfos (synthetic methods)
	 */
	public void setMethodInfo(MethodInfo mi) {
		this.mi = mi;
	}

	/**
	 * this returns the instruction at the following code insnIndex within the
	 * same method, which might or might not be the next one to execute
	 * (branches, overlay calls etc.).
	 */
	public Instruction getNext() {
		return mi.getInstruction(insnIndex + 1);
	}

	public int getInstructionIndex() {
		return insnIndex;
	}

	public int getPosition() {
		return position;
	}

	public void setLocation(int insnIdx, int pos) {
		insnIndex = insnIdx;
		position = pos;
	}

	/**
	 * return the length in bytes of this instruction. override if this is not 1
	 */
	public int getLength() {
		return 1;
	}

	public Instruction getPrev() {
		if (insnIndex > 0) {
			return mi.getInstruction(insnIndex - 1);
		} else {
			return null;
		}
	}

	/**
	 * this is for listeners that process instructionExecuted(), but need to
	 * determine if there was a CG registration, an overlayed direct call (like
	 * clinit) etc. The easy case is the instruction not having been executed
	 * yet, in which case ti.getNextPC() == null There are two cases for
	 * re-execution: either nextPC was set to the same insn (which is what CG
	 * creators usually use), or somebody just pushed another stackframe that
	 * executes something which will return to the same insn (that is what
	 * automatic <clinit> calls and the like do - we call it overlays)
	 */
	public boolean isCompleted(ThreadInfo ti) {
		Instruction nextPc = ti.getNextPC();

		if (nextPc == null) {
			return ti.isTerminated();

		} else {

			return (nextPc != this)
					&& (ti.getStackFrameExecuting(this, 1) == null);
		}

		// <2do> how do we account for exceptions?
	}

	/**
	 * this method can be overridden if instruction classes have to store
	 * information for instructionExecuted() notifications, and this information
	 * should not be stored persistent to avoid memory leaks (e.g. via traces).
	 * Called by ThreadInfo.executeInstruction
	 */
	public void cleanupTransients() {
		// nothing here
	}

	public boolean isSchedulingRelevant(SystemState ss, KernelState ks,
			ThreadInfo ti) {
		return false;
	}

	/**
	 * this is the real workhorse returns next instruction to execute in this
	 * thread
	 * 
	 * <2do> it's unfortunate we roll every side effect into this method,
	 * because it diminishes the value of the 'executeInstruction' notification:
	 * all insns that require some sort of late binding (InvokeVirtual,
	 * GetField, ..) are not yet fully analyzable (e.g. the callee of
	 * InvokeVirtuals is not known yet), putting the burden of duplicating the
	 * related code of execute() in the listener. It would be better if we
	 * factor this 'prepareExecution' out of execute()
	 */
	public abstract Instruction execute(SystemState ss, KernelState ks,
			ThreadInfo ti);

	public boolean examine(SystemState ss, KernelState ks, ThreadInfo th) {
		return false;
	}

	public boolean examineAbstraction(SystemState ss, KernelState ks,
			ThreadInfo th) {
		return false;
	}

	public String toString() {
		return getMnemonic();
	}

	public String getMnemonic() {
		String s = getClass().getSimpleName();
		return s.toLowerCase();
	}

	public int getLineNumber() {
		return mi.getLineNumber(this);
	}

	public String getSourceLine() {
		ClassInfo ci = mi.getClassInfo();
		if (ci != null) {
			int line = mi.getLineNumber(this);
			String fileName = ci.getSourceFileName();

			Source src = Source.getSource(fileName);
			if (src != null) {
				String srcLine = src.getLine(line);
				if (srcLine != null) {
					return srcLine;
				}
			}
		}

		return null;
	}

	/**
	 * this is for debugging/logging if we always want something back telling us
	 * where this insn came from
	 */
	public String getSourceOrLocation() {
		ClassInfo ci = mi.getClassInfo();
		if (ci != null) {
			int line = mi.getLineNumber(this);
			String file = ci.getSourceFileName();

			Source src = Source.getSource(file);
			if (src != null) {
				String srcLine = src.getLine(line);
				if (srcLine != null) {
					return srcLine;
				}
			}

			return "(" + file + ':' + line + ')'; // fallback

		} else {
			return "[synthetic] " + mi.getName();
		}
	}

	/**
	 * this returns a "pathname:line" string
	 */
	public String getFileLocation() {
		ClassInfo ci = mi.getClassInfo();
		if (ci != null) {
			int line = mi.getLineNumber(this);
			String fname = ci.getSourceFileName();
			return (fname + ':' + line);
		} else {
			return "[synthetic] " + mi.getName();
		}
	}

	/**
	 * this returns a "filename:line" string
	 */
	public String getFilePos() {
		String file = null;
		int line = -1;
		ClassInfo ci = mi.getClassInfo();

		if (ci != null) {
			line = mi.getLineNumber(this);
			file = ci.getSourceFileName();
			int i = file.lastIndexOf('/'); // ClassInfo.sourceFileName is using
											// '/'
			if (i >= 0) {
				file = file.substring(i + 1);
			}
		}

		if (file != null) {
			if (line != -1) {
				return (file + ':' + line);
			} else {
				return file;
			}
		} else {
			return ("pc " + position);
		}
	}

	/**
	 * this returns a "class.method(line)" string
	 */
	public String getSourceLocation() {
		ClassInfo ci = mi.getClassInfo();

		if (ci != null) {
			String s = ci.getName() + '.' + mi.getName() + '(' + getFilePos()
					+ ')';
			return s;

		} else {
			return null;
		}
	}

	public void init(MethodInfo mi, int offset, int position) {
		this.mi = mi;
		this.insnIndex = offset;
		this.position = position;
	}

	/**
	 * this is a misnomer - we actually push the clinit calls here in case we
	 * need some. 'causedClinitCalls' might be more appropriate, but it is used
	 * in a number of external projects
	 */
	public boolean requiresClinitExecution(ThreadInfo ti, ClassInfo ci) {
		return ci.requiresClinitExecution(ti);
	}

	/**
	 * this is returning the next Instruction to execute, to be called to obtain
	 * the return value of execute() if this is not a branch insn
	 * 
	 * Be aware of that we might have had exceptions caused by our execution (->
	 * lower frame), or we might have had overlaid calls (-> higher frame), i.e.
	 * we can't simply assume it's the following insn. We have to acquire this
	 * through the top frame of the ThreadInfo.
	 * 
	 * note: the System.exit() problem should be gone, now that it is
	 * implemented as ThreadInfo state (TERMINATED), rather than purged stacks
	 */
	public Instruction getNext(ThreadInfo ti) {
		return ti.getPC().getNext();
	}

	public void accept(InstructionVisitor insVisitor) {
		insVisitor.visit(this);
	}

	// --- the generic attribute API

	public boolean hasAttr() {
		return (attr != null);
	}

	public boolean hasAttr(Class<?> attrType) {
		return ObjectList.containsType(attr, attrType);
	}

	/**
	 * this returns all of them - use either if you know there will be only one
	 * attribute at a time, or check/process result with ObjectList
	 */
	public Object getAttr() {
		return attr;
	}

	/**
	 * this replaces all of them - use only if you know - there will be only one
	 * attribute at a time - you obtained the value you set by a previous
	 * getXAttr() - you constructed a multi value list with
	 * ObjectList.createList()
	 */
	public void setAttr(Object a) {
		attr = a;
	}

	public void addAttr(Object a) {
		attr = ObjectList.add(attr, a);
	}

	public void removeAttr(Object a) {
		attr = ObjectList.remove(attr, a);
	}

	public void replaceAttr(Object oldAttr, Object newAttr) {
		attr = ObjectList.replace(attr, oldAttr, newAttr);
	}

	/**
	 * this only returns the first attr of this type, there can be more if you
	 * don't use client private types or the provided type is too general
	 */
	public <T> T getAttr(Class<T> attrType) {
		return ObjectList.getFirst(attr, attrType);
	}

	public <T> T getNextAttr(Class<T> attrType, Object prev) {
		return ObjectList.getNext(attr, attrType, prev);
	}

	public ObjectList.Iterator attrIterator() {
		return ObjectList.iterator(attr);
	}

	public <T> ObjectList.TypedIterator<T> attrIterator(Class<T> attrType) {
		return ObjectList.typedIterator(attr, attrType);
	}

	// -- end attrs --

//	protected Instruction firstPostDominator;
//
//	public void setFirstPosDominator(Instruction insn) {
//		firstPostDominator = insn;
//	}
//
//	public Instruction getFirstPosDominator() {
//		return this.firstPostDominator;
//	}
//
//	public Tree<SourceRef> getTree() {
//		return getTree(false);
//	}
//
//	public Tree<SourceRef> getDefaultTree() {
//		return getTree(true);
//	}
//
//	static MethodInfo lastMI;
//	static int lastLine;
//	static SingleTree<SourceRef> lastSimpleSet;
//	SingleTree<SourceRef> simpleSet;
//
//	private Tree<SourceRef> getTree(boolean defaultSet) {
//
//		// check if this instruction already has a set (important for loops)
//		if (simpleSet != null) {
//			return simpleSet;
//		}
//
//		Tree<SourceRef> EMPTY = (Tree<SourceRef>) TreeFactory.EMPTY_TREE;
//		// check if it is possible to read source
//		if (mi == null)
//			return EMPTY;
//		ClassInfo mci = mi.getClassInfo();
//		if (mci == null)
//			return EMPTY;
//
//		if (this.getMethodInfo().getClassInfo().getInterest() == 0) {
//			return TreeFactory.EMPTY_TREE;
//		}
//
//		int line = getLineNumber(); // TODO: please, check if this is ok
//		// int line = ti.getLine();
//		// MethodInfo mi = ti.getMethod();
//		if (lastMI == mi && lastLine == line) {
//			simpleSet = lastSimpleSet;
//			return simpleSet;
//		}
//		lastMI = mi;
//		lastLine = line;
//
//		SourceRef sr;
//		String fname = mci.getSourceFileName();
//		if (fname == null) {
//			throw new RuntimeException("this can really happen");
//		}
//
//		sr = (defaultSet) ? new SourceRef("<>" + fname, line) : new SourceRef(
//				fname, line);
//		simpleSet = new SingleTree<SourceRef>(sr, hasChanged());
//
//		if (simpleSet == null) {
//			throw new RuntimeException("could not create source ref ");
//		}
//
//		lastSimpleSet = simpleSet;
//		return simpleSet;
//	}
//
//	int hasChanged = -1; // -1=not tested, 0=not changed, 1=changed
//
//	public boolean hasChanged() {// ThreadInfo ti
//		if (!SetupSlicer.CHANGE_SLICE) {
//			hasChanged = 1;
//			return true;
//		}
//		if (hasChanged == -1) {
//			hasChanged = 0;
//
//			String fname = mi.getSourceFileName(); // ti.getMethod().getSourceFileName();
//			int line = getLineNumber(); // ti.getLine();
//			fname = fname.substring(0, fname.length() - 5);
//			// TODO: possible hotspot
//			List<Interval> intervals = SetupSlicer.differences.get(fname);
//			if (intervals != null) {
//				IntervalMembership im = new IntervalMembership(intervals);
//				hasChanged = im.contains(line) ? 1 : 0;
//			}
//		}
//		return hasChanged == 1;
//	}
//
//	public int getOffset() {
//		return insnIndex;
//	}

}
